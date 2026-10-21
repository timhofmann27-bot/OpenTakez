import re
import time
from collections.abc import Callable
from contextlib import nullcontext
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from yt_dlp import YoutubeDL
from yt_dlp.utils import DownloadCancelled, DownloadError

from app.config import settings
from app.cookies import COOKIE_PLATFORMS, CookieStore
from app.providers.base import (
    AUDIO_OPTIONS,
    VIDEO_OPTIONS,
    DownloadedFiles,
    DownloadOption,
    MediaInfo,
    MediaKind,
    Progress,
    ProgressCallback,
    ProviderError,
)
from app.urls import host_matches, trusted_thumbnail

YoutubeDLFactory = Callable[[dict[str, Any]], YoutubeDL]

# Leftovers that yt-dlp may keep next to the final file.
_TEMP_SUFFIXES = {".part", ".ytdl", ".temp"}
# Audio downloads write the thumbnail only to embed it as cover; elsewhere an image is a result.
_COVER_SUFFIXES = {".jpg", ".webp", ".png"}
_ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")
# Flat playlist entries carry no formats; these extractors only have audio.
_AUDIO_ONLY_EXTRACTORS = {"Soundcloud"}
# Only clear login cases. Vaguer messages ("content isn't available") also mean deleted posts.
_LOGIN_HINTS = (
    "login required",
    "log in",
    "registered users",
    "not granting access",
    "empty media response",
    "there is no video in this post",
)
_RATE_LIMIT = "rate-limit"
_PLAYLIST_TYPES = ("playlist", "multi_video")
_CHANNELS_UNSUPPORTED = (
    "Channels and profiles aren't supported yet. Share a single post, video, or playlist."
)


def format_options(option: DownloadOption) -> dict[str, Any]:
    """yt-dlp options for a download option. Video prefers mp4/m4a for phone playback."""
    match option:
        case DownloadOption.VIDEO_BEST:
            sort = ["ext:mp4:m4a"]
        case DownloadOption.VIDEO_1080:
            sort = ["res:1080", "ext:mp4:m4a"]
        case DownloadOption.VIDEO_720:
            sort = ["res:720", "ext:mp4:m4a"]
        case DownloadOption.AUDIO_MP3 | DownloadOption.AUDIO_M4A:
            codec = "mp3" if option is DownloadOption.AUDIO_MP3 else "m4a"
            return {
                "format": "ba[ext=m4a]/ba/b" if codec == "m4a" else "ba/b",
                "writethumbnail": True,
                "postprocessors": [
                    {"key": "FFmpegThumbnailsConvertor", "format": "jpg", "when": "before_dl"},
                    {"key": "FFmpegExtractAudio", "preferredcodec": codec, "preferredquality": "0"},
                    {"key": "FFmpegMetadata", "add_metadata": True},
                    {"key": "EmbedThumbnail"},
                ],
            }
        case _:
            raise ValueError(f"yt-dlp can't download {option.value}")
    return {"format": "bv*+ba/b", "format_sort": sort, "merge_output_format": "mp4"}


def _has_video(info: dict[str, Any]) -> bool:
    formats = info.get("formats") or [info]
    return any(
        f.get("vcodec") not in (None, "none") or (f.get("vcodec") is None and f.get("height"))
        for f in formats
    )


def _error_message(error: DownloadError) -> str:
    return _ANSI_RE.sub("", str(error)).removeprefix("ERROR: ").strip()


def _is_leftover(path: Path, option: DownloadOption) -> bool:
    if path.suffix in _TEMP_SUFFIXES:
        return True
    return option in AUDIO_OPTIONS and path.suffix in _COVER_SUFFIXES


def _first_thumbnail(entry: dict[str, Any]) -> str | None:
    if entry.get("thumbnail"):
        return entry["thumbnail"]
    thumbnails = entry.get("thumbnails") or []
    return thumbnails[-1].get("url") if thumbnails else None


def _entry_key(entry: dict[str, Any]) -> str | None:
    return entry.get("ie_key") or entry.get("extractor_key")


class _ErrorLog:
    """yt-dlp logger that only remembers errors; ignoreerrors would otherwise hide them."""

    def __init__(self) -> None:
        self.errors: list[str] = []

    def debug(self, msg: str) -> None: ...

    def warning(self, msg: str) -> None: ...

    def error(self, msg: str) -> None:
        self.errors.append(_ANSI_RE.sub("", msg).removeprefix("ERROR: ").strip())


