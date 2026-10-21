import re
from collections.abc import Callable
from pathlib import Path
from typing import Any

from yt_dlp import YoutubeDL
from yt_dlp.utils import DownloadError

from app.providers.base import (
    AUDIO_OPTIONS,
    VIDEO_OPTIONS,
    DownloadOption,
    MediaInfo,
    MediaKind,
    Progress,
    ProgressCallback,
    ProviderError,
)
from app.urls import host_matches

YoutubeDLFactory = Callable[[dict[str, Any]], YoutubeDL]

# Leftovers that yt-dlp may keep next to the final file.
_TEMP_SUFFIXES = {".part", ".ytdl", ".temp", ".jpg", ".webp", ".png"}
_ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")


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
    return {"format": "bv*+ba/b", "format_sort": sort, "merge_output_format": "mp4"}


def _has_video(info: dict[str, Any]) -> bool:
    formats = info.get("formats") or [info]
    return any(
        f.get("vcodec") not in (None, "none") or (f.get("vcodec") is None and f.get("height"))
        for f in formats
    )


def _error_message(error: DownloadError) -> str:
    return _ANSI_RE.sub("", str(error)).removeprefix("ERROR: ").strip()


class YtDlpProvider:
    def __init__(
        self,
        platform: str,
        domains: list[str],
        extractors: list[str],
        ydl_factory: YoutubeDLFactory = YoutubeDL,
        max_filesize: int | None = None,
    ):
        self.platform = platform
        self.domains = domains
        # Regexes (full match) of yt-dlp extractor names. Keeps the generic extractor out, so
        # yt-dlp never fetches arbitrary pages.
        self.extractors = extractors
        self._ydl_factory = ydl_factory
        self.max_filesize = max_filesize

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

    def resolve(self, url: str) -> MediaInfo:
        try:
            # Flat: a playlist URL returns quickly instead of extracting every entry.
            with self._ydl_factory({**self._base_options(), "extract_flat": "in_playlist"}) as ydl:
                info = ydl.extract_info(url, download=False)
        except DownloadError as e:
            raise ProviderError(_error_message(e)) from e
        if info.get("_type") in ("playlist", "multi_video"):
            raise ProviderError("Playlists aren't supported yet. Share a single video or track.")

        has_video = _has_video(info)
        return MediaInfo(
            url=info.get("webpage_url") or url,
            platform=self.platform,
            kind=MediaKind.VIDEO if has_video else MediaKind.AUDIO,
            title=info.get("title") or info.get("id") or url,
            uploader=info.get("uploader") or info.get("channel"),
            thumbnail=info.get("thumbnail"),
            duration=info.get("duration"),
            options=VIDEO_OPTIONS if has_video else AUDIO_OPTIONS,
        )

    def download(
        self, url: str, option: DownloadOption, out_dir: Path, on_progress: ProgressCallback
    ) -> list[Path]:
        def progress_hook(d: dict[str, Any]) -> None:
            if d["status"] != "downloading":
                return
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

        options = {
            **self._base_options(),
            **format_options(option),
            "paths": {"home": str(out_dir)},
            "outtmpl": "%(title).150B [%(id)s].%(ext)s",
            "windowsfilenames": True,
            # Even if a playlist URL reaches this point, download one item at most.
            "playlist_items": "1",
            "max_filesize": self.max_filesize,
            "progress_hooks": [progress_hook],
            "postprocessor_hooks": [postprocessor_hook],
        }
        try:
            with self._ydl_factory(options) as ydl:
                ydl.download([url])
        except DownloadError as e:
            raise ProviderError(_error_message(e)) from e

        files = sorted(
            p for p in out_dir.iterdir() if p.is_file() and p.suffix not in _TEMP_SUFFIXES
        )
        if not files:
            raise ProviderError("No file was downloaded. It may be larger than the size limit.")
        return files
