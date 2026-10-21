import asyncio
import contextlib
import logging
import os
import subprocess
from collections.abc import AsyncIterator
from datetime import timedelta
from importlib.metadata import version

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

from app import __version__
from app.api.cookies import router as cookies_router
from app.api.routes import router
from app.config import settings
from app.cookies import COOKIE_PLATFORMS, CookieStore
from app.jobs.manager import JobManager
from app.providers import default_providers
from app.security import RedactTokenFilter, ensure_token, security_headers

CLEANUP_INTERVAL_SECONDS = 600


async def _cleanup_loop(jobs: JobManager) -> None:
    while True:
        await asyncio.sleep(CLEANUP_INTERVAL_SECONDS)
        jobs.cleanup(timedelta(hours=settings.file_ttl_hours))


_gallery_dl_versions: dict[str, str] = {}


def _gallery_dl_version() -> str | None:
    """Cached on success only, so a binary that appears later (or a flaky start) is retried."""
    binary = settings.gallery_dl_bin
    if binary in _gallery_dl_versions:
        return _gallery_dl_versions[binary]
    try:
        out = subprocess.run(  # noqa: S603
            [binary, "--version"],
            capture_output=True,
            text=True,
            timeout=15,
            check=True,
            env={"PATH": os.environ.get("PATH", "")},
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None
    if not out:
        return None
    _gallery_dl_versions[binary] = out.splitlines()[0]
    return _gallery_dl_versions[binary]


@contextlib.asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    logging.getLogger("uvicorn.access").addFilter(RedactTokenFilter())
    ensure_token(settings)
    app.state.cookies = CookieStore(settings.data_dir, settings.cookie_max_kb * 1024)
    app.state.providers = default_providers(app.state.cookies)
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
        "gallery_dl": _gallery_dl_version(),
        "platforms": app.state.providers.platforms,
        "cookie_platforms": list(COOKIE_PLATFORMS),
    }


app.include_router(router)
app.include_router(cookies_router)


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
