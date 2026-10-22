def test_health_reports_versions_platforms_and_auth(client):
    body = client.get("/api/health").json()

    assert body["status"] == "ok"
    assert body["yt_dlp"]
    assert body["platforms"] == ["fake"]
    assert body["auth_required"] is False


def test_health_uses_startup_version_without_running_another_subprocess(client, monkeypatch):
    from app import main

    initial = client.get("/api/health").json()["gallery_dl"]

    def unexpected_check():
        raise AssertionError("health must not spawn or wait for gallery-dl")

    monkeypatch.setattr(main, "_gallery_dl_version", unexpected_check)

    assert client.get("/api/health").json()["gallery_dl"] == initial


def test_gallery_dl_failure_is_cached_briefly(monkeypatch):
    import subprocess

    from app import main
    from app.config import settings

    main._gallery_dl_versions.clear()
    now = [1000.0]
    monkeypatch.setattr(main.time, "monotonic", lambda: now[0])
    monkeypatch.setattr(settings, "gallery_dl_bin", "/opt/gallery-dl/bin/gallery-dl")
    calls: list[list[str]] = []
    outcomes = [OSError("not there yet"), "1.30.0\n", "unused"]

    def fake_run(argv, **kwargs):
        calls.append(argv)
        outcome = outcomes[len(calls) - 1]
        if isinstance(outcome, Exception):
            raise outcome
        return subprocess.CompletedProcess(argv, 0, stdout=outcome)

    monkeypatch.setattr(main.subprocess, "run", fake_run)

    assert main._gallery_dl_version() is None
    assert main._gallery_dl_version() is None
    now[0] += 61
    assert main._gallery_dl_version() == "1.30.0"
    assert main._gallery_dl_version() == "1.30.0"  # cached after a success
    assert [c[0] for c in calls] == ["/opt/gallery-dl/bin/gallery-dl"] * 2
    main._gallery_dl_versions.clear()


def test_concurrent_health_probes_share_one_failed_version_check(monkeypatch):
    import subprocess
    import threading
    import time
    from concurrent.futures import ThreadPoolExecutor

    from app import main
    from app.config import settings

    main._gallery_dl_versions.clear()
    monkeypatch.setattr(settings, "gallery_dl_bin", "/missing/gallery-dl")
    calls = 0
    call_lock = threading.Lock()
    start = threading.Barrier(8)

    def failed_run(argv, **kwargs):
        nonlocal calls
        with call_lock:
            calls += 1
        time.sleep(0.02)
        raise subprocess.TimeoutExpired(argv, kwargs["timeout"])

    monkeypatch.setattr(main.subprocess, "run", failed_run)

    def probe(_):
        start.wait()
        return main._gallery_dl_version()

    with ThreadPoolExecutor(max_workers=8) as pool:
        assert list(pool.map(probe, range(8))) == [None] * 8
    assert calls == 1
    main._gallery_dl_versions.clear()
