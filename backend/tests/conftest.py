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
from app.ratelimit import limits
from app.urls import host_matches


class FakeProvider:
    """Provider for example.com that writes a small file instead of downloading."""

    platform = "fake"
    options = VIDEO_OPTIONS

    def __init__(self) -> None:
        self.error: str | None = None
        self.error_code: str | None = None
        # Name -> content of the files a download writes. Defaults to a single clip.
        self.files: dict[str, bytes] = {"Clip [abc].mp4": b"video-bytes"}
        # Set to hold downloads in the "running" state until the test releases them.
        self.gate: threading.Event | None = None

    def supports(self, url: str) -> bool:
        return host_matches(url, ["example.com"])

    def resolve(self, url: str) -> MediaInfo:
        if self.error:
            raise ProviderError(self.error, code=self.error_code)
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
            raise ProviderError(self.error, code=self.error_code)
        paths = []
        for name, content in self.files.items():
            (out_dir / name).write_bytes(content)
            paths.append(out_dir / name)
        return paths


@pytest.fixture(autouse=True)
def fresh_limits():
    """Rate-limit state is process-global; every test starts clean on the real clock."""
    limits.reset()
    limits.clock = time.monotonic
    yield limits
    limits.reset()
    limits.clock = time.monotonic


@pytest.fixture
def fake_provider() -> FakeProvider:
    return FakeProvider()


@pytest.fixture
def client(tmp_path, monkeypatch, fake_provider):
    monkeypatch.setattr(settings, "data_dir", tmp_path / "data")
    monkeypatch.setattr(settings, "token", "")
    monkeypatch.setattr(settings, "allow_no_token", True)
    # Never build the real registry (and its gallery-dl dependency) for API tests.
    monkeypatch.setattr("app.main.default_providers", lambda *a, **k: Providers([fake_provider]))
    with TestClient(app) as c:
        yield c


def wait_for_job(
    client: TestClient, job_id: str, status: str, timeout: float = 5, headers: dict | None = None
) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        job = client.get(f"/api/jobs/{job_id}", headers=headers).json()
        if job["status"] == status:
            return job
        time.sleep(0.02)
    raise AssertionError(f"job {job_id} never reached {status}, last: {job}")
