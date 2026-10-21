# OpenTakez

All-in-one, self-hosted media downloader. Paste or share a link and get the video, photo, or audio file.

Supported (work in progress): YouTube, TikTok, Instagram, Facebook, SoundCloud, and Spotify
(via metadata matching, see [DISCLAIMER](DISCLAIMER.md)).

- **PWA**: install it on your phone, then use *Share → OpenTakez* from any app.
- **Self-hosted**: one Docker container, powered by [yt-dlp](https://github.com/yt-dlp/yt-dlp)
  and ffmpeg.
- **Android app**: planned; runs yt-dlp directly on the device, no server needed.

## Run with Docker

```sh
cp .env.example .env
docker compose up -d --build
docker compose logs opentakez | grep token   # the generated access token
```

Open http://localhost:8000 and enter the token. Set your own with `OPENTAKEZ_TOKEN` in `.env`.

To install the PWA on a phone the instance must be served over HTTPS, for example behind
Caddy, Traefik, or a Cloudflare Tunnel. Set `OPENTAKEZ_FORWARDED_ALLOW_IPS` to the proxy's IP.

Then on Android: open the site in Chrome → menu → *Install app*. OpenTakez now appears in
the *Share* sheet of YouTube, SoundCloud, and other apps.

### Limits

Only single videos and tracks for now; playlists and channels are refused. Files larger than
`OPENTAKEZ_MAX_FILE_MB` are skipped, and at most `OPENTAKEZ_MAX_QUEUED_JOBS` downloads may be
pending at once.

### Logins (cookies)

Instagram posts, and some TikTok and Facebook content, can only be loaded while logged in. You
can give the server a login session as a `cookies.txt` file (Settings → Logins in the app):

1. Log in to the site in a desktop browser.
2. Export the cookies in Netscape `cookies.txt` format with a browser extension such as
   "Get cookies.txt LOCALLY".
3. Upload the file for that platform in Settings.

Only cookies that belong to that platform's domains are kept; everything else in the file is
discarded. The result is stored as `/data/cookies/<platform>.txt` with mode 0600 (directory
0700) in the data volume and is never sent to the app or written to logs. For each download the
server hands a temporary copy to the downloader and deletes it afterwards. Delete the cookies in
Settings (or remove the file) at any time.

**Use a secondary account.** Platforms can restrict or ban accounts that automated tools use,
and anyone with access to the data volume or a backup of it can use the session. Never upload
the cookies of your main account. Cookies expire; upload a fresh file when downloads ask for a
login again.

**Image downloads run as the same user as the app.** gallery-dl is a separate program, but it
runs as the app's user, so in principle it could read the stored cookies. This is an accepted
risk: it is pinned to an exact version with hashes (`tools/gallery-dl.txt`), installed in its own
root-owned environment, and started with a clean environment and a config generated per run.

### Updating yt-dlp

Platforms change often. Rebuild or pull a newer image to get a newer yt-dlp. As a stopgap,
`OPENTAKEZ_UPDATE_YTDLP=true` installs the latest release on every start; that skips the
pinned, reviewed version, so leave it off unless something is broken.

## Development

Requirements: Python 3.12+ with [uv](https://docs.astral.sh/uv/), Node 24 with pnpm, ffmpeg, and
[Deno](https://deno.com) (yt-dlp needs a JavaScript runtime for YouTube).

```sh
# Backend on :8000
cd backend
uv sync
uv run uvicorn app.main:app --reload

# PWA on :5173 (proxies /api to :8000, or set OPENTAKEZ_API=http://localhost:8010)
cd web
pnpm install
pnpm dev
```

For local development without a token, start the backend with `OPENTAKEZ_ALLOW_NO_TOKEN=true`.

Checks:

```sh
cd backend && uv run ruff check . && uv run ruff format --check . && uv run pytest
cd web && pnpm lint && pnpm typecheck && pnpm test && pnpm build
cd web && pnpm test:e2e   # Playwright, mocked API (first: pnpm exec playwright install chromium)

# Real downloads from YouTube and SoundCloud (needs network)
cd backend && uv run pytest -m slow
```

## API

All endpoints except `/api/health` need the token (unless `OPENTAKEZ_ALLOW_NO_TOKEN=true`), either as
`Authorization: Bearer <token>` or as `?token=<token>`.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/health` | Version, supported platforms, whether a token is required |
| POST | `/api/resolve` | `{"url"}` → title, thumbnail, duration, download options |
| POST | `/api/jobs` | `{"url", "option"}` → start a download job |
| GET | `/api/cookies` | Which platforms have cookies uploaded (never their content) |
| PUT | `/api/cookies/{platform}` | Upload a `cookies.txt` as the raw `text/plain` body |
| DELETE | `/api/cookies/{platform}` | Remove the cookies of a platform |
| GET | `/api/jobs/{id}` | Job status and progress |
| GET | `/api/jobs/{id}/events` | Server-sent events with the job state until it finishes |
| GET | `/api/jobs/{id}/file` | The downloaded file |
| DELETE | `/api/jobs/{id}` | Delete a finished job and its file |

`url` may be shared text that contains a link. Options: `video-best`, `video-1080`, `video-720`,
`audio-mp3`, `audio-m4a`.

## Project layout

```
backend/   FastAPI app: API, download jobs, providers (yt-dlp, Spotify)
web/       React + Vite PWA (design contract: docs/design/pwa-contract.md)
android/   Native Android app (planned)
```

## License

[GPL-3.0](LICENSE). Read the [DISCLAIMER](DISCLAIMER.md) before use.
