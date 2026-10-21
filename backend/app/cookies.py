"""Uploaded cookies.txt files (login sessions) per platform.

Cookies are credentials: files are 0600 in a 0700 directory, rewritten from parsed fields
(never stored verbatim), and neither errors nor logs ever contain names or values.
"""

import os
import shutil
import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path
from typing import NamedTuple

from pydantic import BaseModel

from app.urls import host_matches

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


MAX_LINES = 1000
HTTPONLY_PREFIX = "#HttpOnly_"
_BOOLS = ("TRUE", "FALSE")


class _Cookie(NamedTuple):
    domain: str
    include_subdomains: str
    path: str
    secure: str
    expiry: int
    name: str
    value: str
    http_only: bool

    def serialize(self) -> str:
        domain = (HTTPONLY_PREFIX if self.http_only else "") + self.domain
        return "\t".join(
            [
                domain,
                self.include_subdomains,
                self.path,
                self.secure,
                str(self.expiry),
                self.name,
                self.value,
            ]
        )


def _has_control(text: str) -> bool:
    return any(ord(c) < 32 or ord(c) == 127 for c in text)


def _parse_line(raw: str, number: int) -> _Cookie | None:
    http_only = raw.startswith(HTTPONLY_PREFIX)
    if http_only:
        raw = raw[len(HTTPONLY_PREFIX) :]
    elif not raw.strip() or raw.startswith("#"):
        return None
    fields = raw.split("\t")
    if len(fields) != 7:
        raise CookieError(f"Line {number} isn't a valid cookie (expected 7 tab-separated fields)")
    domain, subdomains, path, secure, expiry, name, value = fields
    if any(_has_control(f) for f in fields):
        raise CookieError(f"Line {number} contains control characters")
    if not domain.strip("."):
        raise CookieError(f"Line {number} has no domain")
    if subdomains not in _BOOLS or secure not in _BOOLS:
        raise CookieError(f"Line {number} has an invalid TRUE/FALSE flag")
    if not path.startswith("/"):
        raise CookieError(f"Line {number} has an invalid path")
    try:
        expires = int(expiry)
    except ValueError:
        raise CookieError(f"Line {number} has an invalid expiry") from None
    if expires < 0:
        raise CookieError(f"Line {number} has an invalid expiry")
    if not name:
        raise CookieError(f"Line {number} has an empty cookie name")
    return _Cookie(domain, subdomains, path, secure, expires, name, value, http_only)


def _parse(platform: str, data: str | bytes, max_bytes: int) -> tuple[list[_Cookie], int]:
    """Valid cookies of `platform`, and the number of cookies for other sites that were dropped."""
    raw = data if isinstance(data, bytes) else data.encode()
    if len(raw) > max_bytes:
        raise CookieError(f"The file is too large (limit {max_bytes // 1024} KB)")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        raise CookieError("The file isn't valid UTF-8 text") from None
    if "\x00" in text:
        raise CookieError("The file isn't a text file")
    lines = text.splitlines()
    if len(lines) > MAX_LINES:
        raise CookieError(f"The file has too many lines (limit {MAX_LINES})")
    domains = COOKIE_PLATFORMS[platform]
    kept: list[_Cookie] = []
    dropped = 0
    for number, line in enumerate(lines, 1):
        cookie = _parse_line(line, number)
        if cookie is None:
            continue
        if host_matches("https://" + cookie.domain.lstrip("."), domains):
            kept.append(cookie)
        else:
            dropped += 1
    if not kept:
        raise CookieError(f"No cookies for {domains[0]} found")
    return kept, dropped


def _write_private(path: Path, content: str) -> None:
    """Atomic write: a 0600 temp file in the same directory, fsynced, then renamed over."""
    tmp = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    fd = os.open(tmp, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as f:
            f.write(content)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise


def _private_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.chmod(0o700)


class CookieStore:
    def __init__(self, data_dir: Path, max_bytes: int):
        self._dir = Path(data_dir) / "cookies"
        self._run = Path(data_dir) / "run"
        self._max_bytes = max_bytes
        _private_dir(self._dir)
        # Leases die with their run; anything left over is from a crashed process.
        shutil.rmtree(self._run, ignore_errors=True)
        _private_dir(self._run)

    def _file(self, platform: str) -> Path:
        if platform not in COOKIE_PLATFORMS:
            raise KeyError(platform)
        return self._dir / f"{platform}.txt"

    def has(self, platform: str) -> bool:
        return self._file(platform).is_file()

    def _read(self, platform: str) -> list[_Cookie]:
        file = self._file(platform)
        try:
            lines = file.read_text(encoding="utf-8").splitlines()
        except UnicodeDecodeError:
            return []
        return [c for n, line in enumerate(lines, 1) if (c := _parse_line(line, n))]

    def status(self, platform: str) -> CookieStatus:
        file = self._file(platform)
        try:
            mtime = file.stat().st_mtime
            cookies = self._read(platform)
        except (FileNotFoundError, CookieError):
            return CookieStatus(platform=platform, present=False)
        expiries = [c.expiry for c in cookies if c.expiry]
        earliest = min(expiries) if expiries else None
        now = datetime.now(UTC).timestamp()
        return CookieStatus(
            platform=platform,
            present=True,
            uploaded_at=datetime.fromtimestamp(mtime, UTC),
            cookie_count=len(cookies),
            earliest_expiry=datetime.fromtimestamp(earliest, UTC) if earliest else None,
            expired=bool(expiries) and max(expiries) < now,
            domains=sorted({c.domain for c in cookies}),
        )

    def save(self, platform: str, text: str | bytes) -> CookieUpload:
        """Validate, keep only this platform's cookies, store atomically (0600)."""
        file = self._file(platform)
        cookies, dropped = _parse(platform, text, self._max_bytes)
        content = "# Netscape HTTP Cookie File\n" + "".join(c.serialize() + "\n" for c in cookies)
        _write_private(file, content)
        return CookieUpload(**self.status(platform).model_dump(), dropped_count=dropped)

    def delete(self, platform: str) -> None:
        self._file(platform).unlink(missing_ok=True)

    @contextmanager
    def lease(self, platform: str) -> Iterator[Path | None]:
        """A private copy of the platform's cookies for one yt-dlp/gallery-dl run, or None.

        yt-dlp writes cookies back on exit; it must only ever touch this copy. The copy is
        deleted when the block ends.
        """
        file = self._file(platform)
        if not file.is_file():
            yield None
            return
        run_dir = self._run / uuid.uuid4().hex
        run_dir.mkdir(parents=True, mode=0o700)
        try:
            copy = run_dir / "cookies.txt"
            try:
                _write_private(copy, file.read_text(encoding="utf-8"))
            except FileNotFoundError:  # deleted between the check and the copy
                yield None
                return
            yield copy
        finally:
            shutil.rmtree(run_dir, ignore_errors=True)
