import pytest

from app.config import settings
from app.ratelimit import AUTH_FAIL_WINDOW_SECONDS, RateLimits

URL = "https://example.com/clip"


class Clock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


@pytest.fixture
def clock(fresh_limits):
    fresh_limits.clock = Clock()
    return fresh_limits.clock


@pytest.fixture
def small(monkeypatch):
    monkeypatch.setattr(settings, "rate_limit_per_minute", 3)
    monkeypatch.setattr(settings, "auth_fail_limit", 3)


class TestBucket:
    def test_burst_then_refill_with_clock(self, small):
        limits = RateLimits(clock=(c := Clock()))

        assert [limits.take("1.1.1.1") for _ in range(3)] == [None, None, None]
        assert limits.take("1.1.1.1") == 20  # 3 per minute -> one token per 20 s
        c.now += 19
        assert limits.take("1.1.1.1") is not None
        c.now += 1
        assert limits.take("1.1.1.1") is None

    def test_ips_are_independent(self, small):
        limits = RateLimits(clock=Clock())
        for _ in range(3):
            limits.take("1.1.1.1")

        assert limits.take("1.1.1.1") is not None
        assert limits.take("2.2.2.2") is None

    def test_idle_entries_are_evicted(self, small):
        limits = RateLimits(clock=(c := Clock()))
        for i in range(50):
            limits.take(f"10.0.0.{i}")
        c.now += 3600
        limits.take("9.9.9.9")

        assert len(limits._buckets) <= 1

    def test_memory_is_capped(self, monkeypatch):
        monkeypatch.setattr("app.ratelimit.MAX_ENTRIES", 20)
        limits = RateLimits(clock=Clock())
        for i in range(100):
            limits.take(f"ip{i}")
        limits.take("last")

        assert len(limits._buckets) <= 20

    def test_full_table_trims_to_low_watermark_with_a_minimum_gap(self, monkeypatch):
        monkeypatch.setattr("app.ratelimit.MAX_ENTRIES", 20)
        limits = RateLimits(clock=(clock := Clock()))
        for i in range(21):
            limits.take(f"ip{i}")

        assert len(limits._buckets) == 18
        for i in range(21, 24):
            limits.take(f"ip{i}")
        assert len(limits._buckets) == 20  # one eviction; no second full-table sweep

        clock.now += 1.1
        limits.take("after-gap")
        assert len(limits._buckets) == 18


class TestFailures:
    def test_lockout_after_limit_and_expiry(self, small):
        limits = RateLimits(clock=(c := Clock()))
        for _ in range(2):
            limits.record_failure("1.1.1.1")
        assert limits.locked_out("1.1.1.1") is None
        limits.record_failure("1.1.1.1")

        assert limits.locked_out("1.1.1.1") == AUTH_FAIL_WINDOW_SECONDS
        assert limits.locked_out("2.2.2.2") is None
        c.now += AUTH_FAIL_WINDOW_SECONDS
        assert limits.locked_out("1.1.1.1") is None

    def test_old_failures_age_out(self, small):
        limits = RateLimits(clock=(c := Clock()))
        for _ in range(2):
            limits.record_failure("1.1.1.1")
        c.now += AUTH_FAIL_WINDOW_SECONDS + 1
        limits.record_failure("1.1.1.1")

        assert limits.locked_out("1.1.1.1") is None


class TestApi:
    def test_burst_gets_429_with_retry_after(self, client, clock, small):
        body = {"url": URL}
        assert [client.post("/api/resolve", json=body).status_code for _ in range(3)] == [200] * 3

        response = client.post("/api/resolve", json=body)

        assert response.status_code == 429
        assert response.json() == {"detail": "Too many requests, try again later"}
        assert response.headers["Retry-After"] == "20"
        clock.now += 20
        assert client.post("/api/resolve", json=body).status_code == 200

    def test_limit_covers_listed_routes_and_shares_one_bucket(self, client, clock, small):
        job = client.post("/api/jobs", json={"url": URL, "option": "video-best"}).json()["id"]
        assert client.post(f"/api/jobs/{job}/ticket").status_code == 200
        assert client.put("/api/cookies/instagram", content=b"x").status_code == 422

        assert client.post("/api/resolve", json={"url": URL}).status_code == 429

    def test_other_routes_are_not_limited(self, client, clock, small):
        for _ in range(10):
            assert client.get("/api/health").status_code == 200
            assert client.get("/api/cookies").status_code == 200
            assert client.get("/api/jobs/nope").status_code == 404

    def test_downloads_with_a_ticket_are_not_rate_limited(self, client, clock, small):
        job = client.post("/api/jobs", json={"url": URL, "option": "video-best"}).json()["id"]
        for _ in range(10):
            assert client.get(f"/api/jobs/{job}/zip").status_code in (200, 409)

    def test_ips_are_independent_through_the_api(self, client, clock, small, monkeypatch):
        for _ in range(3):
            client.post("/api/resolve", json={"url": URL})
        assert client.post("/api/resolve", json={"url": URL}).status_code == 429

        monkeypatch.setattr("app.ratelimit.client_ip", lambda request: "8.8.8.8")
        assert client.post("/api/resolve", json={"url": URL}).status_code == 200

    def test_lockout_after_bad_tokens(self, client, clock, small, monkeypatch):
        monkeypatch.setattr(settings, "token", "s3cret")
        bad = {"Authorization": "Bearer wrong"}
        assert [client.get("/api/cookies", headers=bad).status_code for _ in range(3)] == [401] * 3

        valid = client.get("/api/cookies", headers={"Authorization": "Bearer s3cret"})
        assert valid.status_code == 200
        locked = client.get("/api/cookies", headers=bad)
        assert locked.status_code == 429
        assert locked.headers["Retry-After"] == str(AUTH_FAIL_WINDOW_SECONDS)
        assert client.get("/api/jobs/x/file").status_code == 429
        assert client.get("/api/health").status_code == 200
        clock.now += AUTH_FAIL_WINDOW_SECONDS
        assert (
            client.get("/api/cookies", headers={"Authorization": "Bearer s3cret"}).status_code
            == 200
        )

    def test_bad_tickets_count_as_failures(self, client, clock, small, monkeypatch):
        monkeypatch.setattr(settings, "token", "s3cret")
        for _ in range(3):
            assert client.get("/api/jobs/x/file?ticket=bad").status_code == 401

        assert client.get("/api/jobs/x/file?ticket=bad").status_code == 429

    def test_missing_credentials_are_not_counted(self, client, clock, small, monkeypatch):
        monkeypatch.setattr(settings, "token", "s3cret")
        for _ in range(10):
            assert client.get("/api/cookies").status_code == 401

        assert (
            client.get("/api/cookies", headers={"Authorization": "Bearer s3cret"}).status_code
            == 200
        )

    def test_other_ip_is_not_locked_out(self, client, clock, small, monkeypatch):
        monkeypatch.setattr(settings, "token", "s3cret")
        for _ in range(3):
            client.get("/api/cookies", headers={"Authorization": "Bearer wrong"})

        monkeypatch.setattr("app.ratelimit.client_ip", lambda request: "8.8.8.8")
        assert (
            client.get("/api/cookies", headers={"Authorization": "Bearer s3cret"}).status_code
            == 200
        )
