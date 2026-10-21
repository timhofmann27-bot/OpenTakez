from pathlib import Path

import pytest
from yt_dlp.utils import DownloadError

from app.providers import UnsupportedUrlError, default_providers
from app.providers.base import (
    AUDIO_OPTIONS,
    VIDEO_OPTIONS,
    DownloadOption,
    MediaKind,
    ProviderError,
)
from app.providers.ytdlp import YtDlpProvider, format_options

VIDEO_INFO = {
    "id": "abc",
    "title": "A video",
    "uploader": "Someone",
    "webpage_url": "https://www.youtube.com/watch?v=abc",
    "thumbnail": "https://i.ytimg.com/abc.jpg",
    "duration": 12,
    "formats": [{"vcodec": "none", "acodec": "opus"}, {"vcodec": "avc1", "acodec": "none"}],
}
AUDIO_INFO = {
    "id": "123",
    "title": "A track",
    "formats": [{"vcodec": "none", "acodec": "mp3"}, {"vcodec": "none", "acodec": "opus"}],
}


class FakeYoutubeDL:
    """Stands in for yt_dlp.YoutubeDL; records the options it was created with."""

    def __init__(self, info=None, error=None, files=()):
        self.info, self.error, self.files = info, error, files
        self.options: dict = {}

    def __call__(self, options):
        self.options = options
        return self

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def extract_info(self, url, download):
        if self.error:
            raise DownloadError(self.error)
        return self.info

    def download(self, urls):
        if self.error:
            raise DownloadError(self.error)
        for hook in self.options["progress_hooks"]:
            hook({"status": "downloading", "downloaded_bytes": 25, "total_bytes": 100})
        for hook in self.options["postprocessor_hooks"]:
            hook({"status": "started"})
        home = Path(self.options["paths"]["home"])
        for name in self.files:
            (home / name).write_bytes(b"x")


def make_provider(ydl: FakeYoutubeDL) -> YtDlpProvider:
    return YtDlpProvider("youtube", ["youtube.com"], ["youtube(:.*)?"], ydl_factory=ydl)


def test_resolve_video_offers_video_and_audio_options():
    ydl = FakeYoutubeDL(info=VIDEO_INFO)

    info = make_provider(ydl).resolve("https://youtube.com/watch?v=abc")

    assert info.kind == MediaKind.VIDEO
    assert info.options == VIDEO_OPTIONS
    assert (info.title, info.uploader, info.duration) == ("A video", "Someone", 12)
    assert info.url == "https://www.youtube.com/watch?v=abc"


def test_resolve_audio_only_offers_audio_options():
    info = make_provider(FakeYoutubeDL(info=AUDIO_INFO)).resolve("https://youtube.com/x")

    assert info.kind == MediaKind.AUDIO
    assert info.options == AUDIO_OPTIONS


def test_yt_dlp_is_restricted_to_platform_extractors_and_single_items():
    ydl = FakeYoutubeDL(info=VIDEO_INFO)

    make_provider(ydl).resolve("https://youtube.com/watch?v=abc")

    assert ydl.options["allowed_extractors"] == ["youtube(:.*)?"]
    assert ydl.options["noplaylist"] is True


def test_resolve_error_message_is_cleaned_up():
    ydl = FakeYoutubeDL(error="ERROR: \x1b[0;31m[youtube] abc: Video unavailable\x1b[0m")

    with pytest.raises(ProviderError, match=r"^\[youtube\] abc: Video unavailable$"):
        make_provider(ydl).resolve("https://youtube.com/watch?v=abc")


def test_download_reports_progress_and_returns_only_final_files(tmp_path):
    ydl = FakeYoutubeDL(files=["A video [abc].mp4", "A video [abc].jpg", "x.mp4.part"])
    updates = []

    files = make_provider(ydl).download(
        "https://youtube.com/watch?v=abc", DownloadOption.VIDEO_BEST, tmp_path, updates.append
    )

    assert [f.name for f in files] == ["A video [abc].mp4"]
    assert [(u.phase, u.fraction) for u in updates] == [("downloading", 0.25), ("processing", None)]
    assert ydl.options["paths"] == {"home": str(tmp_path)}


def test_download_without_output_is_an_error(tmp_path):
    with pytest.raises(ProviderError, match="No file was downloaded"):
        make_provider(FakeYoutubeDL()).download(
            "https://youtube.com/x", DownloadOption.AUDIO_MP3, tmp_path, lambda p: None
        )


def test_audio_options_extract_audio_and_embed_cover():
    options = format_options(DownloadOption.AUDIO_MP3)

    keys = [pp["key"] for pp in options["postprocessors"]]
    assert keys == [
        "FFmpegThumbnailsConvertor",
        "FFmpegExtractAudio",
        "FFmpegMetadata",
        "EmbedThumbnail",
    ]
    assert options["postprocessors"][1]["preferredcodec"] == "mp3"
    assert options["writethumbnail"] is True


def test_video_options_limit_resolution_and_prefer_mp4():
    options = format_options(DownloadOption.VIDEO_720)

    assert options["format_sort"] == ["res:720", "ext:mp4:m4a"]
    assert options["merge_output_format"] == "mp4"


@pytest.mark.parametrize(
    ("url", "platform"),
    [
        ("https://youtu.be/abc", "youtube"),
        ("https://music.youtube.com/watch?v=abc", "youtube"),
        ("https://soundcloud.com/artist/track", "soundcloud"),
        ("https://on.soundcloud.com/abc", "soundcloud"),
    ],
)
def test_default_providers_route_by_host(url, platform):
    assert default_providers().for_url(url).platform == platform


def test_default_providers_reject_unknown_hosts():
    with pytest.raises(UnsupportedUrlError):
        default_providers().for_url("https://example.com/video")
