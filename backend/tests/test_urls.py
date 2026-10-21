import pytest

from app.urls import extract_url, host_matches

DOMAINS = ["youtube.com", "youtu.be"]


@pytest.mark.parametrize(
    "url",
    [
        "https://www.youtube.com/watch?v=abc",
        "https://youtube.com/shorts/abc",
        "https://m.youtube.com/watch?v=abc",
        "https://music.youtube.com/watch?v=abc",
        "http://youtu.be/abc",
        "https://WWW.YouTube.com./watch?v=abc",
    ],
)
def test_allows_platform_hosts(url):
    assert host_matches(url, DOMAINS)


@pytest.mark.parametrize(
    "url",
    [
        "https://evilyoutube.com/watch",
        "https://youtube.com.evil.com/watch",
        "https://127.0.0.1/",
        "http://localhost:8000/api/health",
        "https://youtube.com:8443/watch",
        "file:///etc/passwd",
        "ftp://youtube.com/x",
        "https://[::1/",
        "youtube.com/watch?v=abc",
    ],
)
def test_rejects_other_hosts_schemes_and_ports(url):
    assert not host_matches(url, DOMAINS)


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("https://youtu.be/abc", "https://youtu.be/abc"),
        ("Schau mal! https://youtu.be/abc?si=x.", "https://youtu.be/abc?si=x"),
        ("Clip (https://youtu.be/abc)", "https://youtu.be/abc"),
        ("no link here", None),
    ],
)
def test_extract_url_from_shared_text(text, expected):
    assert extract_url(text) == expected
