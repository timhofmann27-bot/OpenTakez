"""Downloads from the real platforms. Run with: uv run pytest -m slow"""

import json
import subprocess

import pytest

from app.providers import default_providers
from app.providers.base import DownloadOption, MediaKind

pytestmark = pytest.mark.slow

# "Me at the zoo", the first YouTube video (19 s); unlikely to disappear.
YOUTUBE_URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw"


def streams(path) -> set[tuple[str, str]]:
    """(codec_type, codec_name) of every stream in a media file."""
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name", "-of", "json",
         str(path)],
        capture_output=True, text=True, check=True,
    ).stdout  # fmt: skip
    return {(st["codec_type"], st["codec_name"]) for st in json.loads(out)["streams"]}


def test_youtube_resolve():
    info = default_providers().for_url(YOUTUBE_URL).resolve(YOUTUBE_URL)

    assert info.kind == MediaKind.VIDEO
    assert info.title == "Me at the zoo"


def test_youtube_video_720(tmp_path):
    provider = default_providers().for_url(YOUTUBE_URL)
    progress = []

    [file] = provider.download(YOUTUBE_URL, DownloadOption.VIDEO_720, tmp_path, progress.append)

    assert file.suffix == ".mp4"
    assert {kind for kind, _ in streams(file)} == {"video", "audio"}
    assert any(p.fraction for p in progress)


def test_youtube_audio_mp3_with_cover(tmp_path):
    provider = default_providers().for_url(YOUTUBE_URL)

    [file] = provider.download(YOUTUBE_URL, DownloadOption.AUDIO_MP3, tmp_path, lambda p: None)

    assert file.suffix == ".mp3"
    assert streams(file) == {("audio", "mp3"), ("video", "mjpeg")}  # mjpeg = embedded cover


def test_soundcloud_track_is_audio(tmp_path):
    # Used by yt-dlp's own SoundCloud extractor tests.
    url = "https://soundcloud.com/garyvee/sideways-prod-mad-real"
    provider = default_providers().for_url(url)

    info = provider.resolve(url)
    [file] = provider.download(url, DownloadOption.AUDIO_M4A, tmp_path, lambda p: None)

    assert info.kind == MediaKind.AUDIO
    assert file.suffix == ".m4a"
