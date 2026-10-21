def test_health_reports_versions_platforms_and_auth(client):
    body = client.get("/api/health").json()

    assert body["status"] == "ok"
    assert body["yt_dlp"]
    assert body["platforms"] == ["fake"]
    assert body["auth_required"] is False


def test_gallery_dl_version_is_not_cached_when_it_fails(monkeypatch):
    import subprocess

    from app import main
    from app.config import settings

    main._gallery_dl_versions.clear()
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
    assert main._gallery_dl_version() == "1.30.0"
    assert main._gallery_dl_version() == "1.30.0"  # cached after a success
    assert [c[0] for c in calls] == ["/opt/gallery-dl/bin/gallery-dl"] * 2
    main._gallery_dl_versions.clear()
