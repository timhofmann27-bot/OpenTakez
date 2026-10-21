import logging
import shutil
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from pathlib import Path

from pydantic import BaseModel, Field

from app.providers.base import DownloadOption, Progress, Provider, ProviderError

log = logging.getLogger(__name__)


class JobStatus(StrEnum):
    QUEUED = "queued"
    RUNNING = "running"
    DONE = "done"
    ERROR = "error"


FINISHED = {JobStatus.DONE, JobStatus.ERROR}


class Job(BaseModel):
    id: str
    url: str
    platform: str
    option: DownloadOption
    status: JobStatus = JobStatus.QUEUED
    progress: Progress | None = None
    files: list[str] = []
    error: str | None = None
    created_at: datetime = Field(default_factory=lambda: datetime.now(UTC))


class QueueFullError(Exception):
    pass


class JobManager:
    """Runs downloads in a thread pool. Jobs live in memory; each has its own directory.

    Jobs are short-lived (files are deleted after a TTL), so nothing survives a restart.
    """

    def __init__(self, data_dir: Path, max_workers: int, max_queued: int = 20):
        self._root = data_dir / "jobs"
        # Directories left over from a previous run have no job record anymore.
        shutil.rmtree(self._root, ignore_errors=True)
        self._root.mkdir(parents=True)
        self._jobs: dict[str, Job] = {}
        self._lock = threading.Lock()
        self._pool = ThreadPoolExecutor(max_workers=max_workers, thread_name_prefix="download")
        self._max_queued = max_queued

    def submit(self, provider: Provider, url: str, option: DownloadOption) -> Job:
        job = Job(id=uuid.uuid4().hex, url=url, platform=provider.platform, option=option)
        with self._lock:
            pending = sum(j.status not in FINISHED for j in self._jobs.values())
            if pending >= self._max_queued:
                raise QueueFullError
            self._jobs[job.id] = job
        self._pool.submit(self._run, job.id, provider)
        return job.model_copy()

    def get(self, job_id: str) -> Job | None:
        with self._lock:
            job = self._jobs.get(job_id)
            return job.model_copy(deep=True) if job else None

    def file_path(self, job: Job, name: str) -> Path:
        return self._job_dir(job.id) / name

    def delete(self, job_id: str) -> bool:
        """Remove a finished job and its files. Running jobs can't be cancelled yet."""
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None or job.status not in FINISHED:
                return False
            del self._jobs[job_id]
        shutil.rmtree(self._job_dir(job_id), ignore_errors=True)
        return True

    def cleanup(self, max_age: timedelta) -> int:
        cutoff = datetime.now(UTC) - max_age
        with self._lock:
            expired = [
                job.id
                for job in self._jobs.values()
                if job.status in FINISHED and job.created_at < cutoff
            ]
        return sum(self.delete(job_id) for job_id in expired)

    def shutdown(self) -> None:
        self._pool.shutdown(wait=False, cancel_futures=True)

    def _job_dir(self, job_id: str) -> Path:
        return self._root / job_id

    def _update(self, job_id: str, **changes: object) -> None:
        with self._lock:
            job = self._jobs.get(job_id)
            if job is not None:
                for key, value in changes.items():
                    setattr(job, key, value)

    def _run(self, job_id: str, provider: Provider) -> None:
        job = self.get(job_id)
        if job is None:
            return
        out_dir = self._job_dir(job_id)
        out_dir.mkdir()
        self._update(job_id, status=JobStatus.RUNNING)
        try:
            files = provider.download(
                job.url, job.option, out_dir, lambda p: self._update(job_id, progress=p)
            )
        except ProviderError as e:
            self._update(job_id, status=JobStatus.ERROR, error=str(e))
        except Exception:
            log.exception("Download %s failed", job_id)
            self._update(job_id, status=JobStatus.ERROR, error="Unexpected error while downloading")
        else:
            self._update(job_id, status=JobStatus.DONE, files=[f.name for f in files])
