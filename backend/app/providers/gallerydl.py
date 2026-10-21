"""Images via gallery-dl, run as a separate program (it is GPL-2.0-only; never import it)."""

import contextlib
import json
import logging
import os
import re
import signal
import subprocess
import tempfile
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from app.cookies import COOKIE_PLATFORMS, CookieStore
from app.providers.base import (
    IMAGE_OPTIONS,
    DownloadedFiles,
    DownloadOption,
    MediaInfo,
    MediaKind,
    Progress,
    ProgressCallback,
    ProviderError,
)
from app.urls import host_matches, trusted_thumbnail

log = logging.getLogger(__name__)

OUTPUT_CAP = 5 * 1024 * 1024
TITLE_MAX = 120
# Zero-padded so the files sort in post order. `num` counts files within the post.
FILENAME_FORMAT = "{num:>03}_{id}.{extension}"

# Phrases only: stderr also contains URLs, so a bare "login" or "401" would match those.
_LOGIN_RE = re.compile(
    r"redirect to (login|challenge)|login (is )?required|\blog in\b|logged in|sign in|"
    r"authentication (is )?required|checkpoint required",
    re.IGNORECASE,
)
_NOT_FOUND_RE = re.compile(r"not found|\b404\b|does not exist|no longer available", re.IGNORECASE)
_RATE_LIMIT_RE = re.compile(r"\b429\b|too many requests|rate.?limit", re.IGNORECASE)
_SKIPPED_SUFFIXES = (".part", ".json")
_VIDEO_EXTENSIONS = {"mp4", "mov", "m4v", "webm", "mkv", "avi", "mpg", "mpeg", "3gp"}
SIZE_POLL_SECONDS = 1.0


@dataclass
class CompletedRun:
    returncode: int
    stdout: str
    stderr: str
    timed_out: bool = False
    # Killed because the output directory outgrew the job cap.
    too_large: bool = False


@dataclass(frozen=True)
class SizeWatch:
    directory: Path
    max_bytes: int


# (argv, env, cwd, timeout, watch) -> CompletedRun
Runner = Callable[..., CompletedRun]


def _drain(stream: Any, sink: bytearray) -> None:
    """Keep the first OUTPUT_CAP bytes and discard the rest so the process never blocks."""
    while chunk := stream.read(65536):
        room = OUTPUT_CAP - len(sink)
        if room > 0:
            sink += chunk[:room]


def _tree_size(directory: Path) -> int:
    total = 0
    for path in directory.rglob("*"):
        with contextlib.suppress(OSError):  # files come and go while gallery-dl runs
            if path.is_file() and not path.is_symlink():
                total += path.stat().st_size
    return total


def run_subprocess(
    argv: list[str],
    *,
    env: dict[str, str],
    cwd: Path,
    timeout: int,
    watch: SizeWatch | None = None,
) -> CompletedRun:
    proc = subprocess.Popen(  # noqa: S603 - argv list, no shell
        argv,
        env=env,
        cwd=cwd,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        start_new_session=True,
    )
    out, err = bytearray(), bytearray()
    threads = [
        threading.Thread(target=_drain, args=(proc.stdout, out), daemon=True),
        threading.Thread(target=_drain, args=(proc.stderr, err), daemon=True),
    ]
    for t in threads:
        t.start()
    timed_out = too_large = False
    deadline = time.monotonic() + timeout
    try:
        # gallery-dl has no job-wide size limit of its own, so poll the output directory.
        while True:
            remaining = deadline - time.monotonic()
            try:
                proc.wait(timeout=max(0, min(SIZE_POLL_SECONDS, remaining)))
                break
            except subprocess.TimeoutExpired:
                if remaining <= SIZE_POLL_SECONDS:
                    timed_out = True
                    break
                if watch and _tree_size(watch.directory) > watch.max_bytes:
                    too_large = True
                    break
    finally:
        # Also covers children it spawned; they share the new session's process group.
        with contextlib.suppress(ProcessLookupError, PermissionError):
            os.killpg(proc.pid, signal.SIGKILL)
        proc.wait()
    for t in threads:
        t.join(timeout=5)
    return CompletedRun(
        proc.returncode,
        out.decode("utf-8", "replace"),
        err.decode("utf-8", "replace"),
        timed_out,
        too_large,
    )


