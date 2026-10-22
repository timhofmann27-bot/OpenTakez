from contextlib import contextmanager
from pathlib import Path

import pytest
from yt_dlp.utils import DownloadCancelled, DownloadError

from app.config import settings
from app.providers.base import (
    AUDIO_OPTIONS,
    IMAGE_OPTIONS,
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

    def __init__(self, info=VIDEO_INFO, error=None, files=()):
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


def make_provider(ydl: FakeYoutubeDL, **kwargs) -> YtDlpProvider:
    return YtDlpProvider("youtube", ["youtube.com"], ["youtube(:.*)?"], ydl_factory=ydl, **kwargs)


class FakeCookies:
    """CookieStore stand-in: hands out a fixed path and records the lease lifetime."""

    def __init__(self, path: Path | None):
        self.path, self.active = path, False

    @contextmanager
    def lease(self, platform):
        self.active = True
        try:
            yield self.path
        finally:
            self.active = False


def flat_playlist(count, ie_key="Youtube", **extra):
    entries = [{"_type": "url", "id": str(i), "ie_key": ie_key} for i in range(count)]
    return {"_type": "playlist", "id": "PL1", "title": "Mix", "entries": entries, **extra}


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
    ydl = FakeYoutubeDL(files=["A video [abc].mp4", "x.mp4.part", "y.ytdl"])
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


def test_playlist_resolve_caps_item_count_and_keeps_total():
    info = flat_playlist(120, uploader="Me", thumbnail="https://t/p.jpg")
    ydl = FakeYoutubeDL(info=info)

    result = make_provider(ydl, leaf_extractors={"Youtube"}).resolve("https://youtube.com/playlist")

    assert (result.item_count, result.total_items) == (settings.max_playlist_items, 120)
    assert (result.title, result.uploader, result.thumbnail) == ("Mix", "Me", "https://t/p.jpg")
    assert result.kind == MediaKind.VIDEO
    assert result.options == VIDEO_OPTIONS


def test_playlist_count_wins_over_the_entries_seen():
    ydl = FakeYoutubeDL(info=flat_playlist(3, playlist_count=7))

    result = make_provider(ydl).resolve("https://youtube.com/playlist")

    assert (result.item_count, result.total_items) == (7, 7)


def test_playlist_thumbnail_falls_back_to_first_entry():
    info = flat_playlist(2)
    info["entries"][1]["thumbnails"] = [{"url": "https://t/a"}, {"url": "https://t/b"}]

    result = make_provider(FakeYoutubeDL(info=info)).resolve("https://youtube.com/playlist")

    assert result.thumbnail == "https://t/b"


def test_soundcloud_set_is_audio():
    ydl = FakeYoutubeDL(info=flat_playlist(3, ie_key="Soundcloud"))

    result = make_provider(ydl, leaf_extractors={"Soundcloud"}).resolve("https://youtube.com/x")

    assert result.kind == MediaKind.AUDIO
    assert result.options == AUDIO_OPTIONS


def test_playlist_of_playlists_is_a_channel_and_rejected():
    info = flat_playlist(2, ie_key="YoutubeTab")

    with pytest.raises(ProviderError, match="Channels and profiles"):
        make_provider(FakeYoutubeDL(info=info), leaf_extractors={"Youtube"}).resolve("https://x")


def test_empty_playlist_is_an_error():
    with pytest.raises(ProviderError, match="empty"):
        make_provider(FakeYoutubeDL(info=flat_playlist(0))).resolve("https://youtube.com/x")


def test_download_requests_capped_playlist_items_with_numbered_names(tmp_path):
    ydl = FakeYoutubeDL(files=["01 a.mp4", "02 b.mp4"])

    files = make_provider(ydl).download(
        "https://youtube.com/playlist", DownloadOption.VIDEO_BEST, tmp_path, lambda p: None
    )

    assert ydl.options["playlist_items"] == f"1:{settings.max_playlist_items}"
    assert ydl.options["noplaylist"] is True
    assert ydl.options["ignoreerrors"] == "only_download"
    assert ydl.options["outtmpl"].startswith("%(playlist_index&{:02d} |)s")
    assert [f.name for f in files] == ["01 a.mp4", "02 b.mp4"]


def test_cookiefile_is_the_lease_and_lease_covers_the_run(tmp_path):
    cookies = FakeCookies(tmp_path / "leased.txt")
    seen = []

    class SpyYdl(FakeYoutubeDL):
        def extract_info(self, url, download):
            seen.append(cookies.active)
            return super().extract_info(url, download)

        def download(self, urls):
            seen.append(cookies.active)
            super().download(urls)

    ydl = SpyYdl(info=VIDEO_INFO, files=["a.mp4"])
    provider = make_provider(ydl, cookies=cookies)

    provider.resolve("https://youtube.com/x")
    assert ydl.options["cookiefile"] == str(tmp_path / "leased.txt")
    provider.download("https://youtube.com/x", DownloadOption.VIDEO_BEST, tmp_path, lambda p: None)

    assert ydl.options["cookiefile"] == str(tmp_path / "leased.txt")
    assert seen == [True, True, True]  # resolve, the playlist check, the download
    assert cookies.active is False


def test_no_cookiefile_without_cookies_or_lease():
    for cookies in (None, FakeCookies(None)):
        ydl = FakeYoutubeDL(info=VIDEO_INFO)
        make_provider(ydl, cookies=cookies).resolve("https://youtube.com/x")
        assert "cookiefile" not in ydl.options


@pytest.mark.parametrize(
    ("option", "expected"),
    [
        (DownloadOption.VIDEO_BEST, ["a.jpg", "b.mp4", "c.webp"]),
        (DownloadOption.AUDIO_MP3, ["b.mp4"]),
    ],
)
def test_leftover_filtering_depends_on_the_option(tmp_path, option, expected):
    ydl = FakeYoutubeDL(files=["a.jpg", "b.mp4", "c.webp", "d.part", "e.ytdl", "f.temp"])

    files = make_provider(ydl).download("https://youtube.com/x", option, tmp_path, lambda p: None)

    assert [f.name for f in files] == expected


@pytest.mark.parametrize(
    "message",
    [
        "[Instagram] abc: Instagram API is not granting access. Use --cookies for auth",
        "[Instagram] abc: You need to log in to access this content",
        "[Instagram] abc: Instagram sent an empty media response. Check if this post is accessible",
        "[Instagram] abc: There is no video in this post",
    ],
)
def test_login_type_errors_map_to_login_required(message):
    provider = YtDlpProvider(
        "instagram", ["instagram.com"], ["Instagram"], FakeYoutubeDL(error=message)
    )

    with pytest.raises(ProviderError) as caught:
        provider.resolve("https://www.instagram.com/p/abc/")

    assert caught.value.code == "login_required"
    assert message in str(caught.value)


def test_other_errors_and_other_platforms_have_no_code():
    other = FakeYoutubeDL(error="[Instagram] abc: Video unavailable")
    instagram = YtDlpProvider("instagram", ["instagram.com"], ["Instagram"], other)
    youtube = make_provider(FakeYoutubeDL(error="Please log in"))

    for provider in (instagram, youtube):
        with pytest.raises(ProviderError) as caught:
            provider.resolve("https://youtube.com/x")
        assert caught.value.code is None


def test_swallowed_download_error_is_reported_instead_of_a_generic_one(tmp_path):
    class Swallowing(FakeYoutubeDL):
        def download(self, urls):
            self.options["logger"].error("ERROR: \x1b[0;31mPrivate video\x1b[0m")

    with pytest.raises(ProviderError, match="^Private video$"):
        make_provider(Swallowing()).download(
            "https://youtube.com/x", DownloadOption.VIDEO_BEST, tmp_path, lambda p: None
        )


class WrappedYoutubeDL(FakeYoutubeDL):
    """Mimics yt-dlp's `_handle_extraction_exceptions`: with ignoreerrors, every exception from
    inside the run is logged and swallowed except DownloadCancelled."""

    def run(self) -> None: ...

    def download(self, urls):
        try:
            self.run()
        except DownloadCancelled:
            raise
        except Exception as e:
            if not self.options.get("ignoreerrors"):
                raise
            self.options["logger"].error(f"ERROR: {e}")


def download_with(ydl, tmp_path):
    return make_provider(ydl).download(
        "https://youtube.com/x", DownloadOption.VIDEO_BEST, tmp_path, lambda p: None
    )


def test_download_aborts_when_total_exceeds_job_cap(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "max_job_mb", 1)

    class Big(WrappedYoutubeDL):
        def run(self):
            # A finished entry exists, so a swallowed abort would look like a success.
            (Path(self.options["paths"]["home"]) / "a.mp4").write_bytes(b"x")
            hook = self.options["progress_hooks"][0]
            hook({"status": "downloading", "filename": "a", "downloaded_bytes": 700_000})
            hook({"status": "downloading", "filename": "b", "downloaded_bytes": 700_000})
            raise AssertionError("yt-dlp kept going after the cap")

    with pytest.raises(ProviderError, match="size limit"):
        download_with(Big(), tmp_path)


def test_download_aborts_after_deadline(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "job_timeout_seconds", -1)

    class Slow(WrappedYoutubeDL):
        def run(self):
            (Path(self.options["paths"]["home"]) / "a.mp4").write_bytes(b"x")
            self.options["progress_hooks"][0](
                {"status": "downloading", "filename": "a", "downloaded_bytes": 1}
            )
            raise AssertionError("yt-dlp kept going after the deadline")

    with pytest.raises(ProviderError, match="too long"):
        download_with(Slow(), tmp_path)


def test_deadline_also_stops_entries_that_never_download(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "job_timeout_seconds", -1)

    class Entries(WrappedYoutubeDL):
        def run(self):
            # yt-dlp asks the match filter for every entry before downloading it.
            self.options["match_filter"]({"id": "1"}, incomplete=False)
            raise AssertionError("yt-dlp kept going after the deadline")

    with pytest.raises(ProviderError, match="too long"):
        download_with(Entries(), tmp_path)


def test_match_filter_lets_entries_through_before_the_deadline(tmp_path):
    class Entries(WrappedYoutubeDL):
        def run(self):
            assert self.options["match_filter"]({"id": "1"}, incomplete=True) is None
            (Path(self.options["paths"]["home"]) / "a.mp4").write_bytes(b"x")

    [file] = download_with(Entries(), tmp_path)

    assert file.name == "a.mp4"


def test_abort_is_reported_even_if_yt_dlp_raises_afterwards(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "max_job_mb", 1)

    class Noisy(WrappedYoutubeDL):
        def download(self, urls):
            try:
                self.options["progress_hooks"][0](
                    {"status": "downloading", "filename": "a", "downloaded_bytes": 2_000_000}
                )
            except DownloadCancelled:
                raise DownloadError("unrelated follow-up") from None

    with pytest.raises(ProviderError, match="size limit"):
        download_with(Noisy(), tmp_path)


def test_image_option_is_not_a_yt_dlp_option(tmp_path):
    assert DownloadOption.IMAGE_ORIGINAL not in make_provider(FakeYoutubeDL()).options
    assert make_provider(FakeYoutubeDL()).options == VIDEO_OPTIONS
    with pytest.raises(ValueError, match="image-original"):
        format_options(DownloadOption.IMAGE_ORIGINAL)
    with pytest.raises(ProviderError):
        make_provider(FakeYoutubeDL()).download(
            "https://youtube.com/x", DownloadOption.IMAGE_ORIGINAL, tmp_path, lambda p: None
        )
    assert IMAGE_OPTIONS == [DownloadOption.IMAGE_ORIGINAL]


@pytest.mark.parametrize(
    "message",
    [
        "[Instagram] abc: You have exceeded the rate-limit for accessing posts anonymously",
        "[Facebook] abc: This content isn't available right now",
        "[Instagram] abc: Video unavailable",
    ],
)
def test_ambiguous_errors_are_not_login_required(message):
    provider = YtDlpProvider(
        "instagram", ["instagram.com"], ["Instagram"], FakeYoutubeDL(error=message)
    )

    with pytest.raises(ProviderError) as caught:
        provider.resolve("https://www.instagram.com/p/abc/")

    assert caught.value.code is None
    assert "cookies.txt" not in str(caught.value)


def test_rate_limit_has_its_own_message():
    message = "[Instagram] abc: You have exceeded the rate-limit for accessing posts anonymously"
    provider = YtDlpProvider(
        "instagram", ["instagram.com"], ["Instagram"], FakeYoutubeDL(error=message)
    )

    with pytest.raises(ProviderError, match="Try again later"):
        provider.resolve("https://www.instagram.com/p/abc/")


# --- playlist / channel guard ---


def test_resolve_asks_for_a_capped_flat_extraction():
    ydl = FakeYoutubeDL(info=flat_playlist(2))

    make_provider(ydl).resolve("https://youtube.com/playlist?list=PL1")

    assert ydl.options["extract_flat"] == "in_playlist"
    assert ydl.options["playlistend"] == settings.max_playlist_items


def test_download_rejects_channels_like_resolve(tmp_path):
    ydl = FakeYoutubeDL(info=flat_playlist(2, ie_key="YoutubeTab"), files=["a.mp4"])

    with pytest.raises(ProviderError, match="Channels and profiles"):
        make_provider(ydl, leaf_extractors={"Youtube"}).download(
            "https://youtube.com/@x/videos", DownloadOption.VIDEO_BEST, tmp_path, lambda p: None
        )

    assert not list(tmp_path.iterdir())  # nothing was downloaded


def test_download_of_a_valid_playlist_goes_ahead(tmp_path):
    ydl = FakeYoutubeDL(info=flat_playlist(2), files=["01 a.mp4"])

    [file] = make_provider(ydl, leaf_extractors={"Youtube"}).download(
        "https://youtube.com/playlist?list=PL1", DownloadOption.VIDEO_BEST, tmp_path, lambda p: None
    )

    assert file.name == "01 a.mp4"
    assert "extract_flat" not in ydl.options  # the real run isn't flat


def test_entries_without_a_key_are_not_leaves():
    info = flat_playlist(2)
    for entry in info["entries"]:
        del entry["ie_key"]

    with pytest.raises(ProviderError, match="Channels and profiles"):
        make_provider(FakeYoutubeDL(info=info), leaf_extractors={"Youtube"}).resolve("https://x")


def test_entries_with_an_extractor_key_are_leaves():
    # Carousels from yt-dlp itself carry extractor_key instead of ie_key.
    info = {"_type": "playlist", "entries": [{"id": "1", "extractor_key": "Instagram"}]}

    result = make_provider(FakeYoutubeDL(info=info), leaf_extractors={"Instagram"}).resolve("x")

    assert result.item_count == 1


def channel_info(webpage_url, extractor_key="YoutubeTab"):
    # Real shape: a channel's videos tab yields flat entries that are plain Youtube videos.
    return {**flat_playlist(2), "extractor_key": extractor_key, "webpage_url": webpage_url}


@pytest.mark.parametrize(
    "webpage_url",
    ["https://www.youtube.com/@jawed/videos", "https://www.youtube.com/channel/UCabc/videos"],
)
def test_youtube_channel_tabs_are_rejected_even_with_video_entries(webpage_url):
    provider = make_provider(
        FakeYoutubeDL(info=channel_info(webpage_url)),
        leaf_extractors={"Youtube"},
        playlist_extractors={"YoutubeTab"},
    )

    with pytest.raises(ProviderError, match="Channels and profiles"):
        provider.resolve(webpage_url)


def test_youtube_playlist_urls_are_accepted():
    url = "https://www.youtube.com/playlist?list=PL1"
    provider = make_provider(
        FakeYoutubeDL(info=channel_info(url)),
        leaf_extractors={"Youtube"},
        playlist_extractors={"YoutubeTab"},
    )

    assert provider.resolve(url).item_count == 2


def test_playlists_from_other_extractors_are_rejected():
    # A SoundCloud profile also lists plain tracks, but it isn't a set.
    info = {**flat_playlist(2, ie_key="Soundcloud"), "extractor_key": "SoundcloudUser"}
    provider = make_provider(
        FakeYoutubeDL(info=info),
        leaf_extractors={"Soundcloud"},
        playlist_extractors={"SoundcloudSet"},
    )

    with pytest.raises(ProviderError, match="Channels and profiles"):
        provider.resolve("https://soundcloud.com/artist")


def test_failed_entries_are_counted_when_others_succeed(tmp_path):
    class Partial(WrappedYoutubeDL):
        def run(self):
            (Path(self.options["paths"]["home"]) / "01 a.mp4").write_bytes(b"x")
            self.options["logger"].error("ERROR: [youtube] 2: Private video")
            self.options["logger"].error("ERROR: [youtube] 3: Video unavailable")

    files = download_with(Partial(), tmp_path)

    assert [f.name for f in files] == ["01 a.mp4"]
    assert files.skipped == 2


def test_clean_download_skips_nothing(tmp_path):
    assert download_with(FakeYoutubeDL(files=["a.mp4"]), tmp_path).skipped == 0


@pytest.mark.parametrize(
    ("thumbnail", "expected"),
    [
        ("https://i.ytimg.com/vi/a/hq.jpg", "https://i.ytimg.com/vi/a/hq.jpg"),
        ("https://evil.example/x.jpg", None),
        ("https://i.ytimg.com.evil.example/x.jpg", None),
        ("http://i.ytimg.com/x.jpg", None),
        ("javascript:alert(1)", None),
        (None, None),
    ],
)
def test_thumbnails_only_from_the_platform_cdn(thumbnail, expected):
    info = {**VIDEO_INFO, "thumbnail": thumbnail}
    provider = make_provider(FakeYoutubeDL(info=info), thumbnail_hosts=["ytimg.com"])

    assert provider.resolve("https://youtube.com/x").thumbnail == expected


def test_playlist_thumbnails_are_filtered_too():
    info = flat_playlist(2, thumbnail="https://evil.example/p.jpg")
    provider = make_provider(FakeYoutubeDL(info=info), thumbnail_hosts=["ytimg.com"])

    assert provider.resolve("https://youtube.com/playlist").thumbnail is None


def test_yt_dlp_cache_lives_under_the_temp_dir_for_read_only_containers():
    import tempfile

    ydl = FakeYoutubeDL(info=VIDEO_INFO)

    make_provider(ydl).resolve("https://youtube.com/watch?v=abc")

    assert Path(ydl.options["cachedir"]).is_relative_to(tempfile.gettempdir())
