#!/bin/sh
set -e

# Off by default: an unpinned install at runtime bypasses uv.lock. Prefer pulling a newer
# image; enable this only if you need yt-dlp fixes before the next image release.
if [ "${OPENTAKEZ_UPDATE_YTDLP:-false}" = "true" ]; then
    echo "Updating yt-dlp ..."
    uv pip install --quiet --no-cache --upgrade "yt-dlp[default]" --python /app/.venv/bin/python \
        || echo "yt-dlp update failed, continuing with bundled version"
fi

# Trust X-Forwarded-* only from the reverse proxy (set to its IP, or "*" if it's the only way in).
exec uvicorn app.main:app --host 0.0.0.0 --port 8000 --proxy-headers \
    --forwarded-allow-ips="${OPENTAKEZ_FORWARDED_ALLOW_IPS:-127.0.0.1}"
