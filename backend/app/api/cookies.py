from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, status
from starlette.concurrency import run_in_threadpool

from app.api.auth import require_token
from app.config import settings
from app.cookies import COOKIE_PLATFORMS, CookieError, CookieStatus, CookieStore, CookieUpload

router = APIRouter(prefix="/api/cookies", dependencies=[Depends(require_token)])


def get_cookies(request: Request) -> CookieStore:
    return request.app.state.cookies


CookiesDep = Annotated[CookieStore, Depends(get_cookies)]


def _known(platform: str) -> str:
    if platform not in COOKIE_PLATFORMS:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown platform")
    return platform


@router.get("")
def list_cookies(store: CookiesDep) -> list[CookieStatus]:
    return [store.status(platform) for platform in COOKIE_PLATFORMS]


@router.put("/{platform}")
async def upload_cookies(platform: str, request: Request, store: CookiesDep) -> CookieUpload:
    """Raw cookies.txt as the body. The size limit applies to the declared and the actual size."""
    _known(platform)
    limit = settings.cookie_max_kb * 1024
    too_large = HTTPException(status.HTTP_413_CONTENT_TOO_LARGE, "The file is too large")
    try:
        if int(request.headers.get("content-length", "0")) > limit:
            raise too_large
    except ValueError:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Invalid Content-Length") from None
    body = bytearray()
    async for chunk in request.stream():
        body += chunk
        if len(body) > limit:
            raise too_large
    try:
        # Parsing and fsync would block the event loop.
        return await run_in_threadpool(store.save, platform, bytes(body))
    except CookieError as e:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, str(e)) from e


@router.delete("/{platform}", status_code=status.HTTP_204_NO_CONTENT)
def delete_cookies(platform: str, store: CookiesDep) -> None:
    store.delete(_known(platform))
