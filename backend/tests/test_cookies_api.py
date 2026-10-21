import logging

import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app

SECRET = "UNIQUE-SECRET-VALUE-9f3a"
GOOD = "\t".join([".instagram.com", "TRUE", "/", "TRUE", "4102444800", "sessionid", SECRET])
HDR = {"Content-Type": "text/plain"}


@pytest.fixture
def ck(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "data_dir", tmp_path / "data")
    monkeypatch.setattr(settings, "token", "")
    monkeypatch.setattr(settings, "allow_no_token", True)
    with TestClient(app) as c:
        yield c


def test_list_put_delete(ck):
    r = ck.get("/api/cookies")
    assert r.status_code == 200
    assert {s["platform"] for s in r.json()} == {"instagram", "tiktok", "facebook"}
    assert not any(s["present"] for s in r.json())

    r = ck.put("/api/cookies/instagram", content=GOOD, headers=HDR)
    assert r.status_code == 200
    body = r.json()
    assert body["present"] and body["cookie_count"] == 1 and body["dropped_count"] == 0
    assert any(s["present"] for s in ck.get("/api/cookies").json())

    assert ck.delete("/api/cookies/instagram").status_code == 204
    assert ck.delete("/api/cookies/instagram").status_code == 204
    assert not any(s["present"] for s in ck.get("/api/cookies").json())


def test_unknown_platform_404(ck):
    assert ck.put("/api/cookies/myspace", content=GOOD, headers=HDR).status_code == 404
    assert ck.delete("/api/cookies/myspace").status_code == 404


def test_422_for_invalid(ck):
    r = ck.put("/api/cookies/instagram", content="garbage\n" + SECRET, headers=HDR)
    assert r.status_code == 422
    assert SECRET not in r.text
    r = ck.put("/api/cookies/tiktok", content=GOOD, headers=HDR)
    assert r.status_code == 422 and SECRET not in r.text
    assert ck.put("/api/cookies/instagram", content=b"\xff\xfe", headers=HDR).status_code == 422


def test_413_content_length(ck):
    big = "#" * (settings.cookie_max_kb * 1024 + 1)
    assert ck.put("/api/cookies/instagram", content=big, headers=HDR).status_code == 413


def test_413_streamed_without_length(ck):
    def chunks():
        for _ in range(settings.cookie_max_kb + 2):
            yield b"#" * 1024

    r = ck.put("/api/cookies/instagram", content=chunks(), headers=HDR)
    assert r.status_code == 413


def test_requires_token(ck, monkeypatch):
    monkeypatch.setattr(settings, "token", "t0k")
    assert ck.get("/api/cookies").status_code == 401
    assert ck.put("/api/cookies/instagram", content=GOOD, headers=HDR).status_code == 401
    assert ck.get("/api/cookies", headers={"Authorization": "Bearer t0k"}).status_code == 200


def test_no_value_leaks(ck, caplog):
    caplog.set_level(logging.DEBUG)
    bodies = [
        ck.put("/api/cookies/instagram", content=GOOD, headers=HDR).text,
        ck.get("/api/cookies").text,
        ck.put("/api/cookies/instagram", content=GOOD + "\tx", headers=HDR).text,
        ck.put("/api/cookies/instagram", content=SECRET, headers=HDR).text,
        ck.delete("/api/cookies/instagram").text,
    ]
    assert all(SECRET not in b for b in bodies)
    assert SECRET not in caplog.text


def test_health_lists_cookie_platforms_and_gallery_dl(ck):
    body = ck.get("/api/health").json()
    assert set(body["cookie_platforms"]) == {"instagram", "tiktok", "facebook"}
    assert "gallery_dl" in body  # version string, or null when not installed
    assert body["gallery_dl"] is None or isinstance(body["gallery_dl"], str)


def test_disk_work_does_not_block_the_event_loop(ck, monkeypatch):
    import asyncio

    from app.cookies import CookieStore

    on_loop: list[bool] = []

    def spy(original):
        def wrapper(self, *args, **kwargs):
            try:
                asyncio.get_running_loop()
                on_loop.append(True)
            except RuntimeError:
                on_loop.append(False)
            return original(self, *args, **kwargs)

        return wrapper

    monkeypatch.setattr(CookieStore, "save", spy(CookieStore.save))
    monkeypatch.setattr(CookieStore, "delete", spy(CookieStore.delete))

    ck.put("/api/cookies/instagram", content=GOOD, headers=HDR)
    ck.delete("/api/cookies/instagram")

    assert on_loop == [False, False]
