from fastapi.testclient import TestClient

from app.main import app


def test_health_returns_ok_and_versions():
    response = TestClient(app).get("/api/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["yt_dlp"]
