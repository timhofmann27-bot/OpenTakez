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

Requirements: Python 3.12+ with [uv](https://docs.astral.sh/uv/), Node 24 with pnpm, ffmpeg.

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
```

## Project layout

```
backend/   FastAPI app: API, download jobs, providers (yt-dlp, Spotify)
web/       React + Vite PWA
android/   Native Android app (planned)
```

## License

[GPL-3.0](LICENSE). Read the [DISCLAIMER](DISCLAIMER.md) before use.
