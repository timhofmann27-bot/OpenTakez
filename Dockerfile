# --- Build the PWA ---
FROM node:24-slim AS web
RUN npm install -g pnpm@11.20.0
WORKDIR /web
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY web/ ./
RUN pnpm build

# --- Runtime: FastAPI serves the API and the built PWA ---
FROM python:3.12-slim
COPY --from=ghcr.io/astral-sh/uv:0.12.3 /uv /usr/local/bin/uv
# yt-dlp needs a JavaScript runtime for YouTube.
COPY --from=denoland/deno:bin /deno /usr/local/bin/deno
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/app/.venv \
    PATH=/app/.venv/bin:$PATH \
    OPENTAKEZ_WEB_DIR=/app/web/dist \
    OPENTAKEZ_DATA_DIR=/data

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
