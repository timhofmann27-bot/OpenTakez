"""Short-lived download tickets so the master token never appears in a URL.

A ticket is bound to one job and signed with a per-process random secret, so it dies on
restart (like jobs) and can't be turned into access to anything else. The secret is per
process, so tickets only work with a single worker and a single replica (jobs are in
memory too): under `--workers N` or several replicas a ticket fails on the other processes.
"""

import base64
import binascii
import enum
import hashlib
import hmac
import re
import secrets
import time
from datetime import UTC, datetime

from app.config import settings

_secret = secrets.token_bytes(32)

_B64_ALPHABET = re.compile(r"[A-Za-z0-9_-]*")
_MAX_PART_LENGTH = 512  # a real ticket part is well under 100 characters
_MAX_EXPIRY_DIGITS = 15


class TicketCheck(enum.Enum):
    VALID = "valid"
    # Bad signature, wrong job or malformed: someone guessing or tampering.
    INVALID = "invalid"
    # Authentic signature for this job, but past its expiry: an honest stale link.
    EXPIRED = "expired"


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _unb64(text: str) -> bytes:
    # urlsafe_b64decode silently drops characters outside the alphabet; refuse them instead.
    if len(text) > _MAX_PART_LENGTH or not _B64_ALPHABET.fullmatch(text):
        raise ValueError("not base64url")
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def _sign(job_id: str, expiry: int) -> bytes:
    return hmac.new(_secret, f"{job_id}|{expiry}".encode(), hashlib.sha256).digest()


def issue(job_id: str, now: float | None = None) -> tuple[str, datetime]:
    expiry = int((time.time() if now is None else now) + settings.ticket_ttl_seconds)
    ticket = f"{_b64(f'{job_id}.{expiry}'.encode())}.{_b64(_sign(job_id, expiry))}"
    return ticket, datetime.fromtimestamp(expiry, UTC)


def _parse_expiry(raw: str) -> int:
    """Only the canonical form we issue: ASCII digits, no sign, spaces, underscores or zeros."""
    if not (raw.isascii() and raw.isdigit()) or len(raw) > _MAX_EXPIRY_DIGITS or raw[0] == "0":
        raise ValueError("non-canonical expiry")
    return int(raw)


def check(ticket: str, job_id: str, now: float | None = None) -> TicketCheck:
    try:
        payload, signature = ticket.split(".")
        ticket_job, _, raw_expiry = _unb64(payload).decode().rpartition(".")
        expiry = _parse_expiry(raw_expiry)
        given = _unb64(signature)
    except (ValueError, binascii.Error):  # malformed input, incl. non-ASCII
        return TicketCheck.INVALID
    # Both checks always run so timing doesn't reveal which one failed.
    signed = hmac.compare_digest(given, _sign(ticket_job, expiry))
    same_job = hmac.compare_digest(ticket_job.encode(), job_id.encode())
    if not (signed and same_job):
        return TicketCheck.INVALID
    if expiry > (time.time() if now is None else now):
        return TicketCheck.VALID
    return TicketCheck.EXPIRED


def verify(ticket: str, job_id: str, now: float | None = None) -> bool:
    return check(ticket, job_id, now) is TicketCheck.VALID
