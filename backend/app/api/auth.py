import secrets
from typing import Annotated, NoReturn

from fastapi import Header, HTTPException, Query, Request, status

from app import tickets
from app.config import settings
from app.ratelimit import lockout_left, record_auth_failure, too_many


def _reject(request: Request, *, count_failure: bool) -> NoReturn:
    """Answer 401, optionally counting it toward the client's failed-auth lockout.

    Count only credentials that look like guesses. A missing credential or an expired but
    authentic ticket is an honest client with a stale or absent login, not an attack.
    """
    if count_failure:
        record_auth_failure(request)
    raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid or missing token")


def _bearer(authorization: str | None) -> str:
    return (authorization or "").removeprefix("Bearer ").strip()


def _token_ok(supplied: str) -> bool:
    return secrets.compare_digest(supplied.encode(), settings.token.encode())


def _locked_out_unless(request: Request, credential_valid: bool) -> None:
    """429 a locked-out client, except one presenting a valid credential.

    A valid credential is not a guess, so letting it through opens no brute-force path, and
    it keeps an attacker (or a neighbour behind the same NAT or proxy IP) from locking the
    owner out of their own instance.
    """
    if credential_valid:
        return
    if (left := lockout_left(request)) is not None:
        raise too_many(left)


async def reject_locked_out(
    request: Request, authorization: Annotated[str | None, Header()] = None
) -> None:
    """Runs before auth on every /api router: a locked-out IP only gets in with the token."""
    supplied = _bearer(authorization)
    _locked_out_unless(request, bool(supplied) and _token_ok(supplied))


async def reject_locked_out_link(
    request: Request, authorization: Annotated[str | None, Header()] = None
) -> None:
    """Same for the link routes, where a ticket for the path's job also counts."""
    supplied = _bearer(authorization)
    ticket = request.query_params.get("ticket")
    job_id = request.path_params.get("job_id")
    valid = bool(supplied) and _token_ok(supplied)
    valid = valid or bool(ticket and job_id and tickets.verify(ticket, job_id))
    _locked_out_unless(request, valid)


async def require_token(
    request: Request, authorization: Annotated[str | None, Header()] = None
) -> None:
    """Check the instance token (Authorization header only; never accepted in a URL).

    A missing credential is not counted as a failed attempt, only a wrong one.
    """
    if not settings.token:
        return
    supplied = _bearer(authorization)
    if not _token_ok(supplied):
        _reject(request, count_failure=bool(supplied))


async def require_token_or_ticket(
    job_id: str,
    request: Request,
    authorization: Annotated[str | None, Header()] = None,
    ticket: Annotated[str | None, Query()] = None,
) -> None:
    """For links that can't send headers (EventSource, downloads): token or a job ticket."""
    if not settings.token:
        return
    supplied = _bearer(authorization)
    if supplied and _token_ok(supplied):
        return
    result = tickets.check(ticket, job_id) if ticket else None
    if result is tickets.TicketCheck.VALID:
        return
    # A wrong token or a forged/foreign ticket is a guess; an expired authentic ticket isn't.
    _reject(
        request,
        count_failure=bool(supplied) or result is tickets.TicketCheck.INVALID,
    )
