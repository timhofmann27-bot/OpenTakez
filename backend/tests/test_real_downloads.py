"""Downloads from the real platforms. Run with: uv run pytest -m slow"""

import json
import subprocess

import pytest

from app.providers import default_providers
from app.providers.base import DownloadOption, MediaKind, ProviderError

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


def test_tiktok_video(tmp_path):
    url = "https://www.tiktok.com/@pokemonlife22/video/7059698374567611694"
    provider = default_providers().for_url(url)

    info = provider.resolve(url)
    [file] = provider.download(url, DownloadOption.VIDEO_BEST, tmp_path, lambda p: None)

    assert info.kind == MediaKind.VIDEO
    assert {kind for kind, _ in streams(file)} >= {"video"}


def test_instagram_reel(tmp_path):
    url = "https://www.instagram.com/reel/Chunk8-jurw/"
    provider = default_providers().for_url(url)

    info = provider.resolve(url)
    [file] = provider.download(url, DownloadOption.VIDEO_BEST, tmp_path, lambda p: None)

    assert info.kind == MediaKind.VIDEO
    assert file.suffix == ".mp4"


def test_instagram_carousel_has_three_items(tmp_path):
    url = "https://www.instagram.com/p/BQ0eAlwhDrw/"
    provider = default_providers().for_url(url)

    info = provider.resolve(url)
    files = provider.download(url, DownloadOption.VIDEO_BEST, tmp_path, lambda p: None)

    assert (info.item_count, info.total_items) == (3, 3)
    assert len(files) == 3
    assert [f.name[:2] for f in files] == ["01", "02", "03"]


@pytest.mark.parametrize(
    "url",
    ["https://www.youtube.com/@jawed/videos", "https://www.youtube.com/@jawed"],
)
def test_youtube_channels_are_rejected(url, tmp_path):
    provider = default_providers().for_url(url)

    with pytest.raises(ProviderError, match="Channels and profiles"):
        provider.resolve(url)
    with pytest.raises(ProviderError, match="Channels and profiles"):
        provider.download(url, DownloadOption.VIDEO_BEST, tmp_path, lambda p: None)
    assert not list(tmp_path.iterdir())


def test_youtube_playlist_resolves_capped():
    url = "https://www.youtube.com/playlist?list=UU-lHJZR3Gqxm24_Vd_AJ5Yw"

    info = default_providers().for_url(url).resolve(url)

    assert info.kind == MediaKind.VIDEO and info.item_count
    assert info.thumbnail is None or "ytimg.com" in info.thumbnail


def test_soundcloud_profile_is_rejected_but_set_resolves():
    provider = default_providers().for_url("https://soundcloud.com/garyvee")

    with pytest.raises(ProviderError, match="Channels and profiles"):
        provider.resolve("https://soundcloud.com/garyvee")
    set_url = "https://soundcloud.com/the-concept-band/sets/the-royal-concept-ep"
    assert provider.resolve(set_url).kind == MediaKind.AUDIO
