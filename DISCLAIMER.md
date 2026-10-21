# Disclaimer

OpenTakez is a tool. You are responsible for how you use it.

- Only download content you own, content in the public domain, or content you are otherwise
  legally allowed to copy (for example a private copy where your local law permits it).
- Downloading may violate the terms of service of the platform you download from.
- OpenTakez does **not** circumvent DRM or any other technical protection measure. Spotify
  support works by reading public track metadata (title, artist, cover) and searching for a
  matching publicly available recording elsewhere. No audio is taken from Spotify.
- The maintainers do not operate a public instance and do not host, store, or distribute any
  downloaded content. Every instance is run by the person who deployed it.
- Images (for example Instagram and TikTok photo posts) are fetched with
  [gallery-dl](https://github.com/mikf/gallery-dl), which is licensed GPL-2.0-only. It is shipped
  in the Docker image as a separate program that OpenTakez starts through its command line; it is
  never imported or linked into OpenTakez.
- Cookies you upload are login sessions. They stay on your server (mode 0600 in the data volume),
  but using an account with automated tools may violate the platform's terms and can get the
  account restricted. Use a secondary account, and only your own.
