"""Images via gallery-dl, run as a separate program (it is GPL-2.0-only; never import it).

CONTRACT STUB (M3 step 0). Implemented by the cookies/gallery-dl track.
"""

import re
from pathlib import Path

from app.cookies import CookieStore
from app.providers.base import DownloadOption, MediaInfo, ProgressCallback


class GalleryDlProvider:
    def __init__(
        self,
        platform: str,
        domains: list[str],
        path_pattern: re.Pattern[str],
        cookies: CookieStore | None,
        *,
        requires_cookies: bool = False,
        max_items: int = 50,
        max_filesize: int | None = None,
        timeout: int = 300,
        binary: str = "gallery-dl",
    ):
        raise NotImplementedError

    platform: str

    def supports(self, url: str) -> bool:
        """Allowed host AND path_pattern matches AND (cookies present if requires_cookies)."""
        raise NotImplementedError

    def resolve(self, url: str) -> MediaInfo:
        raise NotImplementedError

    def download(
        self, url: str, option: DownloadOption, out_dir: Path, on_progress: ProgressCallback
    ) -> list[Path]:
        raise NotImplementedError
