import secrets
from typing import Annotated

from fastapi import Header, HTTPException, Query, status

from app.config import settings


def require_token(
    authorization: Annotated[str | None, Header()] = None,
    token: Annotated[str | None, Query()] = None,
) -> None:
    """Check the instance token if one is configured.

    Also accepted as `?token=` because EventSource and plain download links can't send headers.
    """
    if not settings.token:
        return
    supplied = token or (authorization or "").removeprefix("Bearer ").strip()
    if not secrets.compare_digest(supplied.encode(), settings.token.encode()):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid or missing token")
