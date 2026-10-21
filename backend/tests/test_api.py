import json
import threading
from datetime import timedelta

import pytest

from app.api.routes import _content_disposition
from app.config import settings
from app.main import app
from tests.conftest import wait_for_job

URL = "https://example.com/clip"


def start_job(client, url=URL, option="video-best"):
    response = client.post("/api/jobs", json={"url": url, "option": option})
    assert response.status_code == 201
    return response.json()


def test_resolve_returns_media_info(client):
    response = client.post("/api/resolve", json={"url": f"Look at this {URL} !"})

    assert response.status_code == 200
    assert response.json()["title"] == "Clip"
    assert response.json()["url"] == URL


@pytest.mark.parametrize(
    ("text", "detail"),
    [
        ("no link", "No link found"),
        ("https://unsupported.org/video", "This site isn't supported"),
        ("http://127.0.0.1:8000/api/health", "This site isn't supported"),
    ],
)
def test_resolve_rejects_text_without_supported_link(client, text, detail):
    response = client.post("/api/resolve", json={"url": text})

    assert response.status_code == 400
    assert response.json()["detail"] == detail


def test_resolve_platform_error_is_422_with_message(client, fake_provider):
    fake_provider.error = "Video unavailable"

    response = client.post("/api/resolve", json={"url": URL})

    assert response.status_code == 422
    assert response.json()["detail"] == "Video unavailable"


def test_job_downloads_and_serves_the_file(client):
    job = start_job(client)

    done = wait_for_job(client, job["id"], "done")
    response = client.get(f"/api/jobs/{job['id']}/file")

    assert done["files"] == ["Clip [abc].mp4"]
    assert response.content == b"video-bytes"
    assert response.headers["content-disposition"] == (
        "attachment; filename=\"Clip [abc].mp4\"; filename*=UTF-8''Clip%20%5Babc%5D.mp4"
    )


def test_content_disposition_has_ascii_fallback_for_unicode_names():
    header = _content_disposition('Café "Live" – Ünïcode.mp3')

    assert header == (
        'attachment; filename="Cafe Live  Unicode.mp3"; '
        "filename*=UTF-8''Caf%C3%A9%20%22Live%22%20%E2%80%93%20%C3%9Cn%C3%AFcode.mp3"
    )


def test_content_disposition_strips_control_characters():
    assert 'filename="ab.mp3"' in _content_disposition("a\r\nb.mp3")


def test_failed_job_reports_error(client, fake_provider):
    fake_provider.error = "Private video"

    job = wait_for_job(client, start_job(client)["id"], "error")

    assert job["error"] == "Private video"
    assert client.get(f"/api/jobs/{job['id']}/file").status_code == 409


def test_events_stream_until_job_finishes(client):
    job = start_job(client)

    with client.stream("GET", f"/api/jobs/{job['id']}/events") as response:
        assert response.headers["content-type"].startswith("text/event-stream")
        events = [
            json.loads(line.removeprefix("data: "))
            for line in response.iter_lines()
            if line.startswith("data: ")
        ]

    assert events[-1]["status"] == "done"


def test_running_job_cannot_be_deleted_but_finished_one_can(client, fake_provider):
    fake_provider.gate = threading.Event()
    job = start_job(client)
    wait_for_job(client, job["id"], "running")

    assert client.delete(f"/api/jobs/{job['id']}").status_code == 409

    fake_provider.gate.set()
    wait_for_job(client, job["id"], "done")
    assert client.delete(f"/api/jobs/{job['id']}").status_code == 204
    assert client.get(f"/api/jobs/{job['id']}").status_code == 404


def test_cleanup_removes_expired_jobs_and_files(client):
    job = wait_for_job(client, start_job(client)["id"], "done")
    jobs = app.state.jobs
    job_dir = jobs.file_path(jobs.get(job["id"]), "x").parent

    assert jobs.cleanup(timedelta(hours=1)) == 0
    assert jobs.cleanup(timedelta(seconds=-1)) == 1
    assert not job_dir.exists()


def test_unknown_job_is_404(client):
    assert client.get("/api/jobs/nope").status_code == 404
    assert client.get("/api/jobs/nope/events").status_code == 404


def test_invalid_option_is_rejected(client):
    response = client.post("/api/jobs", json={"url": URL, "option": "video-8k"})

    assert response.status_code == 422


class TestToken:
    @pytest.fixture(autouse=True)
    def token(self, client, monkeypatch):
        monkeypatch.setattr(settings, "token", "s3cret")

    def test_api_requires_token(self, client):
        assert client.post("/api/resolve", json={"url": URL}).status_code == 401
        wrong = {"Authorization": "Bearer wrong"}
        assert client.get("/api/jobs/x", headers=wrong).status_code == 401

    def test_bearer_header_and_query_token_are_accepted(self, client):
        headers = {"Authorization": "Bearer s3cret"}
        assert client.post("/api/resolve", json={"url": URL}, headers=headers).status_code == 200
        assert client.post("/api/resolve?token=s3cret", json={"url": URL}).status_code == 200

    def test_health_stays_public_and_reports_auth(self, client):
        response = client.get("/api/health")

        assert response.status_code == 200
        assert response.json()["auth_required"] is True
