import logging
import re
import secrets
from pathlib import Path

from fastapi import Request, Response
from starlette.middleware.base import RequestResponseEndpoint

from app.config import Settings

log = logging.getLogger("opentakez")

_TOKEN_QUERY_RE = re.compile(r"(token=)[^&\s\"]+")

# The PWA loads thumbnails from platform CDNs and may talk to another self-hosted server
# (Settings → server URL), so images and connections allow any https origin.
CONTENT_SECURITY_POLICY = "; ".join(
    [
        "default-src 'self'",
        "script-src 'self'",
        "style-src 'self' 'unsafe-inline'",
        "img-src 'self' data: https:",
        "connect-src 'self' https: http:",
        "manifest-src 'self'",
        "worker-src 'self'",
        "object-src 'none'",
        "base-uri 'none'",
        "frame-ancestors 'none'",
    ]
)


def ensure_token(settings: Settings) -> None:
    """Never run as an open download proxy by accident.

    Without a configured token, generate one on first start, keep it in the data dir so it
    survives restarts, and print it so the owner can enter it in the app.
    """
    if settings.token or settings.allow_no_token:
        return
    token_file = Path(settings.data_dir) / "token"
    if token_file.is_file():
        settings.token = token_file.read_text().strip()
    if not settings.token:
        settings.token = secrets.token_urlsafe(24)
        token_file.parent.mkdir(parents=True, exist_ok=True)
        token_file.write_text(settings.token)
        token_file.chmod(0o600)
    log.warning(
        "No OPENTAKEZ_TOKEN set. Using the generated token from %s: %s", token_file, settings.token
    )


class RedactTokenFilter(logging.Filter):
    """Remove `?token=` values from access log lines (EventSource and file links carry it)."""

    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.args, tuple):
            record.args = tuple(
                _TOKEN_QUERY_RE.sub(r"\1[redacted]", arg) if isinstance(arg, str) else arg
                for arg in record.args
            )
        return True


async def security_headers(request: Request, call_next: RequestResponseEndpoint) -> Response:
    response = await call_next(request)
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("Referrer-Policy", "no-referrer")
    response.headers.setdefault("X-Frame-Options", "DENY")
    if request.url.path.startswith("/api/"):
        response.headers.setdefault("Cache-Control", "no-store")
    else:
        response.headers.setdefault("Content-Security-Policy", CONTENT_SECURITY_POLICY)
    return response
