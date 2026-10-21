from app.config import settings
from app.providers.base import Provider
from app.providers.ytdlp import YtDlpProvider


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
        return [p.platform for p in self._providers]


def default_providers() -> Providers:
    max_filesize = settings.max_file_mb * 1024 * 1024
    return Providers(
        [
            YtDlpProvider(
                "youtube",
                domains=["youtube.com", "youtu.be", "youtube-nocookie.com"],
                extractors=["youtube(:.*)?"],
                max_filesize=max_filesize,
            ),
            YtDlpProvider(
                "soundcloud",
                domains=["soundcloud.com"],
                extractors=["soundcloud(:.*)?"],
                max_filesize=max_filesize,
            ),
        ]
    )
