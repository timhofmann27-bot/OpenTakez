import re
from pathlib import Path

import pytest

from app.providers import UnsupportedUrlError, default_providers
from app.providers.ytdlp import YtDlpProvider
from app.urls import host_matches


class FakeGallery:
    """Mirrors GalleryDlProvider.supports(): host, path pattern, cookies if required."""

    def __init__(self, platform, domains, path_pattern, cookies, *, requires_cookies=False, **_):
        self.platform, self.domains, self.pattern = platform, domains, path_pattern
        self.cookies, self.requires_cookies = cookies, requires_cookies

    def supports(self, url: str) -> bool:
        from urllib.parse import urlsplit

        if not host_matches(url, self.domains) or not self.pattern.match(urlsplit(url).path):
            return False
        return not self.requires_cookies or bool(self.cookies and self.cookies.has(self.platform))

    def resolve(self, url): ...

    def download(self, url, option, out_dir: Path, on_progress): ...


class FakeStore:
    def __init__(self, *platforms):
        self.platforms = set(platforms)

    def has(self, platform):
        return platform in self.platforms


def route(url, store=None):
    provider = default_providers(store, gallery_dl=FakeGallery).for_url(url)
    return provider.platform, "gallery-dl" if isinstance(provider, FakeGallery) else "yt-dlp"


@pytest.mark.parametrize(
    ("url", "expected"),
    [
        ("https://www.tiktok.com/@u/photo/123", ("tiktok", "gallery-dl")),
        ("https://www.tiktok.com/@u/video/123", ("tiktok", "yt-dlp")),
        ("https://vm.tiktok.com/abc/", ("tiktok", "yt-dlp")),
        ("https://www.instagram.com/p/abc/", ("instagram", "yt-dlp")),
        ("https://www.instagram.com/reel/abc/", ("instagram", "yt-dlp")),
        ("https://fb.watch/abc/", ("facebook", "yt-dlp")),
        ("https://www.facebook.com/reel/123", ("facebook", "yt-dlp")),
        ("https://fb.com/watch/?v=1", ("facebook", "yt-dlp")),
        ("https://youtu.be/abc", ("youtube", "yt-dlp")),
        ("https://music.youtube.com/watch?v=abc", ("youtube", "yt-dlp")),
        ("https://soundcloud.com/artist/track", ("soundcloud", "yt-dlp")),
        ("https://on.soundcloud.com/abc", ("soundcloud", "yt-dlp")),
    ],
)
def test_routing_without_cookies(url, expected):
    assert route(url, FakeStore()) == expected


def test_instagram_posts_use_gallery_dl_only_with_cookies():
    url = "https://www.instagram.com/p/abc/"

    assert route(url, FakeStore("instagram")) == ("instagram", "gallery-dl")
    assert route(url, FakeStore("tiktok")) == ("instagram", "yt-dlp")
    assert route(url, None) == ("instagram", "yt-dlp")


def test_instagram_reels_stay_on_yt_dlp_with_cookies():
    url = "https://www.instagram.com/reel/abc/"

    assert route(url, FakeStore("instagram")) == ("instagram", "yt-dlp")


@pytest.mark.parametrize(
    "url",
    [
        "https://example.com/video",
        "https://evil.com/?u=https://www.tiktok.com/@u/photo/1",
        "https://tiktok.com.evil.com/@u/photo/1",
        "http://127.0.0.1/p/abc/",
    ],
)
def test_unknown_hosts_are_unsupported(url):
    with pytest.raises(UnsupportedUrlError):
        default_providers(FakeStore("instagram"), gallery_dl=FakeGallery).for_url(url)


def test_instagram_stories_are_rejected_by_yt_dlp_not_routed_elsewhere():
    # Stories route to yt-dlp's Instagram provider, which only allows the "Instagram" extractor.
    provider = default_providers(FakeStore("instagram"), gallery_dl=FakeGallery).for_url(
        "https://www.instagram.com/stories/user/123/"
    )

    assert isinstance(provider, YtDlpProvider)
    assert provider.extractors == ["Instagram"]


def test_every_provider_checks_the_domain_allowlist():
    providers = default_providers(FakeStore("instagram"), gallery_dl=FakeGallery)

    for provider in providers._providers:
        assert not provider.supports("https://example.com/p/abc/")
        assert re.match(r"^[a-z]+$", provider.platform)


def test_platforms_are_listed_once_in_order():
    platforms = default_providers(FakeStore(), gallery_dl=FakeGallery).platforms

    assert platforms == ["tiktok", "instagram", "facebook", "youtube", "soundcloud"]


@pytest.mark.parametrize(
    "url",
    [
        "https://vm.tiktok.com/ZTR45GpSF/",
        "https://vt.tiktok.com/ZSe4FqkKd",
        "https://www.tiktok.com/t/ZTRC5xgJp",
    ],
)
def test_tiktok_share_links_are_resolved_by_the_short_link_extractor(url):
    provider = default_providers(FakeStore(), gallery_dl=FakeGallery).for_url(url)

    assert isinstance(provider, YtDlpProvider) and provider.platform == "tiktok"
    # IE_NAME of yt-dlp's TikTokVMIE; it redirects to the full video URL, so both are needed.
    assert "vm.tiktok" in provider.extractors and "TikTok" in provider.extractors
    assert {"TikTok", "TikTokVM"} <= provider._leaf_extractors


def test_every_yt_dlp_provider_has_leaf_extractors_and_thumbnail_hosts():
    for provider in default_providers(FakeStore(), gallery_dl=FakeGallery)._providers:
        if isinstance(provider, YtDlpProvider):
            assert provider._leaf_extractors, provider.platform
            assert provider._thumbnail_hosts, provider.platform
    assert {"Facebook", "FacebookReel"} <= default_providers(None, gallery_dl=FakeGallery).for_url(
        "https://fb.watch/x"
    )._leaf_extractors


@pytest.mark.parametrize(
    "path",
    ["/p/abc/", "/p/abc", "/someuser/p/abc/", "/some.user_1/p/CuE2-_x/"],
)
def test_instagram_post_paths_include_the_user_prefixed_form(path):
    url = f"https://www.instagram.com{path}"

    assert route(url, FakeStore("instagram")) == ("instagram", "gallery-dl")


@pytest.mark.parametrize("path", ["/reel/abc/", "/someuser/", "/someuser/reel/abc/", "/p/"])
def test_instagram_other_paths_stay_on_yt_dlp(path):
    assert route(f"https://www.instagram.com{path}", FakeStore("instagram")) == (
        "instagram",
        "yt-dlp",
    )
