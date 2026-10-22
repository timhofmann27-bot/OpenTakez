# Termux gateway

Optional. The supported install is Docker. This page is a phone-side gateway for people who already use Termux. It was not verified on a device in CI.

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

The PWA is not in the git checkout (`web/dist` is gitignored). Without a copied build the API still answers `/api/health` and the browser UI is a 404. Copy a `web/dist` from a machine that ran `pnpm build`, or use the API only.

Photo posts need `gallery-dl`. If that pip install fails, video and audio still work.

## Report back

If you run this, note: Termux version, `python --version`, `node --version`, whether `/api/health` returns `yt_dlp` and `gallery_dl`, and whether a YouTube link resolves. Do not paste the token.