class GalleryDlProvider:
    def __init__(
        self,
        platform: str,
        domains: list[str],
        path_pattern: re.Pattern[str],
        cookies: CookieStore | None,
        *,
        requires_cookies: bool = False,
        max_items: int = 50,
        max_filesize: int | None = None,
        max_job_bytes: int | None = None,
        thumbnail_hosts: list[str] | None = None,
        timeout: int = 300,
        binary: str = "gallery-dl",
        runner: Runner = run_subprocess,
    ):
        self.platform = platform
        self._label = platform.capitalize()
        self._domains = domains
        self._path_pattern = path_pattern
        self._cookies = cookies
        self._requires_cookies = requires_cookies
        self._max_items = max_items
        self._max_filesize = max_filesize
        self._max_job_bytes = max_job_bytes
        self._thumbnail_hosts = thumbnail_hosts
        self.options = IMAGE_OPTIONS
        self._timeout = timeout
        self._binary = binary
        self._runner = runner

    def supports(self, url: str) -> bool:
        """Allowed host AND path_pattern matches AND (cookies present if requires_cookies)."""
        if not host_matches(url, self._domains) or not self._path_pattern.match(urlsplit(url).path):
            return False
        return not self._requires_cookies or self._has_cookies()

    def _has_cookies(self) -> bool:
        return (
            self._cookies is not None
            and self.platform in COOKIE_PLATFORMS
            and self._cookies.has(self.platform)
        )

    def _config(self, out_dir: Path, cookie_file: Path | None) -> dict[str, Any]:
        extractor: dict[str, Any] = {
            "base-directory": str(out_dir),
            "directory": [],
            "filename": FILENAME_FORMAT,
            "retries": 2,
            "timeout": 20,
            "sleep-request": 1,
            # Photo posts come with their background music as an extra file; we only want photos.
            "tiktok": {"audio": False},
        }
        if cookie_file is not None:
            extractor["cookies"] = str(cookie_file)
            extractor["cookies-update"] = False
        downloader: dict[str, Any] = {}
        # A single file can't be larger than the whole job.
        if limit := min(filter(None, [self._max_filesize, self._max_job_bytes]), default=None):
            downloader["filesize-max"] = limit
        return {"extractor": extractor, "downloader": downloader}

    def _run(
        self, url: str, out_dir: Path, *, dump: bool, watch: SizeWatch | None = None
    ) -> CompletedRun:
        if urlsplit(url).scheme not in ("http", "https"):
            raise ProviderError("This link isn't supported")
        with tempfile.TemporaryDirectory(prefix="opentakez-gdl-") as tmp:
            run_dir = Path(tmp)
            lease = (
                self._cookies.lease(self.platform)
                if self._cookies is not None and self.platform in COOKIE_PLATFORMS
                else contextlib.nullcontext(None)
            )
            with lease as cookie_file:
                config = run_dir / "config.json"
                fd = os.open(config, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                with os.fdopen(fd, "w") as f:
                    json.dump(self._config(out_dir, cookie_file), f)
                argv = [
                    self._binary,
                    "--config-ignore",
                    "--no-input",
                    "-c",
                    str(config),
                    "--range",
                    f"1-{self._max_items}",
                    *(["-j"] if dump else []),
                    url,
                ]
                env = {
                    "PATH": os.environ.get("PATH", os.defpath),
                    "LANG": "C.UTF-8",
                    "HOME": str(run_dir),
                }
                try:
                    result = self._runner(
                        argv, env=env, cwd=run_dir, timeout=self._timeout, watch=watch
                    )
                except FileNotFoundError as e:
                    if e.filename not in (None, argv[0]):
                        log.error("gallery-dl could not start: %s is missing", e.filename)
                        raise ProviderError("Couldn't load this post. Try again later.") from None
                    raise ProviderError("Image downloads aren't available on this server") from None
        if result.too_large:
            raise ProviderError("Download is larger than the size limit.")
        if result.timed_out:
            raise ProviderError("The platform took too long to respond. Try again later.")
        return result

    def _error(self, result: CompletedRun, records: list[Any] | None = None) -> ProviderError:
        """Map a failed run to a user message. Raw output may contain paths; never echo it."""
        text = result.stderr
        for record in records or []:
            if (
                isinstance(record, list)
                and record[:1] == [-1]
                and len(record) > 1
                and isinstance(record[1], dict)
            ):
                text += " " + str(record[1].get("message", ""))
        if _NOT_FOUND_RE.search(text):
            return ProviderError("This post wasn't found. It may be private or deleted.")
        if _LOGIN_RE.search(text):
            return ProviderError(
                f"This post needs a login. Upload cookies for {self._label} in Settings.",
                code="login_required",
            )
        if _RATE_LIMIT_RE.search(text):
            return ProviderError("The platform is rate-limiting requests. Try again later.")
        return ProviderError("Couldn't load this post. Try again later.")

    def resolve(self, url: str) -> MediaInfo:
        with tempfile.TemporaryDirectory(prefix="opentakez-gdl-out-") as unused:
            result = self._run(url, Path(unused), dump=True)
        try:
            records = json.loads(result.stdout) if result.stdout.strip() else []
        except json.JSONDecodeError:
            records = []
        if not isinstance(records, list):
            records = []
        files = _file_records(records)
        failed = any(isinstance(r, list) and r[:1] == [-1] for r in records)
        if failed or (not files and (result.returncode != 0 or "[error]" in result.stderr)):
            raise self._error(result, records)
        if not files:
            raise ProviderError("No images or videos found in this post.")
        meta = next(
            (
                r[1]
                for r in records
                if isinstance(r, list) and r[:1] == [2] and len(r) > 1 and isinstance(r[1], dict)
            ),
            None,
        )
        meta = meta or files[0][2]
        images = [f for f in files if _extension(f) not in _VIDEO_EXTENSIONS]
        return MediaInfo(
            url=url,
            platform=self.platform,
            # Posts of only videos are shown as videos, but still saved as originals.
            kind=MediaKind.IMAGE if images else MediaKind.VIDEO,
            title=self._title(meta),
            uploader=_uploader(meta),
            thumbnail=trusted_thumbnail(images[0][1] if images else None, self._thumbnail_hosts),
            options=IMAGE_OPTIONS,
            item_count=len(files),
        )

    def _title(self, meta: dict[str, Any]) -> str:
        for key in ("description", "caption", "title", "desc"):
            value = meta.get(key)
            if isinstance(value, str) and value.strip():
                text = " ".join(value.split())
                return text if len(text) <= TITLE_MAX else text[: TITLE_MAX - 1].rstrip() + "…"
        post_id = meta.get("post_id") or meta.get("id") or meta.get("shortcode")
        return f"{self._label} post {post_id}" if post_id else f"{self._label} post"

    def download(
        self, url: str, option: DownloadOption, out_dir: Path, on_progress: ProgressCallback
    ) -> list[Path]:
        on_progress(Progress(phase="downloading", fraction=None))
        result = self._run(
            url,
            out_dir,
            dump=False,
            watch=SizeWatch(out_dir, self._max_job_bytes) if self._max_job_bytes else None,
        )
        files = sorted(
            p
            for p in out_dir.iterdir()
            if p.is_file() and not p.name.startswith(".") and not p.name.endswith(_SKIPPED_SUFFIXES)
        )
        if not files:
            if result.returncode != 0 or "[error]" in result.stderr:
                raise self._error(result)
            raise ProviderError("No images or videos found in this post.")
        failed = result.returncode != 0 or "[error]" in result.stderr
        # Some items were saved and others failed: keep what we have and say how many are missing.
        skipped = max(1, result.stderr.count("[error]")) if failed else 0
        return DownloadedFiles(files, skipped=skipped)


def _extension(record: list[Any]) -> str:
    ext = record[2].get("extension")
    if not isinstance(ext, str) or not ext:
        ext = Path(urlsplit(record[1]).path).suffix.lstrip(".")
    return ext.lower()


def _file_records(records: list[Any]) -> list[Any]:
    return [
        r
        for r in records
        if isinstance(r, list)
        and len(r) >= 3
        and r[0] == 3
        and isinstance(r[1], str)
        and isinstance(r[2], dict)
    ]


def _uploader(meta: dict[str, Any]) -> str | None:
    for key in ("username", "uploader", "user"):
        if isinstance(meta.get(key), str) and meta[key]:
            return meta[key]
    for key in ("user", "author", "owner"):
        user = meta.get(key)
        if isinstance(user, dict):
            for sub in ("username", "uniqueId", "nickname"):
                if isinstance(user.get(sub), str) and user[sub]:
                    return user[sub]
    return None
