# Termux gateway

Optional. The supported install is Docker. This page is a phone-side gateway for people who already use Termux. A Termux session on an arm64 phone reached `/api/health` and completed an Instagram video download through the browser. CI does not run this path.

## What it is

A loopback OpenTakez server: `http://127.0.0.1:8000` in the phone's browser. yt-dlp uses Node when Deno is absent (Termux has Node, not Deno).

## What it is not

- Not a replacement for the Android app. The app accepts only `https://` server URLs (`ServerUrl.normalize`), so it will not talk to this process.
- Not a server for other devices. Binding is `127.0.0.1` on purpose. Publishing the port without HTTPS and a token other people cannot read is out of scope.
- Not kept alive by Android. The process dies when Termux is killed. `termux-wake-lock` and Termux:Boot are your problem, not the script's.

## Try it

In Termux, with storage permission if you want files outside the app:

```sh
pkg install git
git clone https://github.com/timhofmann27-bot/OpenTakez.git ~/opentakez
bash ~/opentakez/scripts/termux-gateway.sh
```

Open `http://127.0.0.1:8000` in the phone browser. The first start writes a token to `~/.opentakez/token`. The script prints nothing else about it; read that file.

The PWA is not in the git checkout (`web/dist` is gitignored). The script downloads a prebuilt tarball from the `pwa-main` prerelease when `web/dist/index.html` is missing. If that download fails, the API still answers `/api/health` and the browser UI is a 404.

Termux's Python is 3.14. `pip install pydantic-core` ignores the Android wheel and tries a Rust source build, which fails. The script downloads `pydantic_core-2.50.0` for this phone's ABI and installs that file into the venv, then `pydantic==2.14.0`. It installs the backend with `--no-deps` and lists the runtime packages itself, so `uvicorn[standard]` (watchfiles, uvloop, httptools) is never pulled. That pin is Termux-only; the Docker image is unchanged.

Photo posts need `gallery-dl`. If that pip install fails, video and audio still work.

## Report back

If you run this, note: Termux version, `python --version`, `node --version`, whether `/api/health` returns `yt_dlp` and `gallery_dl`, and whether a YouTube link resolves. Do not paste the token.
