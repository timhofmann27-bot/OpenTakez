import pytest

from app import tickets
from app.config import settings
from tests.conftest import wait_for_job

URL = "https://example.com/clip"
AUTH = {"Authorization": "Bearer s3cret"}


class TestTicketTokens:
    def test_round_trip(self):
        ticket, expires_at = tickets.issue("job1", now=1000)

        assert tickets.verify(ticket, "job1", now=1001)
        assert expires_at.timestamp() == 1000 + settings.ticket_ttl_seconds

    def test_is_url_safe_without_padding(self):
        ticket, _ = tickets.issue("job1")

        assert "=" not in ticket and "+" not in ticket and "/" not in ticket
        assert ticket.count(".") == 1

    def test_expires(self):
        ticket, _ = tickets.issue("job1", now=1000)
        end = 1000 + settings.ticket_ttl_seconds

        assert tickets.verify(ticket, "job1", now=end - 1)
        assert not tickets.verify(ticket, "job1", now=end)

    def test_bound_to_its_job(self):
        ticket, _ = tickets.issue("job1")

        assert not tickets.verify(ticket, "job2")

    def test_tampering_is_rejected(self):
        ticket, _ = tickets.issue("job1")
        payload, signature = ticket.split(".")
        forged_payload, _ = tickets.issue("job1", now=10**9)
        flipped = signature[:-2] + ("AA" if not signature.endswith("AA") else "BB")

        assert not tickets.verify(f"{payload}.{flipped}", "job1")
        assert not tickets.verify(f"{forged_payload.split('.')[0]}.{signature}", "job1")

    def test_signed_with_another_secret_is_rejected(self, monkeypatch):
        ticket, _ = tickets.issue("job1")
        monkeypatch.setattr(tickets, "_secret", b"x" * 32)

        assert not tickets.verify(ticket, "job1")

    @pytest.mark.parametrize(
        "garbage", ["", ".", "a.b.c", "!!!.???", "é.é", "bm9kb3Q.AAAA", "x" * 5000]
    )
    def test_garbage_is_rejected(self, garbage):
        assert not tickets.verify(garbage, "job1")


@pytest.fixture
def secured(client, monkeypatch):
    monkeypatch.setattr(settings, "token", "s3cret")
    return client


def finished_job(client) -> str:
    response = client.post("/api/jobs", json={"url": URL, "option": "video-best"}, headers=AUTH)
    job_id = response.json()["id"]
    wait_for_job(client, job_id, "done", headers=AUTH)
    return job_id


def ticket_for(client, job_id: str) -> str:
    response = client.post(f"/api/jobs/{job_id}/ticket", headers=AUTH)
    assert response.status_code == 200
    return response.json()["ticket"]


class TestTicketApi:
    def test_issue_requires_the_token(self, secured):
        job_id = finished_job(secured)

        assert secured.post(f"/api/jobs/{job_id}/ticket").status_code == 401
        assert (
            secured.post(f"/api/jobs/{job_id}/ticket", headers={"Authorization": "Bearer no"})
        ).status_code == 401

    def test_response_shape_and_no_master_token(self, secured):
        job_id = finished_job(secured)

        response = secured.post(f"/api/jobs/{job_id}/ticket", headers=AUTH)
        body = response.json()

        assert set(body) == {"ticket", "expires_at"}
        assert "s3cret" not in response.text
        assert body["expires_at"].endswith(("Z", "+00:00"))

    def test_unknown_job_is_404_with_no_extra_detail(self, secured):
        response = secured.post("/api/jobs/nope/ticket", headers=AUTH)

        assert response.status_code == 404
        assert response.json() == {"detail": "Job not found"}

    def test_ticket_opens_all_four_link_routes(self, secured):
        job_id = finished_job(secured)
        q = f"?ticket={ticket_for(secured, job_id)}"

        assert secured.get(f"/api/jobs/{job_id}/file{q}").content == b"video-bytes"
        assert secured.get(f"/api/jobs/{job_id}/files/0{q}").content == b"video-bytes"
        assert secured.get(f"/api/jobs/{job_id}/zip{q}").status_code == 200
        with secured.stream("GET", f"/api/jobs/{job_id}/events{q}") as events:
            assert events.status_code == 200

    def test_ticket_is_reusable(self, secured):
        job_id = finished_job(secured)
        q = f"?ticket={ticket_for(secured, job_id)}"

        assert secured.get(f"/api/jobs/{job_id}/file{q}").status_code == 200
        assert secured.get(f"/api/jobs/{job_id}/file{q}").status_code == 200

    def test_link_routes_still_accept_the_bearer_token(self, secured):
        job_id = finished_job(secured)

        assert secured.get(f"/api/jobs/{job_id}/file", headers=AUTH).status_code == 200

    def test_token_in_query_is_rejected_on_link_routes(self, secured):
        job_id = finished_job(secured)

        assert secured.get(f"/api/jobs/{job_id}/file?token=s3cret").status_code == 401
        assert secured.get(f"/api/jobs/{job_id}/events?token=s3cret").status_code == 401

    def test_expired_ticket_is_401(self, secured, monkeypatch):
        job_id = finished_job(secured)
        monkeypatch.setattr(settings, "ticket_ttl_seconds", -1)
        q = f"?ticket={ticket_for(secured, job_id)}"

        assert secured.get(f"/api/jobs/{job_id}/file{q}").status_code == 401

    def test_tampered_ticket_is_401(self, secured):
        job_id = finished_job(secured)
        ticket = ticket_for(secured, job_id)

        assert secured.get(f"/api/jobs/{job_id}/file?ticket={ticket}x").status_code == 401

    def test_ticket_for_another_job_is_401(self, secured):
        first, second = finished_job(secured), finished_job(secured)
        q = f"?ticket={ticket_for(secured, first)}"

        assert secured.get(f"/api/jobs/{second}/file{q}").status_code == 401

    def test_ticket_does_not_work_on_other_routes(self, secured):
        job_id = finished_job(secured)
        q = f"ticket={ticket_for(secured, job_id)}"

        assert secured.get(f"/api/jobs/{job_id}?{q}").status_code == 401
        assert (
            secured.post(f"/api/jobs?{q}", json={"url": URL, "option": "video-best"}).status_code
            == 401
        )
        assert secured.post(f"/api/jobs/{job_id}/ticket?{q}").status_code == 401
        assert secured.get(f"/api/cookies?{q}").status_code == 401
        assert secured.put(f"/api/cookies/instagram?{q}", content=b"x").status_code == 401
        assert secured.delete(f"/api/jobs/{job_id}?{q}").status_code == 401

    def test_ticket_as_bearer_is_not_the_master_token(self, secured):
        job_id = finished_job(secured)
        ticket = ticket_for(secured, job_id)

        response = secured.get("/api/cookies", headers={"Authorization": f"Bearer {ticket}"})

        assert response.status_code == 401

    def test_open_instance_needs_no_ticket(self, client):
        job_id = finished_job_open(client)

        assert client.get(f"/api/jobs/{job_id}/file").status_code == 200


def finished_job_open(client) -> str:
    job_id = client.post("/api/jobs", json={"url": URL, "option": "video-best"}).json()["id"]
    wait_for_job(client, job_id, "done")
    return job_id
