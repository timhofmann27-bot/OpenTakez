# --- Build the PWA ---
# The bundle is platform-independent: build it natively on the build machine, even when the
# runtime image is cross-built for another architecture (linux/arm64 under QEMU is slow).
FROM --platform=$BUILDPLATFORM node:25-slim AS web
RUN npm install -g pnpm@11.20.0
WORKDIR /web
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY web/ ./
RUN pnpm build

# --- Runtime: FastAPI serves the API and the built PWA ---
FROM python:3.12-slim
LABEL org.opencontainers.image.title="OpenTakez" \
      org.opencontainers.image.description="Self-hosted media downloader (yt-dlp, gallery-dl) with an installable PWA" \
      org.opencontainers.image.source="https://github.com/timhofmann27-bot/OpenTakez" \
      org.opencontainers.image.licenses="GPL-3.0-or-later"
COPY --from=ghcr.io/astral-sh/uv:0.12.3 /uv /usr/local/bin/uv
# yt-dlp needs a JavaScript runtime for YouTube.
COPY --from=denoland/deno:bin /deno /usr/local/bin/deno
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# gallery-dl (GPL-2.0-only) lives in its own root-owned venv, pinned with hashes: it stays a
# separate program (never imported), and neither the app user nor the yt-dlp updater can touch it.
COPY tools/gallery-dl.txt /tmp/gallery-dl.txt
RUN uv venv --python /usr/local/bin/python /opt/gallery-dl \
    && uv pip install --python /opt/gallery-dl/bin/python --require-hashes --no-deps \
        -r /tmp/gallery-dl.txt \
    && ln -s /opt/gallery-dl/bin/gallery-dl /usr/local/bin/gallery-dl \
    && rm /tmp/gallery-dl.txt

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/app/.venv \
    PATH=/app/.venv/bin:$PATH \
    OPENTAKEZ_WEB_DIR=/app/web/dist \
    # Absolute: the app-writable /app/.venv/bin is first on PATH and must not shadow gallery-dl.
    OPENTAKEZ_GALLERY_DL_BIN=/opt/gallery-dl/bin/gallery-dl \
    OPENTAKEZ_DATA_DIR=/data \
    # The container runs with a read-only root filesystem: caches (yt-dlp, Deno) go to the /tmp
    # tmpfs, and bytecode for the app code is not written at runtime.
    XDG_CACHE_HOME=/tmp/.cache \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /app/backend
COPY backend/pyproject.toml backend/uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY backend/ ./
COPY --from=web /web/dist /app/web/dist

# The venv is owned by the app user so yt-dlp can update itself at startup.
RUN useradd --system --uid 1000 opentakez \
    && mkdir /data \
    && chown -R opentakez /data /app/.venv
USER opentakez

VOLUME /data
EXPOSE 8000
ENTRYPOINT ["./docker-entrypoint.sh"]
