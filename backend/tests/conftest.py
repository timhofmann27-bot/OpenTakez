import threading
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from app.providers import Providers
from app.providers.base import (
    VIDEO_OPTIONS,
    DownloadOption,
    MediaInfo,
    MediaKind,
    Progress,
    ProgressCallback,
    ProviderError,
)
from app.urls import host_matches


class FakeProvider:
    """Provider for example.com that writes a small file instead of downloading."""

    platform = "fake"

    def __init__(self) -> None:
        self.error: str | None = None
        # Set to hold downloads in the "running" state until the test releases them.
        self.gate: threading.Event | None = None

    def supports(self, url: str) -> bool:
        return host_matches(url, ["example.com"])

    def resolve(self, url: str) -> MediaInfo:
        if self.error:
            raise ProviderError(self.error)
        return MediaInfo(
            url=url, platform="fake", kind=MediaKind.VIDEO, title="Clip", options=VIDEO_OPTIONS
        )

    def download(
        self, url: str, option: DownloadOption, out_dir: Path, on_progress: ProgressCallback
    ) -> list[Path]:
        on_progress(Progress(phase="downloading", fraction=0.5))
        if self.gate:
            self.gate.wait(timeout=5)
        if self.error:
            raise ProviderError(self.error)
        file = out_dir / "Clip [abc].mp4"
        file.write_bytes(b"video-bytes")
        return [file]


@pytest.fixture
def fake_provider() -> FakeProvider:
    return FakeProvider()


@pytest.fixture
def client(tmp_path, monkeypatch, fake_provider):
    monkeypatch.setattr(settings, "data_dir", tmp_path / "data")
    monkeypatch.setattr(settings, "token", "")
    with TestClient(app) as c:
        app.state.providers = Providers([fake_provider])
        yield c


def wait_for_job(client: TestClient, job_id: str, status: str, timeout: float = 5) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        job = client.get(f"/api/jobs/{job_id}").json()
        if job["status"] == status:
            return job
        time.sleep(0.02)
    raise AssertionError(f"job {job_id} never reached {status}, last: {job}")
