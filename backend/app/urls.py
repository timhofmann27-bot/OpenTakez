import re
from collections.abc import Iterable
from urllib.parse import urlsplit

_URL_RE = re.compile(r"https?://[^\s<>\"']+")


def extract_url(text: str) -> str | None:
    """Return the first http(s) URL in `text`.

    Apps often share "Look at this! https://..." instead of a bare link.
    """
    match = _URL_RE.search(text)
    return match.group(0).rstrip(".,;:!?)") if match else None


def host_matches(url: str, domains: Iterable[str]) -> bool:
    """True if `url` is http(s) on one of `domains` or their subdomains.

    This is the SSRF guard: only URLs on known platform hosts reach yt-dlp.
    """
    try:
        parts = urlsplit(url)
        port = parts.port
    except ValueError:
        return False
    if parts.scheme not in ("http", "https") or not parts.hostname or port is not None:
        return False
    host = parts.hostname.lower().rstrip(".")
    return any(host == domain or host.endswith("." + domain) for domain in domains)


def trusted_thumbnail(url: str | None, domains: Iterable[str] | None) -> str | None:
    """`url` if it is https on one of the platform's CDN `domains` (None allows any host).

    The browser loads thumbnails directly, so a hostile page title or API field must not be able
    to point it at arbitrary hosts.
    """
    if not url or not url.startswith("https://"):
        return None
    return url if domains is None or host_matches(url, domains) else None
