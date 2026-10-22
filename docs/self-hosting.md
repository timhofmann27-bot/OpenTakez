# Self-hosting OpenTakez

OpenTakez is one Docker image: a FastAPI server that serves the API and the PWA, with yt-dlp,
ffmpeg, Deno, and gallery-dl inside. You run one container per instance. Nobody operates a
public instance; read the [DISCLAIMER](../DISCLAIMER.md) first.

- [Requirements](#requirements)
- [Quick start](#quick-start)
- [The access token](#the-access-token)
- [HTTPS with Caddy](#https-with-caddy)
- [Cloudflare Tunnel](#cloudflare-tunnel)
- [Allowed hosts](#allowed-hosts)
- [Configuration](#configuration)
- [Logins (cookies)](#logins-cookies)
- [Backups](#backups)
- [Updating](#updating)
- [Raspberry Pi and other arm64 machines](#raspberry-pi-and-other-arm64-machines)
- [Android: install the PWA and share to it](#android-install-the-pwa-and-share-to-it)
- [Troubleshooting](#troubleshooting)

## Requirements

- Docker Engine 24+ with the Compose plugin (`docker compose`, v2.24 or newer for the Caddy
  example below).
- A 64-bit Linux machine: `linux/amd64` or `linux/arm64` (Raspberry Pi 4/5 with a 64-bit OS).
- About 1 GB of RAM and enough disk for the downloads you keep at once. Files are deleted
  after `OPENTAKEZ_FILE_TTL_HOURS` (6 hours by default).
- For phones: a domain name and HTTPS. Browsers only install PWAs, and only share to them,
  over HTTPS (or on `localhost`).

## Quick start

```sh
git clone https://github.com/timhofmann27-bot/OpenTakez.git
cd OpenTakez
cp .env.example .env
docker compose pull
docker compose up -d
```

You only need `docker-compose.yml` and `.env.example` from the repository; cloning is the
easiest way to get both. Open `http://<server>:8000` and enter the token (next section).

`docker-compose.yml` names the published image `ghcr.io/timhofmann27-bot/opentakez:latest` and
also keeps `build: .`:

| Command | What it does |
| --- | --- |
| `docker compose pull` | Downloads the published image for your architecture. |
| `docker compose build` | Builds the image from your checkout and tags it with the same name, replacing the pulled one locally. |
| `docker compose up -d` | Starts the container with whichever image is present; pulls it if there is none. |

Use `pull` to run releases, `build` to run your own changes. To pin a release, replace `latest`
with a version such as `1.0.0` (or `1.0` for patch updates only).

The container runs as a non-root user with a read-only root filesystem, a 512 MB `/tmp` tmpfs,
all Linux capabilities dropped, and `no-new-privileges`. The only persistent, writable place is
the `opentakez-data` volume at `/data`.

## The access token

Every API call needs a token. If `OPENTAKEZ_TOKEN` is empty, one is generated on the first
start, stored in `/data/token`, and printed to the log:

```sh
docker compose logs opentakez | grep -i token
```

To choose your own, set `OPENTAKEZ_TOKEN` in `.env` (use a long random string, for example
`openssl rand -base64 32`) and run `docker compose up -d`. Enter the token once in the PWA; it
is kept on that device.

The token is only sent in the `Authorization` header. Progress streams and file links use
short-lived download tickets instead, so the token never appears in URLs, browser history, or
proxy logs.

`OPENTAKEZ_ALLOW_NO_TOKEN=true` turns authentication off. Only use it for an instance that
nobody else can reach.

## HTTPS with Caddy

[Caddy](https://caddyserver.com) gets and renews Let's Encrypt certificates on its own. You
need a DNS record for your host name pointing to the server, and ports 80 and 443 open.

Next to `docker-compose.yml`, create `Caddyfile`:

```caddyfile
opentakez.example.com {
	reverse_proxy opentakez:8000
}
```

Caddy sets `X-Forwarded-For` and `X-Forwarded-Proto` and streams server-sent events without
buffering, so nothing else is needed.

Create `compose.caddy.yml`, which runs Caddy on a shared network and stops publishing port 8000
directly:

```yaml
services:
  opentakez:
    # Only Caddy is reachable from outside.
    ports: !reset []
    networks: [proxy]

  caddy:
    image: caddy:2.11-alpine
    container_name: caddy
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
      - "443:443/udp"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy-data:/data
      - caddy-config:/config
    networks: [proxy]

networks:
  proxy:
    ipam:
      config:
        - subnet: 172.30.0.0/24

volumes:
  caddy-data:
  caddy-config:
```

In `.env`:

```sh
OPENTAKEZ_FORWARDED_ALLOW_IPS=172.30.0.0/24
OPENTAKEZ_ALLOWED_HOSTS=["opentakez.example.com"]
```

Start both:

```sh
docker compose -f docker-compose.yml -f compose.caddy.yml up -d
```

**Why `OPENTAKEZ_FORWARDED_ALLOW_IPS` must match the network.** OpenTakez only believes the
`X-Forwarded-*` headers of the addresses listed there. Requests from Caddy come from Caddy's
address on the `proxy` network, not from `127.0.0.1`, so with the default the server would
ignore the headers: every client would appear to have Caddy's IP (one shared rate limit, and
one failed login lockout for everyone), and the server wouldn't know the request came over
HTTPS. The fixed subnet keeps the address predictable; any address inside it is a container
on that network. If the subnet collides with one of your networks, pick another private range
and change both places.

Caddy running directly on the host (not in Docker) talks to the published port from
`127.0.0.1`, which is the default, so the default `.env` works. Use
`reverse_proxy 127.0.0.1:8000` and consider binding the port to localhost:
`OPENTAKEZ_PORT=127.0.0.1:8000`.

Never set `OPENTAKEZ_FORWARDED_ALLOW_IPS=*` while port 8000 is reachable from outside: anyone
could then fake their IP and dodge the rate limits.

Traefik, nginx, and other proxies work the same way: proxy to port 8000, pass `X-Forwarded-For`
and `X-Forwarded-Proto`, disable response buffering for `/api/jobs/*/events`, and trust the
proxy's address.

## Cloudflare Tunnel

A [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)
gives you HTTPS without opening ports. Run `cloudflared` as a container on the same network as
OpenTakez (like Caddy above), point the tunnel's public hostname to `http://opentakez:8000`, and
set `OPENTAKEZ_FORWARDED_ALLOW_IPS` to that network and `OPENTAKEZ_ALLOWED_HOSTS` to the public
hostname. Notes:

- All downloads pass through Cloudflare. Large video files count against their terms for your
  plan; check them before you rely on this.
- Cloudflare Access (a login page in front of the tunnel) also blocks the PWA's background
  requests unless the device has a valid Access session. The OpenTakez token is enough on its
  own.

## Allowed hosts

`OPENTAKEZ_ALLOWED_HOSTS` is a JSON list of host names the server answers to; other `Host`
headers get `400`. This protects against DNS rebinding, where a malicious website makes your
browser talk to a server on your network under a host name the attacker controls. The default
`["*"]` accepts every host. Once the instance has a public name, set it:

```sh
OPENTAKEZ_ALLOWED_HOSTS=["opentakez.example.com"]
```

`/api/health` answers any `Host`, so the container's health check keeps working. Add
`localhost` or a LAN address if you open the instance that way.

## Configuration

Set these in `.env` (see [`.env.example`](../.env.example)) and run `docker compose up -d`
after changes. Lists are JSON.

| Variable | Default | Meaning |
| --- | --- | --- |
| `OPENTAKEZ_TOKEN` | empty (generated) | Access token. Empty: generated on first start and stored in `/data/token`. |
| `OPENTAKEZ_ALLOW_NO_TOKEN` | `false` | Run without a token. Only for instances nobody else can reach. |
| `OPENTAKEZ_PORT` | `8000` | Host port (Compose only). `127.0.0.1:8000` publishes it on localhost only. |
| `OPENTAKEZ_FORWARDED_ALLOW_IPS` | `127.0.0.1` | Addresses or networks (CIDR) of reverse proxies whose `X-Forwarded-*` headers are trusted. |
| `OPENTAKEZ_ALLOWED_HOSTS` | `["*"]` | Accepted `Host` headers. |
| `OPENTAKEZ_CORS_ORIGINS` | `["http://localhost:5173"]` | Origins of PWAs hosted elsewhere that may call this server. |
| `OPENTAKEZ_TICKET_TTL_SECONDS` | `600` | Lifetime of download tickets used by progress streams and file links. |
| `OPENTAKEZ_RATE_LIMIT_PER_MINUTE` | `30` | Per client IP: resolve, start job, upload cookies, and ticket requests per minute. |
| `OPENTAKEZ_AUTH_FAIL_LIMIT` | `10` | Failed logins (wrong token or ticket) within 10 minutes before that IP is locked out for 10 minutes. |
| `OPENTAKEZ_FILE_TTL_HOURS` | `6` | Hours before downloaded files are deleted. |
| `OPENTAKEZ_MAX_CONCURRENT_JOBS` | `2` | Downloads running at the same time. |
| `OPENTAKEZ_MAX_QUEUED_JOBS` | `20` | Queued plus running jobs before new ones are refused. |
| `OPENTAKEZ_MAX_CONCURRENT_RESOLVES` | `4` | Link checks running at the same time. |
| `OPENTAKEZ_MAX_FILE_MB` | `2048` | Largest single file. |
| `OPENTAKEZ_MAX_PLAYLIST_ITEMS` | `50` | Items taken from a playlist or carousel (1–200); more are cut off. |
| `OPENTAKEZ_MAX_JOB_MB` | `4096` | Largest total size of one job. |
| `OPENTAKEZ_JOB_TIMEOUT_SECONDS` | `3600` | Seconds before a job is aborted. |
| `OPENTAKEZ_COOKIE_MAX_KB` | `256` | Largest `cookies.txt` upload. |
| `OPENTAKEZ_GALLERY_DL_TIMEOUT` | `300` | Seconds before a gallery-dl (image) run is aborted. |
| `OPENTAKEZ_UPDATE_YTDLP` | `false` | Install the newest yt-dlp on every start. Needs `read_only: false`; see [Updating](#updating). |

Set by the image, normally left alone: `OPENTAKEZ_DATA_DIR` (`/data`), `OPENTAKEZ_WEB_DIR`
(`/app/web/dist`), `OPENTAKEZ_GALLERY_DL_BIN` (`/opt/gallery-dl/bin/gallery-dl`).

## Logins (cookies)

Some Instagram, TikTok, and Facebook content needs a logged-in session. You can upload a
`cookies.txt` per platform in Settings → Logins. Read
[Logins (cookies) in the README](../README.md#logins-cookies) first: use a secondary account,
never your main one.

## Backups

Everything worth keeping is in the `opentakez-data` volume:

- `/data/token`: the generated token (not needed if you set `OPENTAKEZ_TOKEN`).
- `/data/cookies/`: uploaded login sessions.

Downloads in the volume are temporary and are not worth backing up. Jobs live in memory and are
gone after a restart.

```sh
# Back up token and cookies (everything except the temporary downloads in jobs/)
docker run --rm -v opentakez_opentakez-data:/data:ro -v "$PWD":/backup busybox \
  tar czf /backup/opentakez-data.tgz -C /data --exclude ./jobs .

# Restore
docker run --rm -v opentakez_opentakez-data:/data -v "$PWD":/backup busybox \
  sh -c 'tar xzf /backup/opentakez-data.tgz -C /data && chown -R 1000 /data'
```

The volume name is `<project>_opentakez-data`; the project is the directory name unless you
set one (`docker volume ls`). The backup contains login sessions: store it like a password.

## Updating

```sh
docker compose pull
docker compose up -d
docker image prune -f
```

Platforms change often and break downloads; fixes arrive in new yt-dlp and gallery-dl
releases. Dependabot opens pull requests for them every week, and each release publishes a new
image, so updating the image is the way to get them. Watch the repository's releases (Watch →
Custom → Releases) to be notified.

**Why the runtime updater is off.** `OPENTAKEZ_UPDATE_YTDLP=true` installs the newest yt-dlp on
every start. That skips the pinned, reviewed versions in `uv.lock`, and it needs a writable
image filesystem: set `read_only: false` in `docker-compose.yml` as well, or the update fails
and the bundled version is used. Use it only as a stopgap when something broke and no new
image is out yet.

## Raspberry Pi and other arm64 machines

The image is published for `linux/arm64`, and `docker compose pull` picks it automatically.
You need a 64-bit OS (Raspberry Pi OS 64-bit, Ubuntu Server); 32-bit ARM is not supported.
A Raspberry Pi 4 or 5 is enough; converting to MP3 and merging high-resolution video are
CPU-bound and slower than on a PC, so keep `OPENTAKEZ_MAX_CONCURRENT_JOBS` at 1–2. Put the
data volume on an SSD rather than the SD card if you download a lot.

Building on the Pi itself works too (`docker compose build`) but takes a while.

## Android: install the PWA and share to it

1. Open `https://opentakez.example.com` in Chrome and enter the token.
2. Menu (⋮) → *Install app* (or *Add to Home screen* → *Install*).
3. In YouTube, TikTok, Instagram, SoundCloud, or any app with a share button, tap *Share* and
   pick **OpenTakez**. The link opens in the app, ready to download.

If OpenTakez doesn't appear in the share sheet, make sure the site is served over HTTPS and was
installed (not just added as a bookmark), then reopen the share sheet; Android sometimes lists
new targets only after a restart of the sharing app.

A native Android app that downloads on the device is planned; see the [roadmap](roadmap.md).

## Troubleshooting

**TikTok, Instagram, or Facebook downloads suddenly fail.** The platform changed something.
Update the image (`docker compose pull && docker compose up -d`). If there is no new release
yet, check the [yt-dlp](https://github.com/yt-dlp/yt-dlp/issues) and
[gallery-dl](https://github.com/mikf/gallery-dl/issues) issue trackers, or use the runtime
updater as a stopgap (see [Updating](#updating)). If the error asks for a login, upload fresh
cookies.

**401 Unauthorized / the app asks for the token again.** The token in the app doesn't match the
server. Check `OPENTAKEZ_TOKEN` in `.env` or the generated one in the logs, and enter it again
in Settings. If you removed `/data/token`, a new token was generated.

**429 Too many requests.** The rate limit (`OPENTAKEZ_RATE_LIMIT_PER_MINUTE`) or the failed-login
lockout was hit; the app shows when to try again. If every user hits it at once behind a reverse
proxy, `OPENTAKEZ_FORWARDED_ALLOW_IPS` doesn't match the proxy, so all requests share the proxy's
IP (see [HTTPS with Caddy](#https-with-caddy)). The lockout ends after 10 minutes, or on restart.

**400 Invalid host header.** The host name you opened isn't in `OPENTAKEZ_ALLOWED_HOSTS`.

**Port 8000 is already in use.** Set another host port, e.g. `OPENTAKEZ_PORT=8080`, and run
`docker compose up -d`.

**`docker compose build` fails with "unexpected digest" or similar cache errors.** Some buildx
builders (docker-container driver) fail to load the result into Docker. Build with the default
builder: `BUILDX_BUILDER=default docker compose build`.

**The PWA can't be installed.** It must be served over HTTPS with a valid certificate (or opened
as `http://localhost`).

**Progress stays at 0 % behind a proxy.** The proxy buffers server-sent events. Caddy doesn't;
for nginx set `proxy_buffering off;` for `/api/jobs/`.
