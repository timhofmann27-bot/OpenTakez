# Roadmap

What OpenTakez does today, what comes next, and what is planned. Plans change; issues and pull
requests are welcome.

## Done

| Milestone | Summary |
| --- | --- |
| M0 | Project skeleton: FastAPI backend serving a React + Vite PWA, one Docker image with ffmpeg and Deno, CI. |
| M1 | Backend core: YouTube and SoundCloud via yt-dlp, URL allowlist, in-memory jobs with progress events, token auth. |
| M2 | PWA: paste or share a link, format picker, live progress, history, settings, Android share target; security hardening (generated token, limits, CSP, log redaction). |
| M3 | TikTok, Instagram, Facebook; photo posts via gallery-dl; playlists and carousels with ZIP; cookie upload for logged-in content. |
| M5 | Release 1.0: download tickets instead of the token in URLs, rate limits and login lockout, allowed hosts, read-only container, multi-arch images on GHCR (amd64, arm64), Dependabot, self-hosting docs. |

See the [changelog](../CHANGELOG.md) for details.

## In progress: M6, native Android app

A native app that downloads on the phone itself, without a server. Planned as release 1.1.0;
this entry moves to *Done* when it ships. See the [Android guide](android.md).

- Kotlin and Jetpack Compose, using [youtubedl-android](https://github.com/yausername/youtubedl-android)
  (yt-dlp, ffmpeg, and Python bundled for Android), so downloads run on the device.
- Same flow as the PWA: share a link from any app, pick a format, the file lands in the
  device's Downloads, Music, or Pictures folder.
- Optional: send a link to your self-hosted server instead of downloading locally. Photo posts,
  carousels, and anything that needs a login go through the server.
- Signed per-ABI APKs on GitHub Releases, installable and updatable with
  [Obtainium](https://obtainium.imranr.dev). Not on Google Play: its policies don't allow apps
  that download from YouTube.
- yt-dlp updates inside the app, because platform fixes can't wait for an app release.

## Later: F-Droid

An [F-Droid](https://f-droid.org) listing, once the app has settled. It needs a reproducible
build setup and a review of the bundled binaries (yt-dlp, Python, ffmpeg).

## Later: paid hosting

A paid offer for a private instance only. The app stays free. No public instance, no paid
platform download. See [hosting-offer.md](hosting-offer.md).

- Setup: 29 € once.
- Hosting: 5 € per month.
- Not sold: paid downloads from YouTube, TikTok, Instagram, Facebook, or SoundCloud.

## Later: Termux gateway

An optional loopback server for people who already run Termux. See [termux.md](termux.md).
The Android app does not connect to it (https only). Not verified on a device.

## Planned: Spotify

Spotify links (tracks, albums, playlists) will be supported **without taking any audio from
Spotify**:

1. Read the public metadata (title, artists, album, duration, cover) from Spotify's public embed
   pages (`https://open.spotify.com/embed/track/...`), which need no account or API key.
2. Search for the matching recording on YouTube Music and pick the best match by title,
   artists, and duration.
3. Download that recording and tag it with the Spotify metadata and cover.

Optionally, instance owners can add their own Spotify API credentials for more reliable
metadata (for example long playlists). This stays optional on purpose.

**Why not API-first.** Since February 2026, Spotify's Web API is much harder to use for a
self-hosted project
([Spotify's migration guide](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide)):

- Apps in development mode require the app owner to have Spotify Premium.
- Development mode is limited to one client ID and five users.
- Several metadata endpoints were removed, and client-credentials access to metadata is being
  phased out.

Requiring every self-hoster to register an app, pay for Premium, and accept those limits would
make Spotify support useless for most people, so public embed metadata is the default.

**No DRM circumvention, ever.** OpenTakez will never decrypt or record Spotify streams or work
around any other technical protection measure. See the [DISCLAIMER](../DISCLAIMER.md).

## Other ideas

Not scheduled; contributions welcome.

- Cancel running jobs from the PWA.
- Per-user tokens (with names, so they can be revoked one by one) instead of one shared token.
- Notifications when a download finishes (Web Push).
- Persist jobs across restarts.
- More platforms that yt-dlp or gallery-dl already support (X/Twitter, Reddit, Vimeo, Bandcamp).
- Subtitles and chapter metadata.
