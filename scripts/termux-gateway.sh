#!/data/data/com.termux/files/usr/bin/bash
# OpenTakez gateway for Termux. Not tested here; the maintainer runs it on a phone.
# The Android app accepts only https server URLs, so this process is for the browser
# on this phone (http://127.0.0.1:8000). Other devices need HTTPS or Tailscale.
set -euo pipefail

ROOT="${OPENTAKEZ_ROOT:-$HOME/opentakez}"
DATA="${OPENTAKEZ_DATA_DIR:-$HOME/.opentakez}"
PORT="${OPENTAKEZ_PORT:-8000}"

if ! command -v pkg >/dev/null 2>&1; then
    echo "This script is for Termux (pkg not found)." >&2
    exit 1
fi

pkg install -y python ffmpeg nodejs
# Termux's Python is 3.14. PyPI has no Android wheel for pydantic-core, and
# building it needs a Rust target Termux does not have. A third-party index
# publishes cp314 Android wheels; 2.50.0 matches pydantic 2.14.0.
# uvicorn[standard] pulls uvloop, httptools and watchfiles, which also lack
# Android wheels, so those extras are not installed.
pip install 'pydantic-core==2.50.0' \
    --extra-index-url https://eutalix.github.io/android-pydantic-core/pydantic-core/ \
    || { echo "pydantic-core Android wheel failed. Stop; do not retry a source build." >&2; exit 1; }
pip install 'pydantic==2.14.0'
# gallery-dl is a separate program (GPL-2.0-only). Photo posts need it; video does not.
pip install --upgrade 'gallery-dl==1.32.17' || echo "gallery-dl install failed; photo posts will not work"

if [[ ! -d "$ROOT/backend" ]]; then
    echo "Clone the repo first: git clone https://github.com/timhofmann27-bot/OpenTakez.git \"$ROOT\"" >&2
    exit 1
fi

cd "$ROOT/backend"
python -m venv .venv
# shellcheck disable=SC1091
source .venv/bin/activate
# No [standard]: uvloop, httptools and watchfiles have no Android wheels.
pip install -e . 'uvicorn>=0.32'

if [[ ! -f "$ROOT/web/dist/index.html" ]]; then
    echo "No built PWA at web/dist. The API still runs; the browser UI will 404 until you copy a build." >&2
fi

mkdir -p "$DATA"
export OPENTAKEZ_DATA_DIR="$DATA"
export OPENTAKEZ_WEB_DIR="$ROOT/web/dist"
export OPENTAKEZ_GALLERY_DL_BIN="${OPENTAKEZ_GALLERY_DL_BIN:-gallery-dl}"
# Loopback only. Do not publish this port. A token is still generated on first start.
exec uvicorn app.main:app --host 127.0.0.1 --port "$PORT"
