"""Uploaded cookies.txt files (login sessions) per platform.

CONTRACT STUB (M3 step 0). Implemented by the cookies/gallery-dl track; other code may only
rely on the names and signatures below.
"""

from collections.abc import Iterator
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path

from pydantic import BaseModel

# Platforms that accept cookies, and the domains their cookies may belong to.
COOKIE_PLATFORMS: dict[str, list[str]] = {
    "instagram": ["instagram.com"],
    "tiktok": ["tiktok.com"],
    "facebook": ["facebook.com", "fb.com", "fb.watch"],
}


class CookieError(Exception):
    """Invalid upload. The message is shown to the user and never contains cookie content."""


class CookieStatus(BaseModel):
    platform: str
    present: bool
    uploaded_at: datetime | None = None
    cookie_count: int = 0
    earliest_expiry: datetime | None = None
    expired: bool = False
    domains: list[str] = []


class CookieUpload(CookieStatus):
    # Cookies in the upload that belonged to other sites and were discarded.
    dropped_count: int = 0


class CookieStore:
    def __init__(self, data_dir: Path, max_bytes: int):
        raise NotImplementedError

    def has(self, platform: str) -> bool:
        raise NotImplementedError

    def status(self, platform: str) -> CookieStatus:
        raise NotImplementedError

    def save(self, platform: str, text: str) -> CookieUpload:
        """Validate, keep only this platform's cookies, store atomically (0600)."""
        raise NotImplementedError

    def delete(self, platform: str) -> None:
        raise NotImplementedError

    @contextmanager
    def lease(self, platform: str) -> Iterator[Path | None]:
        """A private copy of the platform's cookies for one yt-dlp/gallery-dl run, or None.

        yt-dlp writes cookies back on exit; it must only ever touch this copy. The copy is
        deleted when the block ends.
        """
        raise NotImplementedError
        yield None
