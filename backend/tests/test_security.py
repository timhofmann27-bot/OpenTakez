import logging

import pytest

from app.config import Settings
from app.security import RedactTokenFilter, ensure_token
from tests.conftest import wait_for_job

URL = "https://example.com/clip"


class TestEnsureToken:
    def test_generates_and_persists_a_token_when_none_is_set(self, tmp_path):
        first = Settings(token="", data_dir=tmp_path)
        ensure_token(first)

        second = Settings(token="", data_dir=tmp_path)
        ensure_token(second)

        assert len(first.token) >= 24
        assert second.token == first.token
        assert (tmp_path / "token").stat().st_mode & 0o777 == 0o600

    def test_keeps_a_configured_token(self, tmp_path):
        settings = Settings(token="mine", data_dir=tmp_path)
        ensure_token(settings)

        assert settings.token == "mine"
        assert not (tmp_path / "token").exists()

    def test_open_mode_must_be_explicit(self, tmp_path):
        settings = Settings(token="", allow_no_token=True, data_dir=tmp_path)
        ensure_token(settings)

        assert settings.token == ""


def test_access_log_redacts_query_tokens():
    record = logging.LogRecord(
        "uvicorn.access", logging.INFO, "", 0, '%s - "%s %s"', 
        ("1.2.3.4", "GET", "/api/jobs/x/file?token=s3cret&a=1"), None,
    )  # fmt: skip

    RedactTokenFilter().filter(record)

    assert "s3cret" not in record.getMessage()
    assert "token=[redacted]&a=1" in record.getMessage()


def test_security_headers(client):
    api = client.get("/api/health")

    assert api.headers["x-content-type-options"] == "nosniff"
    assert api.headers["referrer-policy"] == "no-referrer"
    assert api.headers["cache-control"] == "no-store"


def test_spa_gets_a_content_security_policy(client, tmp_path, monkeypatch):
    from app.config import settings

    web = tmp_path / "web"
    web.mkdir()
    (web / "index.html").write_text("<div id=root></div>")
    monkeypatch.setattr(settings, "web_dir", web)

    response = client.get("/")

    assert "frame-ancestors 'none'" in response.headers["content-security-policy"]
    assert client.get("/%00").status_code == 200


def test_queue_limit_returns_503(client, fake_provider, monkeypatch):
    import threading

    from app.main import app

    monkeypatch.setattr(app.state.jobs, "_max_queued", 1)
    fake_provider.gate = threading.Event()
    first = client.post("/api/jobs", json={"url": URL, "option": "video-best"})

    second = client.post("/api/jobs", json={"url": URL, "option": "video-best"})

    assert second.status_code == 503
    fake_provider.gate.set()
    wait_for_job(client, first.json()["id"], "done")


@pytest.mark.parametrize("kind", ["playlist", "multi_video"])
def test_nested_playlists_are_rejected_at_resolve(kind):
    from app.providers.base import ProviderError
    from tests.test_ytdlp_provider import FakeYoutubeDL, make_provider

    entries = [{"_type": kind, "id": "PL2", "ie_key": "Youtube"}]
    ydl = FakeYoutubeDL(info={"_type": kind, "id": "PL1", "title": "Mix", "entries": entries})

    with pytest.raises(ProviderError, match="Channels and profiles"):
        make_provider(ydl).resolve("https://youtube.com/playlist?list=PL1")
    assert ydl.options["extract_flat"] == "in_playlist"


def test_download_is_capped(tmp_path):
    from app.providers.base import DownloadOption
    from app.providers.ytdlp import YtDlpProvider
    from tests.test_ytdlp_provider import FakeYoutubeDL

    ydl = FakeYoutubeDL(files=["a.mp4"])
    provider = YtDlpProvider("youtube", ["youtube.com"], ["youtube"], ydl, max_filesize=1000)

    provider.download("https://youtube.com/x", DownloadOption.VIDEO_BEST, tmp_path, lambda p: None)

    assert ydl.options["max_filesize"] == 1000
    assert ydl.options["playlist_items"] == "1:50"
    assert ydl.options["socket_timeout"] == 20
