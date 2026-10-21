def test_health_reports_versions_platforms_and_auth(client):
    body = client.get("/api/health").json()

    assert body["status"] == "ok"
    assert body["yt_dlp"]
    assert body["platforms"] == ["fake"]
    assert body["auth_required"] is False
