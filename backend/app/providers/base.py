from collections.abc import Callable
from enum import StrEnum
from pathlib import Path
from typing import Literal, Protocol

from pydantic import BaseModel


class MediaKind(StrEnum):
    VIDEO = "video"
    AUDIO = "audio"
    # Photos, including carousels that mix photos and videos.
    IMAGE = "image"


class DownloadOption(StrEnum):
    VIDEO_BEST = "video-best"
    VIDEO_1080 = "video-1080"
    VIDEO_720 = "video-720"
    AUDIO_MP3 = "audio-mp3"
    AUDIO_M4A = "audio-m4a"
    IMAGE_ORIGINAL = "image-original"


AUDIO_OPTIONS = [DownloadOption.AUDIO_MP3, DownloadOption.AUDIO_M4A]
IMAGE_OPTIONS = [DownloadOption.IMAGE_ORIGINAL]
VIDEO_OPTIONS = [
    DownloadOption.VIDEO_BEST,
    DownloadOption.VIDEO_1080,
    DownloadOption.VIDEO_720,
    *AUDIO_OPTIONS,
]


class MediaInfo(BaseModel):
    url: str
    platform: str
    kind: MediaKind
    title: str
    uploader: str | None = None
    thumbnail: str | None = None
    duration: float | None = None
    options: list[DownloadOption]
    # Items that will be downloaded (None for a single item) and how many the source has.
    item_count: int | None = None
    total_items: int | None = None


class Progress(BaseModel):
    phase: Literal["downloading", "processing"]
    fraction: float | None = None
    speed: float | None = None  # bytes per second
    eta: float | None = None  # seconds


ProgressCallback = Callable[[Progress], None]


class ProviderError(Exception):
    """A platform could not resolve or download a URL. The message is shown to the user.

    `code` lets the app react to known cases, e.g. "login_required" → point to cookie upload.
    """

    def __init__(self, message: str, code: str | None = None):
        super().__init__(message)
        self.code = code


class Provider(Protocol):
    platform: str

    def supports(self, url: str) -> bool: ...

    def resolve(self, url: str) -> MediaInfo: ...

    def download(
        self, url: str, option: DownloadOption, out_dir: Path, on_progress: ProgressCallback
    ) -> list[Path]:
        """Download into `out_dir` (empty, owned by the caller) and return the final files."""
        ...
