# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-10-22

First stable release: one self-hosted Docker image with an installable PWA.

### Platforms and downloads

- YouTube and SoundCloud via [yt-dlp](https://github.com/yt-dlp/yt-dlp), with Deno as the
  JavaScript runtime YouTube needs.
- TikTok (including `vm.tiktok.com` short links), Instagram, and Facebook. TikTok uses yt-dlp's
  browser impersonation (`curl-cffi`).
- Photo posts and carousels (Instagram, TikTok) via [gallery-dl](https://github.com/mikf/gallery-dl),
  run as a separate command-line program from its own hash-pinned, root-owned environment.
- Formats: best video, 1080p, 720p, MP3, M4A, and original images.
- Playlists, sets, and carousels up to `OPENTAKEZ_MAX_PLAYLIST_ITEMS` items, with per-file
  downloads and a streaming ZIP of the whole job. Channels and profiles are refused.
- Login sessions per platform: upload a `cookies.txt` (filtered to the platform's domains,
  stored with mode 0600, never sent back or logged, copied per run).
- Limits for file size, job size, job duration, queued and concurrent jobs, and concurrent link
  checks. Downloads are deleted after `OPENTAKEZ_FILE_TTL_HOURS`.

### PWA

- Paste or share a link, preview it, pick a format, and download. Installable on Android, where
  it appears in the share sheet of other apps.
- Live progress over server-sent events with reconnects, history in IndexedDB, retry, and a
  file list with per-file save and ZIP.
- Settings for server URL, token, language (English, German), and cookie uploads.
- Accessible: contrast, focus handling, quiet progress announcements.

### Security

- Access token required by default; generated on first start and stored in the data volume.
- Download tickets: event streams and file links use short-lived, per-job tickets
  (`POST /api/jobs/{id}/ticket`, `?ticket=`) instead of the token. `?token=` is no longer
  accepted anywhere. Because a download link is now built on demand, the save controls are
  buttons rather than links, so the browser's "save link as" and "copy link address" no longer
  apply to them.
- Per-IP rate limits (`OPENTAKEZ_RATE_LIMIT_PER_MINUTE`) and a lockout after repeated failed
  logins (`OPENTAKEZ_AUTH_FAIL_LIMIT`). Responses are 429 with `Retry-After`.
- `OPENTAKEZ_ALLOWED_HOSTS` rejects unknown Host headers (DNS rebinding).
- URL allowlist against SSRF, thumbnails only from platform CDNs, CSP and security headers,
  token and ticket redaction in access logs, sanitized ZIP entry names.
- Container runs as a non-root user with a read-only root filesystem, a `/tmp` tmpfs, all
  capabilities dropped, and `no-new-privileges`.

### Operations

- Multi-architecture image (`linux/amd64`, `linux/arm64` for Raspberry Pi) published to
  `ghcr.io/timhofmann27-bot/opentakez` on every release tag.
- Dependabot keeps yt-dlp, gallery-dl, web dependencies, GitHub Actions, and base images current.
- Documentation for self-hosting (HTTPS with Caddy, Cloudflare Tunnel, backups, updating) and
  a roadmap.

[Unreleased]: https://github.com/timhofmann27-bot/OpenTakez/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/timhofmann27-bot/OpenTakez/releases/tag/v1.0.0
