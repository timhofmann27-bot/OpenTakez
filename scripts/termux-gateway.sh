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

pkg install -y python ffmpeg nodejs curl
# Termux's Python is 3.14. PyPI has no Android wheel for pydantic-core, and
# pip ignores the third-party index wheel (it then tries a Rust source build,
# which this phone cannot do). Download the wheel and install the file.
# /tmp is often not writable (Termux noexec / EACCES). $PREFIX/tmp is.
ARCH=$(uname -m)
case "$ARCH" in
    aarch64) ABI_TAG="arm64_v8a" ;;
    arm | armv7l | armv8l) ABI_TAG="armeabi_v7a" ;;
    x86_64) ABI_TAG="x86_64" ;;
    i686 | i386) ABI_TAG="x86" ;;
    *) echo "Unsupported arch: $ARCH" >&2; exit 1 ;;
esac
PY_TAG=$(python -c 'import sys; print(f"cp{sys.version_info.major}{sys.version_info.minor}")')
WHEEL="pydantic_core-2.50.0-${PY_TAG}-${PY_TAG}-android_24_${ABI_TAG}.whl"
WHEEL_URL="https://github.com/Eutalix/android-pydantic-core/releases/download/v2.50.0/${WHEEL}"
WHEEL_DIR="${PREFIX:-$HOME}/tmp"
mkdir -p "$WHEEL_DIR"
WHEEL_PATH="${WHEEL_DIR}/${WHEEL}"
curl -fL --retry 3 -o "$WHEEL_PATH" "$WHEEL_URL" \
    || { echo "pydantic-core wheel download failed. Stop; do not build from source." >&2; exit 1; }
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
# Install the wheel into this venv. A system pip install would not be visible here.
pip install "$WHEEL_PATH" \
    || { echo "pydantic-core wheel install failed. Stop; do not build from source." >&2; exit 1; }
rm -f "$WHEEL_PATH"
pip install 'pydantic==2.14.0'
# No extras: uvicorn[standard] pulls watchfiles, uvloop and httptools, which
# have no Android wheels. fastapi[standard] is not required either.
pip install -e . --no-deps
pip install \
    'fastapi>=0.115' \
    'uvicorn>=0.32' \
    'pydantic-settings>=2.6' \
    'yt-dlp[default,curl-cffi]'

if [[ ! -f "$ROOT/web/dist/index.html" ]]; then
    echo "No built PWA at web/dist. The API still runs; the browser UI will 404 until you copy a build." >&2
fi

mkdir -p "$DATA"
export OPENTAKEZ_DATA_DIR="$DATA"
export OPENTAKEZ_WEB_DIR="$ROOT/web/dist"
export OPENTAKEZ_GALLERY_DL_BIN="${OPENTAKEZ_GALLERY_DL_BIN:-gallery-dl}"
# Loopback only. Do not publish this port. A token is still generated on first start.
exec uvicorn app.main:app --host 127.0.0.1 --port "$PORT"
