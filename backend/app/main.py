import asyncio
import contextlib
import logging
from collections.abc import AsyncIterator
from datetime import timedelta
from importlib.metadata import version

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

from app import __version__
from app.api.routes import router
from app.config import settings
from app.jobs.manager import JobManager
from app.providers import default_providers
from app.security import RedactTokenFilter, ensure_token, security_headers

CLEANUP_INTERVAL_SECONDS = 600


async def _cleanup_loop(jobs: JobManager) -> None:
    while True:
        await asyncio.sleep(CLEANUP_INTERVAL_SECONDS)
        jobs.cleanup(timedelta(hours=settings.file_ttl_hours))


@contextlib.asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    logging.getLogger("uvicorn.access").addFilter(RedactTokenFilter())
    ensure_token(settings)
    app.state.providers = default_providers()
    app.state.jobs = JobManager(
        settings.data_dir, settings.max_concurrent_jobs, settings.max_queued_jobs
    )
    cleanup = asyncio.create_task(_cleanup_loop(app.state.jobs))
    yield
    cleanup.cancel()
    app.state.jobs.shutdown()


app = FastAPI(title="OpenTakez", version=__version__, lifespan=lifespan)

app.middleware("http")(security_headers)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/api/health")
def health() -> dict[str, object]:
    return {
        "status": "ok",
        "version": app.version,
        "yt_dlp": version("yt-dlp"),
        "auth_required": bool(settings.token),
        "platforms": app.state.providers.platforms,
    }


app.include_router(router)


# Serve the built PWA when it's bundled (Docker image). Unknown paths fall back to
# index.html so client-side routes like /share work.
@app.get("/{path:path}", include_in_schema=False)
def spa(path: str) -> FileResponse:
    web_root = settings.web_dir.resolve()
    if path.startswith("api/") or not web_root.is_dir():
        raise HTTPException(status_code=404)
    try:
        file = (web_root / path).resolve()
    except (ValueError, OSError):  # e.g. NUL bytes in the path
        file = web_root
    if file.is_file() and file.is_relative_to(web_root):
        return FileResponse(file)
    return FileResponse(web_root / "index.html")
