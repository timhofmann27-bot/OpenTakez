import time
from pathlib import Path

import pytest

from app.config import settings
from app.jobs.manager import Job, JobManager, JobStatus
from app.providers.base import (
    VIDEO_OPTIONS,
    DownloadedFiles,
    DownloadOption,
    ProviderError,
)


class ScriptedProvider:
    platform = "fake"
    options = VIDEO_OPTIONS

    def __init__(self, action):
        self.action = action

    def download(self, url, option, out_dir: Path, on_progress):
        return self.action(out_dir)


@pytest.fixture
def manager(tmp_path):
    m = JobManager(tmp_path, max_workers=1)
    yield m
    m.shutdown()


def run(manager: JobManager, action) -> tuple[Job, Path]:
    job = manager.submit(
        ScriptedProvider(action), "https://example.com/x", DownloadOption.VIDEO_BEST
    )
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        current = manager.get(job.id)
        if current and current.status in (JobStatus.DONE, JobStatus.ERROR):
            return current, manager._job_dir(job.id)
        time.sleep(0.01)
    raise AssertionError(f"job stayed {manager.get(job.id).status}")


def test_skipped_count_is_kept(manager):
    def action(out: Path):
        (out / "a.mp4").write_bytes(b"x")
        return DownloadedFiles([out / "a.mp4"], skipped=3)

    job, _ = run(manager, action)

    assert (job.status, job.files, job.skipped) == (JobStatus.DONE, ["a.mp4"], 3)


def test_plain_file_lists_have_no_skipped(manager):
    def action(out: Path):
        (out / "a.mp4").write_bytes(b"x")
        return [out / "a.mp4"]

    job, _ = run(manager, action)

    assert job.status is JobStatus.DONE and job.skipped == 0


def test_unreadable_result_ends_in_error_and_cleans_up(manager):
    def action(out: Path):
        return [out / "never-written.mp4"]  # stat() raises

    job, job_dir = run(manager, action)

    assert job.status is JobStatus.ERROR and job.error
    assert not job_dir.exists()


def test_failure_to_create_the_job_directory_is_an_error(manager, monkeypatch):
    def broken_mkdir(self, *a, **k):
        raise OSError("disk full")

    monkeypatch.setattr(Path, "mkdir", broken_mkdir)

    job, _ = run(manager, lambda out: [])

    assert job.status is JobStatus.ERROR


@pytest.mark.parametrize("error", [ProviderError("nope"), RuntimeError("boom")])
def test_failed_downloads_remove_partial_files(manager, error):
    def action(out: Path):
        (out / "half.mp4.part").write_bytes(b"x")
        raise error

    job, job_dir = run(manager, action)

    assert job.status is JobStatus.ERROR
    assert not job_dir.exists()


def test_job_over_the_size_limit_is_an_error_without_files(manager, monkeypatch):
    monkeypatch.setattr(settings, "max_job_mb", 0)

    def action(out: Path):
        (out / "a.mp4").write_bytes(b"x")
        return [out / "a.mp4"]

    job, job_dir = run(manager, action)

    assert job.status is JobStatus.ERROR and "larger than the limit" in (job.error or "")
    assert not job_dir.exists()