class YtDlpProvider:
    def __init__(
        self,
        platform: str,
        domains: list[str],
        extractors: list[str],
        ydl_factory: YoutubeDLFactory = YoutubeDL,
        max_filesize: int | None = None,
        cookies: CookieStore | None = None,
        leaf_extractors: set[str] | None = None,
        playlist_extractors: set[str] | None = None,
        thumbnail_hosts: list[str] | None = None,
    ):
        self.platform = platform
        self.domains = domains
        # Regexes (full match) of yt-dlp extractor names. Keeps the generic extractor out, so
        # yt-dlp never fetches arbitrary pages.
        self.extractors = extractors
        self._ydl_factory = ydl_factory
        self.max_filesize = max_filesize
        self._cookies = cookies
        # ie_keys that may appear as playlist entries; anything else is a channel or similar.
        self._leaf_extractors = leaf_extractors
        # Extractor keys whose playlists are accepted. A channel or profile page lists plain
        # videos too, so its entries alone can't tell it apart from a playlist.
        self._playlist_extractors = playlist_extractors
        self._thumbnail_hosts = thumbnail_hosts
        self.options = VIDEO_OPTIONS

    def supports(self, url: str) -> bool:
        return host_matches(url, self.domains)

    def _base_options(self) -> dict[str, Any]:
        return {
            "quiet": True,
            "no_warnings": True,
            "noprogress": True,
            "noplaylist": True,
            "allowed_extractors": self.extractors,
            # A stalled platform must not hold a worker forever.
            "socket_timeout": 20,
            "retries": 3,
            "extractor_retries": 2,
        }

    def _lease(self):
        return self._cookies.lease(self.platform) if self._cookies else nullcontext(None)

    def _error(self, error: DownloadError) -> ProviderError:
        message = _error_message(error)
        lowered = message.lower()
        if _RATE_LIMIT in lowered:
            return ProviderError(f"{message} Try again later.")
        if self.platform in COOKIE_PLATFORMS and any(h in lowered for h in _LOGIN_HINTS):
            return ProviderError(
                f"{message} Logging in with a cookies.txt for this site may help.",
                code="login_required",
            )
        return ProviderError(message)

    def _extract_flat(self, url: str, cookiefile: Path | None) -> dict[str, Any]:
        # Flat and capped: a playlist URL returns quickly instead of paging through every entry.
        options = {
            **self._base_options(),
            "extract_flat": "in_playlist",
            "playlistend": settings.max_playlist_items,
        }
        if cookiefile is not None:
            options["cookiefile"] = str(cookiefile)
        with self._ydl_factory(options) as ydl:
            return ydl.extract_info(url, download=False)

    def resolve(self, url: str) -> MediaInfo:
        try:
            with self._lease() as cookiefile:
                info = self._extract_flat(url, cookiefile)
        except DownloadError as e:
            raise self._error(e) from e
        if info.get("_type") in _PLAYLIST_TYPES:
            return self._playlist_info(info, url)

        has_video = _has_video(info)
        return MediaInfo(
            url=info.get("webpage_url") or url,
            platform=self.platform,
            kind=MediaKind.VIDEO if has_video else MediaKind.AUDIO,
            title=info.get("title") or info.get("id") or url,
            uploader=info.get("uploader") or info.get("channel"),
            thumbnail=trusted_thumbnail(_first_thumbnail(info), self._thumbnail_hosts),
            duration=info.get("duration"),
            options=VIDEO_OPTIONS if has_video else AUDIO_OPTIONS,
        )

    def _validate_playlist(self, info: dict[str, Any]) -> list[dict[str, Any]]:
        """The entries of a playlist, or a ProviderError for channels, profiles and empty lists."""
        key = info.get("extractor_key")
        if self._playlist_extractors is not None and key not in self._playlist_extractors:
            raise ProviderError(_CHANNELS_UNSUPPORTED)
        # Channel tabs share YouTube's playlist extractor; only /playlist?list= is a playlist.
        if key == "YoutubeTab" and urlsplit(info.get("webpage_url") or "").path != "/playlist":
            raise ProviderError(_CHANNELS_UNSUPPORTED)
        entries = [e for e in info.get("entries") or [] if e]
        for entry in entries:
            if entry.get("_type") in _PLAYLIST_TYPES or (
                self._leaf_extractors is not None and _entry_key(entry) not in self._leaf_extractors
            ):
                raise ProviderError(_CHANNELS_UNSUPPORTED)
        if not entries:
            raise ProviderError("This playlist is empty.")
        return entries

    def _playlist_info(self, info: dict[str, Any], url: str) -> MediaInfo:
        entries = self._validate_playlist(info)
        total = info.get("playlist_count") or len(entries)
        audio_only = all(_entry_key(e) in _AUDIO_ONLY_EXTRACTORS for e in entries) or (
            all("formats" in e for e in entries) and not any(_has_video(e) for e in entries)
        )
        thumbnail = _first_thumbnail(info) or next(
            (t for e in entries if (t := _first_thumbnail(e))), None
        )
        return MediaInfo(
            url=info.get("webpage_url") or url,
            platform=self.platform,
            kind=MediaKind.AUDIO if audio_only else MediaKind.VIDEO,
            title=info.get("title") or info.get("id") or url,
            uploader=info.get("uploader") or info.get("channel"),
            thumbnail=trusted_thumbnail(thumbnail, self._thumbnail_hosts),
            options=AUDIO_OPTIONS if audio_only else VIDEO_OPTIONS,
            item_count=min(total, settings.max_playlist_items),
            total_items=total,
        )

    def download(
        self, url: str, option: DownloadOption, out_dir: Path, on_progress: ProgressCallback
    ) -> list[Path]:
        if option not in self.options:
            raise ProviderError("This download option isn't available for this link.")
        started = time.monotonic()
        max_total = settings.max_job_mb * 1024 * 1024
        downloaded: dict[str, int] = {}
        # yt-dlp's ignoreerrors swallows every exception except DownloadCancelled, so the reason
        # is kept here and re-raised once yt-dlp has returned.
        aborted: list[ProviderError] = []

        def abort(reason: str) -> None:
            aborted.append(ProviderError(reason))
            raise DownloadCancelled(reason)

        def check_deadline() -> None:
            if time.monotonic() - started > settings.job_timeout_seconds:
                abort("Download took too long and was stopped.")

        def progress_hook(d: dict[str, Any]) -> None:
            if d["status"] != "downloading":
                return
            downloaded[d.get("filename", "")] = d["downloaded_bytes"]
            if sum(downloaded.values()) > max_total:
                abort("Download is larger than the size limit.")
            check_deadline()
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            on_progress(
                Progress(
                    phase="downloading",
                    fraction=min(d["downloaded_bytes"] / total, 1.0) if total else None,
                    speed=d.get("speed"),
                    eta=d.get("eta"),
                )
            )

        def postprocessor_hook(d: dict[str, Any]) -> None:
            if d["status"] == "started":
                on_progress(Progress(phase="processing"))

        def match_filter(info: dict[str, Any], *, incomplete: bool = False) -> None:
            # Called for every playlist entry, also for ones that never start downloading.
            check_deadline()

        log = _ErrorLog()
        options = {
            **self._base_options(),
            **format_options(option),
            "logger": log,
            "paths": {"home": str(out_dir)},
            "outtmpl": "%(playlist_index&{:02d} |)s%(title).150B [%(id)s].%(ext)s",
            "windowsfilenames": True,
            "playlist_items": f"1:{settings.max_playlist_items}",
            # One unavailable entry must not abort the rest of a playlist.
            "ignoreerrors": "only_download",
            "max_filesize": self.max_filesize,
            "match_filter": match_filter,
            "progress_hooks": [progress_hook],
            "postprocessor_hooks": [postprocessor_hook],
        }
        try:
            with self._lease() as cookiefile:
                info = self._extract_flat(url, cookiefile)
                if info.get("_type") in _PLAYLIST_TYPES:
                    self._validate_playlist(info)
                if cookiefile is not None:
                    options["cookiefile"] = str(cookiefile)
                with self._ydl_factory(options) as ydl:
                    ydl.download([url])
        except DownloadCancelled:
            pass
        except DownloadError as e:
            if not aborted:
                raise self._error(e) from e
        if aborted:
            raise aborted[0]

        files = sorted(p for p in out_dir.iterdir() if p.is_file() and not _is_leftover(p, option))
        if not files and log.errors:
            raise self._error(DownloadError(log.errors[-1]))
        if not files:
            raise ProviderError("No file was downloaded. It may be larger than the size limit.")
        return DownloadedFiles(files, skipped=len(log.errors))
