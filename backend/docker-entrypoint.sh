#!/bin/sh
set -e

# Platforms change often and yt-dlp ships fixes quickly, so update on every start.
if [ "${OPENTAKEZ_UPDATE_YTDLP:-true}" = "true" ]; then
    echo "Updating yt-dlp ..."
    uv pip install --quiet --no-cache --upgrade "yt-dlp[default]" --python /app/.venv/bin/python \
        || echo "yt-dlp update failed, continuing with bundled version"
fi

exec uvicorn app.main:app --host 0.0.0.0 --port 8000 --proxy-headers --forwarded-allow-ips='*'
