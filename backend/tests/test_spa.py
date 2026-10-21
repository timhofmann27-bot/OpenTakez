import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app

client = TestClient(app)


@pytest.fixture
def web_dir(tmp_path, monkeypatch):
    web = tmp_path / "web"
    web.mkdir()
    (web / "index.html").write_text('<div id="root"></div>')
    (web / "logo.svg").write_text("<svg></svg>")
    (tmp_path / "secret.txt").write_text("top secret")
    monkeypatch.setattr(settings, "web_dir", web)
    return web


def test_unknown_api_path_is_404_not_spa(web_dir):
    assert client.get("/api/does-not-exist").status_code == 404


def test_static_file_is_served(web_dir):
    assert client.get("/logo.svg").text == "<svg></svg>"


def test_client_route_falls_back_to_index(web_dir):
    response = client.get("/share?url=https://example.com")

    assert response.status_code == 200
    assert '<div id="root">' in response.text


def test_path_traversal_does_not_escape_web_root(web_dir):
    response = client.get("/..%2Fsecret.txt")

    assert "top secret" not in response.text


def test_without_bundled_web_everything_is_404(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "web_dir", tmp_path / "missing")

    assert client.get("/").status_code == 404
