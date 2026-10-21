# M5 contract (release 1.0 hardening)

Shared by the backend, frontend, and ops tracks. Change only with the integrator.

## 1. Download tickets (replace `?token=` on links)

The master token must no longer appear in URLs. Links that can't send headers (EventSource,
`<a href>` downloads) use a short-lived ticket instead.

- `POST /api/jobs/{id}/ticket` (Bearer token required) → `200 {"ticket": str, "expires_at": ISO-8601}`.
  404 if the job is unknown.
- Ticket = `base64url(job_id . expiry_unix) + "." + base64url(HMAC-SHA256(secret, job_id|expiry))`.
  `secret` is random per process (tickets die on restart, like jobs). TTL: `OPENTAKEZ_TICKET_TTL_SECONDS`, default 600.
  Reusable until expiry (SSE reconnects, HTTP range requests, retried downloads).
- Accepted as `?ticket=` ONLY on `GET /api/jobs/{id}/events`, `/files/{index}`, `/file`, `/zip`, and only
  for the job id it was issued for. Compared with `hmac.compare_digest`. Expired/invalid → 401.
- Those four routes also still accept the Bearer header. `?token=` is no longer accepted anywhere.
- Access-log redaction covers `ticket=` as well.

Frontend: before opening an EventSource or starting a file/ZIP download, request a ticket for the
job (cache per job until ~30 s before `expires_at`). Downloads are started programmatically after
the ticket arrives (anchor with `download`, `click()`), triggered by the user's click.

## 2. Rate limiting

In-memory, per client IP (`request.client.host`; correct behind a proxy because uvicorn trusts
`OPENTAKEZ_FORWARDED_ALLOW_IPS`). Token bucket, configurable:

- `OPENTAKEZ_RATE_LIMIT_PER_MINUTE` (default 30) for `POST /api/resolve`, `POST /api/jobs`,
  `PUT /api/cookies/*`, `POST /api/jobs/{id}/ticket`.
- Failed auth (401 from a wrong token or ticket): after `OPENTAKEZ_AUTH_FAIL_LIMIT` (default 10) failures within 10 min,
  that IP gets 429 for all `/api/` routes except `/api/health` for 10 min.
- 429 response: `{"detail": "Too many requests, try again later"}` + `Retry-After` header (seconds).
- `/api/health`, SSE streaming and file downloads are not rate limited (but tickets are).

Frontend: map 429 to a message using `Retry-After` (i18n key `error.rateLimited`).

## 3. Allowed hosts (DNS rebinding)

`OPENTAKEZ_ALLOWED_HOSTS` (JSON list, default `["*"]`). When set, requests whose Host header doesn't match get 400
(Starlette `TrustedHostMiddleware`). README recommends setting it to the public hostname.

## 4. Container

docker-compose: `read_only: true`, `tmpfs: [/tmp]`, data stays on the named volume. Everything that writes must
write to `/data` or `/tmp` (yt-dlp cache dir → `/tmp/yt-dlp-cache` or `cachedir: False`, gallery-dl HOME
already a temp dir, uv cache only used by the optional updater → document that
`OPENTAKEZ_UPDATE_YTDLP=true` requires `read_only: false`).

## 5. Release

- GitHub Actions: on tag `v*` build multi-arch (`linux/amd64`, `linux/arm64` — Raspberry Pi) and push to
  `ghcr.io/<owner>/opentakez` with tags `vX.Y.Z`, `X.Y`, `latest`. No scheduled rebuilds (pinned deps); instead
  Dependabot PRs for `backend/uv.lock` (yt-dlp weekly), `tools/gallery-dl.txt` (if supported, else documented
  manual step), npm (`web/`) and GitHub Actions.
- `docker-compose.yml` uses `image: ghcr.io/timhofmann27-bot/opentakez:latest` with `build: .` kept for local builds
  (documented).
- Docs: `docs/self-hosting.md` (Docker, Caddy reverse proxy with HTTPS, Cloudflare Tunnel note, token, allowed
  hosts, backups of /data, updating, Android install + share), `docs/roadmap.md` (Spotify planned: metadata via
  public embed pages without API keys, optional API credentials; why: Feb 2026 Spotify API changes require
  Premium + limit dev apps; Android app M6), `CHANGELOG.md` with 1.0.0.
- Version: backend `app/__init__.py` and `web/package.json` → `1.0.0`.
