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
cp .env.example .env   # set OPENTAKEZ_TOKEN if the server is reachable from the internet
docker compose up -d --build
```

Open http://localhost:8000. To install the PWA on a phone the instance must be served over
HTTPS (for example behind Caddy, Traefik, or a Cloudflare Tunnel).

## Development

Requirements: Python 3.12+ with [uv](https://docs.astral.sh/uv/), Node 24 with pnpm, ffmpeg, and
[Deno](https://deno.com) (yt-dlp needs a JavaScript runtime for YouTube).

```sh
# Backend on :8000
cd backend
uv sync
uv run uvicorn app.main:app --reload

# PWA on :5173 (proxies /api to :8000)
cd web
pnpm install
pnpm dev
```

Checks:

```sh
cd backend && uv run ruff check . && uv run ruff format --check . && uv run pytest
cd web && pnpm lint && pnpm build

# Real downloads from YouTube and SoundCloud (needs network)
cd backend && uv run pytest -m slow
```

## API

All endpoints except `/api/health` need the token when `OPENTAKEZ_TOKEN` is set, either as
`Authorization: Bearer <token>` or as `?token=<token>`.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/health` | Version, supported platforms, whether a token is required |
| POST | `/api/resolve` | `{"url"}` → title, thumbnail, duration, download options |
| POST | `/api/jobs` | `{"url", "option"}` → start a download job |
| GET | `/api/jobs/{id}` | Job status and progress |
| GET | `/api/jobs/{id}/events` | Server-sent events with the job state until it finishes |
| GET | `/api/jobs/{id}/file` | The downloaded file |
| DELETE | `/api/jobs/{id}` | Delete a finished job and its file |

`url` may be shared text that contains a link. Options: `video-best`, `video-1080`, `video-720`,
`audio-mp3`, `audio-m4a`.

## Project layout

```
backend/   FastAPI app: API, download jobs, providers (yt-dlp, Spotify)
web/       React + Vite PWA
android/   Native Android app (planned)
```

## License

[GPL-3.0](LICENSE). Read the [DISCLAIMER](DISCLAIMER.md) before use.
