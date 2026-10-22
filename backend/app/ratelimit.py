"""In-memory abuse limits per client IP: a token bucket and a failed-auth lockout."""

import ipaddress
import math
import threading
import time
from collections.abc import Callable
from typing import Annotated

from fastapi import Depends, HTTPException, Request, status

from app.config import settings

AUTH_FAIL_WINDOW_SECONDS = 600
# Hard cap so a flood of spoofed or rotating addresses can't grow memory without bound.
MAX_ENTRIES = 10_000
SWEEP_INTERVAL_SECONDS = 60
# A sweep triggered by a full table trims to this share of the cap (headroom for new
# entries) and never runs more often than MIN_SWEEP_GAP_SECONDS, so a table that stays full
# can't turn every request into an O(n) rebuild.
LOW_WATERMARK = 0.9
MIN_SWEEP_GAP_SECONDS = 1.0


class RateLimits:
    """Per-IP token buckets and failed-auth lockouts, held in memory behind one lock.

    Every table is capped at MAX_ENTRIES; at the cap the least recently touched key is
    dropped. Tables stay in least-recently-used order because writes pop before assigning.
    """

    def __init__(self, clock: Callable[[], float] = time.monotonic) -> None:
        self.clock = clock
        self._lock = threading.Lock()
        self._buckets: dict[str, tuple[float, float]] = {}  # ip -> (tokens, last seen)
        self._failures: dict[str, list[float]] = {}
        self._locked_until: dict[str, float] = {}
        self._last_sweep = 0.0
        self._last_trim = float("-inf")

    def reset(self) -> None:
        with self._lock:
            self._buckets.clear()
            self._failures.clear()
            self._locked_until.clear()
            self._last_sweep = 0.0
            self._last_trim = float("-inf")

    def take(self, ip: str) -> int | None:
        """Use one token. Returns None if allowed, else the seconds until one is available."""
        capacity = float(settings.rate_limit_per_minute)
        refill = capacity / 60
        with self._lock:
            now = self.clock()
            self._sweep(now)
            tokens, seen = self._buckets.pop(ip, (capacity, now))
            tokens = min(capacity, tokens + (now - seen) * refill)
            if tokens < 1:
                self._store(self._buckets, ip, (tokens, now), now)
                return math.ceil((1 - tokens) / refill)
            self._store(self._buckets, ip, (tokens - 1, now), now)
            return None

    def locked_out(self, ip: str) -> int | None:
        """Seconds of lockout left, or None."""
        with self._lock:
            now = self.clock()
            until = self._locked_until.get(ip)
            if until is None:
                return None
            if until <= now:
                del self._locked_until[ip]
                return None
            return math.ceil(until - now)

    def record_failure(self, ip: str) -> None:
        with self._lock:
            now = self.clock()
            self._sweep(now)
            previous = self._failures.pop(ip, [])
            recent = [t for t in previous if now - t < AUTH_FAIL_WINDOW_SECONDS]
            recent.append(now)
            if len(recent) >= settings.auth_fail_limit:
                self._locked_until.pop(ip, None)
                self._store(self._locked_until, ip, now + AUTH_FAIL_WINDOW_SECONDS, now)
                recent = []
            self._store(self._failures, ip, recent, now)

    def _store[V](self, table: dict[str, V], ip: str, value: V, now: float) -> None:
        """Insert as most recent and cap memory, with infrequent bulk LRU trims."""
        table[ip] = value
        if len(table) <= MAX_ENTRIES:
            return
        if now - self._last_trim >= MIN_SWEEP_GAP_SECONDS:
            self._last_trim = now
            target = int(MAX_ENTRIES * LOW_WATERMARK)
            for stale in list(table)[: len(table) - target]:
                del table[stale]
        else:
            # Keep the hard cap between trims without rebuilding a full table.
            del table[next(iter(table))]

    def _sweep(self, now: float) -> None:
        """Drop entries that no longer matter. A full bucket is the same as no bucket."""
        if now - self._last_sweep < SWEEP_INTERVAL_SECONDS:
            return
        self._last_sweep = now
        capacity = float(settings.rate_limit_per_minute)
        refill = capacity / 60
        self._buckets = {
            ip: (tokens, seen)
            for ip, (tokens, seen) in self._buckets.items()
            if tokens + (now - seen) * refill < capacity
        }
        self._failures = {
            ip: kept
            for ip, times in self._failures.items()
            if (kept := [t for t in times if now - t < AUTH_FAIL_WINDOW_SECONDS])
        }
        self._locked_until = {ip: u for ip, u in self._locked_until.items() if u > now}


limits = RateLimits()


def client_ip(request: Request) -> str:
    """The key a client is limited under: IPv4 as is, IPv6 collapsed to its /64.

    One IPv6 allocation is a /64 or larger, so keying on the full address would hand an
    attacker billions of identities. Anything that isn't an IP (e.g. the test client's
    "testclient") is used verbatim; a missing client becomes "unknown".
    """
    host = request.client.host if request.client else ""
    if not host:
        return "unknown"
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return host[:64]
    if isinstance(address, ipaddress.IPv6Address):
        if address.ipv4_mapped:
            return str(address.ipv4_mapped)
        return str(ipaddress.ip_network(f"{ipaddress.IPv6Address(int(address))}/64", strict=False))
    return str(address)


def too_many(retry_after: int) -> HTTPException:
    return HTTPException(
        status.HTTP_429_TOO_MANY_REQUESTS,
        "Too many requests, try again later",
        headers={"Retry-After": str(max(retry_after, 1))},
    )


def lockout_left(request: Request) -> int | None:
    """Seconds this client stays locked out, or None."""
    return limits.locked_out(client_ip(request))


async def rate_limit(request: Request) -> None:
    """Dependency: spend one token from the client's bucket or answer 429."""
    if (wait := limits.take(client_ip(request))) is not None:
        raise too_many(wait)


def record_auth_failure(request: Request) -> None:
    limits.record_failure(client_ip(request))


RateLimited = Annotated[None, Depends(rate_limit)]
