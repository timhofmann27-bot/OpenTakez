# OpenTakez for Android

A native Android app (Kotlin, Jetpack Compose). Share a link from any app, pick a format, and the
file lands in your Downloads, Music, or Pictures folder. It needs Android 8.0 (API 26) or newer.

Compared with the [PWA](self-hosting.md#android-install-the-pwa-and-share-to-it):

- It can download **on the phone itself**, with yt-dlp and ffmpeg bundled in the app. You don't
  need a server.
- It can also hand a link to **your own OpenTakez server** and fetch the result, which is the
  only way to get photo posts and carousels, and content that needs a login.
- It saves into the normal media folders through Android's MediaStore, so files show up in your
  gallery and music player.
- It updates yt-dlp inside the app, without waiting for an app release.

Read the [DISCLAIMER](../DISCLAIMER.md) before use.

## Install

### Obtainium (recommended)

[Obtainium](https://obtainium.imranr.dev) installs apps straight from GitHub Releases and tells
you about updates.

1. Install Obtainium.
2. *Add app*, and enter `https://github.com/timhofmann27-bot/OpenTakez`.
3. Under the app's settings, enable *Filter APKs by regular expression* and enter `arm64-v8a`.
   Releases contain one APK per CPU type, and the filter makes Obtainium pick the right one.
   (Use `armeabi-v7a` on an old 32-bit phone, `x86_64` on an emulator.)
4. Leave *Include prereleases* off, so you only get stable versions.
5. Tap *Add*, then *Install*. Android asks once to allow Obtainium to install apps.

### Manual download

1. Open the [latest release](https://github.com/timhofmann27-bot/OpenTakez/releases/latest) and
   download the APK for your phone and `SHA256SUMS`:
   - `OpenTakez-<version>-arm64-v8a.apk`: nearly every phone from 2017 on. Choose this if unsure.
   - `OpenTakez-<version>-armeabi-v7a.apk`: only old 32-bit devices.
   - `OpenTakez-<version>-x86_64.apk`: emulators and Chromebooks.
2. Check the file (on a computer, in the folder with both files):

   ```sh
   sha256sum -c --ignore-missing SHA256SUMS
   ```

3. Optionally check the signature. All releases are signed with the same key:

   ```sh
   apksigner verify --print-certs OpenTakez-<version>-arm64-v8a.apk
   ```

   The certificate SHA-256 digest must be `<fingerprint>`.
4. Open the APK on your phone and allow installing from that source when Android asks.

Updates over an existing install only work between APKs signed with the same key.

## First start

On the first start the app downloads the newest yt-dlp, so it needs an internet connection and a
moment (the bundled copy is too old for YouTube). After that, yt-dlp is updated in the
background (see [yt-dlp updates](#yt-dlp-updates)).

## Sharing links

In YouTube, TikTok, Instagram, SoundCloud, or any app with a share button, tap *Share* and pick
**OpenTakez**. The link opens in the app, ready for a format choice. You can also paste a link
into the app. Text that contains a link (a share message with a caption) works too.

## Where files are saved

Through MediaStore, in a folder named `OpenTakez`:

| Kind | Folder |
| --- | --- |
| Video | `Movies/OpenTakez` |
| Audio (MP3, M4A) | `Music/OpenTakez` |
| Photos | `Pictures/OpenTakez` |
| Other | `Download/OpenTakez` |


## Engine: device or server

Each download runs on one of two engines. In the preview you switch between **On device** and
**Via server** (the switch is disabled until a server is set up). The default comes from the
settings; photo posts always default to the server.

- **Device**: yt-dlp runs in the app. Private, needs no server, and the traffic comes from your
  phone's connection. Supports videos, audio, and playlists.
- **Your server**: the app sends the link to your [self-hosted OpenTakez](self-hosting.md) and
  downloads the finished files from it.

Photo posts and carousels (Instagram, TikTok) need the server, because they are downloaded with
gallery-dl, which is not part of the app. The same goes for anything that needs a login, because
the app doesn't keep cookies (see [known limits](#known-limits)).

## Pair with your server

1. Set up the server with HTTPS as described in [self-hosting](self-hosting.md). The app
   refuses plain `http://` addresses.
2. In the app, open *Settings* and enter the server URL (for example
   `https://opentakez.example.com`) and the access token.
3. Tap *Test connection* to check that the server answers and accepts the token.

The token is stored encrypted with a key in the Android Keystore. It is only ever sent in an
`Authorization` header, never in a URL, and Android backups are disabled so it can't leave the
phone through a backup.

## Permissions

| Permission | Why |
| --- | --- |
| Internet | Download from platforms and talk to your server. |
| Notifications (Android 13+) | Show progress of running downloads. Asked on your first download; downloads also run without it, you just won't see progress in the background. |
| Foreground service (data sync) | Keep a long download alive while the app is in the background. |
| Storage (Android 8 and 9 only) | Write into the public media folders. Newer versions need no storage permission. |

There is no contacts, location, or account permission.

## yt-dlp updates

Platforms change often, and fixes ship in new yt-dlp releases. The app checks for a new yt-dlp
on a weekly schedule and on demand (*Settings*, *Update*). A fresh install updates before the
first download. If YouTube or TikTok suddenly stop
working, update yt-dlp first; you rarely need a new app version.

## Known limits

- **No cookies on the device.** Content that needs a login (private or age-restricted posts)
  goes through your server, where you can [upload cookies](self-hosting.md#logins-cookies).
- **Facebook is flaky.** Facebook changes its pages often and rate-limits aggressively. Expect
  failures that fix themselves after a yt-dlp update.
- **Android 15 limits long background transfers.** Foreground services of the *data sync* type
  get about six hours per 24 hours. Normal downloads are far shorter, but a very large playlist
  can be interrupted; the job is marked failed and *Retry* starts it again.
- **Battery savers can kill downloads.** Some manufacturers (Xiaomi, Huawei, OnePlus, Samsung and
  others) stop background apps aggressively. If downloads stop when the screen is off, exclude
  OpenTakez from battery optimization. [dontkillmyapp.com](https://dontkillmyapp.com) has steps
  for each brand.
- Cleartext HTTP servers (for example on a LAN) are not supported. Use HTTPS.

## Build from source

You need JDK 21 and the Android SDK with platform `android-37.0` (compile SDK 37). The build does
not work with newer or older JDKs, so if your default `java` is something else, point
`JAVA_HOME` at a 21:

```sh
cd android
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # adjust to your system
echo "sdk.dir=$HOME/Android/Sdk" > local.properties    # where your Android SDK lives
sdkmanager "platforms;android-37.0"
./gradlew assembleDebug
```

The debug APK is in `android/app/build/outputs/apk/debug/` (it uses the application ID
`io.github.timhofmann27.opentakez.debug`, so it installs next to the release app). Run `./gradlew lint testDebugUnitTest` for the checks CI runs. The app
version comes from `opentakez.version` in `android/gradle.properties`.

A release build (`./gradlew assembleRelease`) is only signed when `ANDROID_KEYSTORE_FILE`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` are set; otherwise
the APK stays unsigned. Your own builds are signed with your own key, so they can't update the
official app and the other way around.

## How releases are signed

Pushing a version tag runs the [release workflow](../.github/workflows/release.yml). It builds
one APK per CPU type, signs them with the project's release key, verifies the signatures, and
attaches the APKs and `SHA256SUMS` to the GitHub Release. The key lives only in GitHub Actions
secrets (in the protected `release` environment) and in an offline backup of the maintainer.

**If the key is lost, nobody can ship updates to existing installs.** Android refuses an update
signed with a different key, so every user would have to uninstall and reinstall under a new
signature. That is why the key is backed up offline and never changes.

## Google Play

OpenTakez is intentionally not on Google Play, whose policies don't allow apps that download
from YouTube. Use GitHub Releases or Obtainium. An [F-Droid](https://f-droid.org) listing is a
later goal; see the [roadmap](roadmap.md).
