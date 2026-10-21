import asyncio
import unicodedata
from collections.abc import AsyncIterator
from typing import Annotated
from urllib.parse import quote

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.responses import FileResponse, StreamingResponse
from pydantic import BaseModel, Field

from app.api.auth import require_token
from app.jobs.manager import FINISHED, Job, JobManager
from app.providers import Providers, UnsupportedUrlError
from app.providers.base import DownloadOption, MediaInfo, Provider, ProviderError
from app.urls import extract_url

router = APIRouter(prefix="/api", dependencies=[Depends(require_token)])

SSE_POLL_SECONDS = 0.5


def get_providers(request: Request) -> Providers:
    return request.app.state.providers


def get_jobs(request: Request) -> JobManager:
    return request.app.state.jobs


ProvidersDep = Annotated[Providers, Depends(get_providers)]
JobsDep = Annotated[JobManager, Depends(get_jobs)]


class ResolveRequest(BaseModel):
    # May be shared text that contains a link, not just the link itself.
    url: str = Field(max_length=4000)


class JobRequest(ResolveRequest):
    option: DownloadOption


def _provider_for(providers: Providers, text: str) -> tuple[Provider, str]:
    url = extract_url(text)
    if url is None:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "No link found")
    try:
        return providers.for_url(url), url
    except UnsupportedUrlError:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "This site isn't supported") from None


def _content_disposition(name: str) -> str:
    """Both forms: plain `filename` for clients that ignore the UTF-8 `filename*`."""
    fallback = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode()
    fallback = fallback.replace('"', "").replace("\\", "") or "download"
    return f"attachment; filename=\"{fallback}\"; filename*=UTF-8''{quote(name)}"


def _job_or_404(jobs: JobManager, job_id: str) -> Job:
    job = jobs.get(job_id)
    if job is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Job not found")
    return job


@router.post("/resolve")
def resolve(body: ResolveRequest, providers: ProvidersDep) -> MediaInfo:
    provider, url = _provider_for(providers, body.url)
    try:
        return provider.resolve(url)
    except ProviderError as e:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, str(e)) from e


@router.post("/jobs", status_code=status.HTTP_201_CREATED)
def create_job(body: JobRequest, providers: ProvidersDep, jobs: JobsDep) -> Job:
    provider, url = _provider_for(providers, body.url)
    return jobs.submit(provider, url, body.option)


@router.get("/jobs/{job_id}")
def get_job(job_id: str, jobs: JobsDep) -> Job:
    return _job_or_404(jobs, job_id)


@router.get("/jobs/{job_id}/events")
async def job_events(job_id: str, request: Request, jobs: JobsDep) -> StreamingResponse:
    """Server-sent events with the job state whenever it changes, until it finishes."""
    _job_or_404(jobs, job_id)

    async def stream() -> AsyncIterator[str]:
        last = None
        while not await request.is_disconnected():
            job = jobs.get(job_id)
            if job is None:
                return
            data = job.model_dump_json()
            if data != last:
                yield f"data: {data}\n\n"
                last = data
            if job.status in FINISHED:
                return
            await asyncio.sleep(SSE_POLL_SECONDS)

    return StreamingResponse(
        stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.get("/jobs/{job_id}/file")
def job_file(job_id: str, jobs: JobsDep) -> FileResponse:
    job = _job_or_404(jobs, job_id)
    if not job.files:
        raise HTTPException(status.HTTP_409_CONFLICT, "Download isn't finished")
    name = job.files[0]
    return FileResponse(
        jobs.file_path(job, name),
        headers={"Content-Disposition": _content_disposition(name)},
    )


@router.delete("/jobs/{job_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_job(job_id: str, jobs: JobsDep) -> None:
    _job_or_404(jobs, job_id)
    if not jobs.delete(job_id):
        raise HTTPException(status.HTTP_409_CONFLICT, "Job is still running")
