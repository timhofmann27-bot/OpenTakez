import re
from collections.abc import Callable

from app.config import settings
from app.cookies import CookieStore
from app.providers.base import Provider
from app.providers.gallerydl import GalleryDlProvider
from app.providers.ytdlp import YtDlpProvider

# CDN hosts the platforms serve thumbnails from (seen in real resolve output).
_TIKTOK_CDN = [
    "tiktokcdn.com",
    "tiktokcdn-us.com",
    "tiktokcdn-eu.com",
    "tiktokv.com",
    "tiktokv.us",
    "byteimg.com",
    "ibyteimg.com",
]
_META_CDN = ["cdninstagram.com", "fbcdn.net"]


class UnsupportedUrlError(Exception):
    pass


class Providers:
    def __init__(self, providers: list[Provider]):
        self._providers = providers

    def for_url(self, url: str) -> Provider:
        for provider in self._providers:
            if provider.supports(url):
                return provider
        raise UnsupportedUrlError(url)

    @property
    def platforms(self) -> list[str]:
        return list(dict.fromkeys(p.platform for p in self._providers))


def default_providers(
    cookies: CookieStore | None = None,
    gallery_dl: Callable[..., Provider] = GalleryDlProvider,
) -> Providers:
    """Ordered: the first provider that supports a URL wins."""
    max_filesize = settings.max_file_mb * 1024 * 1024
    gallery = {
        "max_items": settings.max_playlist_items,
        "max_filesize": max_filesize,
        "timeout": settings.gallery_dl_timeout,
        "max_job_bytes": settings.max_job_mb * 1024 * 1024,
        "binary": settings.gallery_dl_bin,
    }
    return Providers(
        [
            gallery_dl(
                "tiktok",
                ["tiktok.com"],
                re.compile(r"^/@[^/]+/photo/\d+"),
                cookies,
                thumbnail_hosts=_TIKTOK_CDN,
                **gallery,
            ),
            YtDlpProvider(
                "tiktok",
                ["tiktok.com"],
                # vm.tiktok resolves app share links (vm./vt.tiktok.com/<code>, /t/<code>) and
                # redirects to the full video URL, which the TikTok extractor then handles.
                ["TikTok", "vm.tiktok"],
                max_filesize=max_filesize,
                cookies=cookies,
                leaf_extractors={"TikTok", "TikTokVM"},
                thumbnail_hosts=_TIKTOK_CDN,
            ),
            # Image-only posts need a login; without cookies yt-dlp's error points to the upload.
            gallery_dl(
                "instagram",
                ["instagram.com"],
                re.compile(r"^/(?:[^/]+/)?p/[^/]+/?$"),
                cookies,
                requires_cookies=True,
                thumbnail_hosts=_META_CDN,
                **gallery,
            ),
            YtDlpProvider(
                "instagram",
                ["instagram.com"],
                ["Instagram"],
                max_filesize=max_filesize,
                cookies=cookies,
                leaf_extractors={"Instagram"},
                thumbnail_hosts=_META_CDN,
            ),
            YtDlpProvider(
                "facebook",
                ["facebook.com", "fb.watch", "fb.com"],
                ["facebook", "facebook:reel"],
                max_filesize=max_filesize,
                cookies=cookies,
                leaf_extractors={"Facebook", "FacebookReel"},
                thumbnail_hosts=_META_CDN,
            ),
            YtDlpProvider(
                "youtube",
                domains=["youtube.com", "youtu.be", "youtube-nocookie.com"],
                extractors=["youtube(:.*)?"],
                max_filesize=max_filesize,
                leaf_extractors={"Youtube"},
                playlist_extractors={"YoutubeTab"},
                thumbnail_hosts=["ytimg.com", "ggpht.com"],
            ),
            YtDlpProvider(
                "soundcloud",
                domains=["soundcloud.com"],
                extractors=["soundcloud(:.*)?"],
                max_filesize=max_filesize,
                leaf_extractors={"Soundcloud"},
                # Profiles list plain tracks too; only sets/albums count as playlists.
                playlist_extractors={"SoundcloudSet"},
                thumbnail_hosts=["sndcdn.com"],
            ),
        ]
    )
