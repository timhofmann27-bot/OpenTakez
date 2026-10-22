import asyncio
import contextlib
import logging
import os
import subprocess
import threading
import time
from collections.abc import AsyncIterator
from datetime import timedelta
from importlib.metadata import version

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

from app import __version__
from app.api.cookies import router as cookies_router
from app.api.routes import link_router, router
from app.config import settings
from app.cookies import COOKIE_PLATFORMS, CookieStore
from app.jobs.manager import JobManager
from app.providers import default_providers
from app.security import HostGuard, RedactTokenFilter, ensure_token, security_headers

CLEANUP_INTERVAL_SECONDS = 600


async def _cleanup_loop(jobs: JobManager) -> None:
    while True:
        await asyncio.sleep(CLEANUP_INTERVAL_SECONDS)
        jobs.cleanup(timedelta(hours=settings.file_ttl_hours))


_gallery_dl_versions: dict[str, tuple[str | None, float]] = {}
_gallery_dl_lock = threading.Lock()


def _gallery_dl_version() -> str | None:
    """Cache success indefinitely and failures briefly to keep health checks cheap."""
    with _gallery_dl_lock:
        return _gallery_dl_version_locked()


def _gallery_dl_version_locked() -> str | None:
    binary = settings.gallery_dl_bin
    cached = _gallery_dl_versions.get(binary)
    if cached and cached[1] > time.monotonic():
        return cached[0]
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
        _gallery_dl_versions[binary] = (None, time.monotonic() + 60)
        return None
    if not out:
        _gallery_dl_versions[binary] = (None, time.monotonic() + 60)
        return None
    result = out.splitlines()[0]
    _gallery_dl_versions[binary] = (result, float("inf"))
    return result


@contextlib.asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    logging.getLogger("uvicorn.access").addFilter(RedactTokenFilter())
    ensure_token(settings)
    if settings.allow_no_token and settings.allowed_hosts == ["*"]:
        logging.getLogger("opentakez").warning(
            "Open instance with unrestricted Host headers: set OPENTAKEZ_ALLOWED_HOSTS "
            "to prevent DNS rebinding"
        )
    if os.environ.get("OPENTAKEZ_FORWARDED_ALLOW_IPS") == "*":
        logging.getLogger("opentakez").warning(
            "OPENTAKEZ_FORWARDED_ALLOW_IPS=* trusts forwarded IPs from every client; "
            "only use it behind a trusted reverse proxy"
        )
    app.state.cookies = CookieStore(settings.data_dir, settings.cookie_max_kb * 1024)
    app.state.providers = default_providers(app.state.cookies)
    app.state.gallery_dl_version = _gallery_dl_version()
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
    expose_headers=["Date", "Retry-After"],
)
app.add_middleware(HostGuard, allowed_hosts=settings.allowed_hosts)


@app.get("/api/health")
def health() -> dict[str, object]:
    return {
        "status": "ok",
        "version": app.version,
        "yt_dlp": version("yt-dlp"),
        "auth_required": bool(settings.token),
        "gallery_dl": app.state.gallery_dl_version,
        "platforms": app.state.providers.platforms,
        "cookie_platforms": list(COOKIE_PLATFORMS),
    }


app.include_router(router)
app.include_router(link_router)
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
