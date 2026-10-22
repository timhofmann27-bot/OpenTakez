# M6 contract (native Android app)

Shared by all M6 tracks. Change only with the integrator. The app lives in `android/` (one Gradle
module, `:app`); the server API is `docs/design/m5-contract.md` plus `backend/app/api/`.

## 0. Spike results (done, throwaway code removed)

Run on the API 36 x86_64 emulator, youtubedl-android 0.18.1, JDK 21.

| Spike | Result |
| --- | --- |
| S1 toolchain | **Pass.** AGP 9.4.1, Gradle 9.8.2, Kotlin 2.4.21, KSP 2.3.12, Room 2.8.5 build and run. `init()`, yt-dlp, and ffmpeg work: a SoundCloud track became a 3.4 MB MP3 on the device. **`compileSdk` is 37**, not 36: current AndroidX, Coil, and OkHttp refuse to build below it (the platform `android-37.0` must be installed). `targetSdk` stays 36. |
| S2 YouTube | **Pass, no fallback needed.** After the in-app update to yt-dlp 2026.10.21, `-F` on a YouTube video returns the full format list. yt-dlp finds QuickJS (`libqjs.so`, bundled by the library) on its own: `[jsc:quickjs] Solving JS challenges` appears even with no `--js-runtimes` flag. **The bundled yt-dlp is from 2025, so the first start must update it before the first YouTube download.** |
| S3 size and R8 | **Sizes:** release APKs are 52.0 MB (arm64-v8a), 45.6 MB (armeabi-v7a), 54.8 MB (x86_64). A universal APK is 190.6 MB, so it is **not** built. R8 runs, but instrumented tests on a minified build are not practical (the test APK is obfuscated differently from the app), so R8 compatibility is verified in step 2 by driving the minified, debug-signed app (`-Popentakez.debugSignRelease=true`) through a real download. Keep rules for `com.yausername.**` are in place. |
| S4 platforms | **TikTok and Instagram reels resolve on the device without cookies. Facebook fails with "Cannot parse data"**, exactly as on the server (yt-dlp issue, not device-specific). The app treats Facebook like the server does: try it, show the platform error. |

## 1. Screens and flows

Bottom navigation like the PWA: **Download**, **Downloads**, **History**, **Settings**.

- **Download (home):** link field, Paste, Clear, "Check link". A shared link skips this screen.
- **Preview:** thumbnail, title, uploader, duration, `media.truncated` note, option chips grouped
  Video / Audio only / Photos (use exactly `MediaInfo.options`), an engine switch
  **On device | Via server**, and the Download button.
- **Downloads:** running and failed jobs with phase, fraction, speed, ETA, Cancel, Retry, Remove.
- **History:** finished jobs. Open (ACTION_VIEW on the saved URI), Share, "Download again", Clear.
- **Settings:** server URL and token ("Test connection"), default engine, language (de, en),
  yt-dlp version with an **Update** button, About and licenses.

Flow: share a link (or paste) -> resolve -> Preview -> choose option and engine -> a WorkManager
job runs -> notification with progress -> files saved to shared storage -> History.

## 2. Routing

`RoutingPolicy.defaultEngine(url, serverConfigured)` in `core/`:

- Photo links go through the server because only gallery-dl can fetch them. Ported from
  `backend/app/providers/__init__.py`:
  - TikTok: host `tiktok.com` and path `^/@[^/]+/photo/\d+`.
  - Instagram: host `instagram.com` and path `^/(?:[^/]+/)?p/[^/]+/?$`.
- A photo link with a server configured defaults to `SERVER`; with none, resolve fails with
  `EngineError.NeedsServer`.
- Everything else defaults to `DEVICE`. The user can switch in Preview whenever a server is
  configured; without one the switch is disabled.
- Only the five platforms of `backend/app/urls.py` are accepted (youtube, tiktok, instagram,
  facebook, soundcloud, including `youtu.be`, `vm.`/`vt.tiktok.com`, `fb.watch`, `fb.com`). Host
  matching rejects ports and non-http(s) schemes. Anything else is `EngineError.Unsupported`.
- `UrlExtractor.extract(text)`: first `https?://[^\s<>"']+`, then strip trailing `.,;:!?)`
  (same as `backend/app/urls.py::extract_url`). No match: `EngineError.NoLink`.

## 3. Shared types (already in the repo, integrator-owned)

- `core/model/Models.kt`: `MediaKind`, `DownloadOption` (wire names are the server's), `MediaInfo`
  (same JSON as `POST /api/resolve`), `Engine`, `Phase`, `JobProgress`, `DownloadedFile`,
  `ServerConfig`.
- `core/model/EngineError.kt`: the sealed set of failures. Cancelling is a coroutine
  `CancellationException`, never an `EngineError`.
- `engine/MediaEngine.kt`: `suspend fun resolve(url)`,
  `fun download(url, option, workDir, existingJobId): Flow<EngineEvent>`, with
  `EngineEvent.Progress`, `EngineEvent.RemoteJobStarted(id)` for durable server jobs, and a final
  `EngineEvent.Done(files, skipped)`. `releaseRemoteJob(id)` frees server files after the caller
  has saved all files to shared storage; the device engine ignores both server-only operations.
- `storage/FileSaver.kt`: `suspend fun save(file, displayName): SavedFile`.

Both engines deliver plain files into a work directory the caller owns, so saving happens once.

## 4. Device engine (`engine/device/`)

Uses youtubedl-android. No cookies in v1.

- **Init** at app start on `Dispatchers.IO`: `YoutubeDL.init`, `FFmpeg.init`. Exposed as a
  `StateFlow<EngineState>`; the UI shows "Getting ready ..." until ready.
- **Update:** `updateYoutubeDL(context, STABLE)`. Forced before the first resolve of a fresh
  install, then a weekly `YtDlpUpdateWorker` (unmetered network) and the Settings button. Updates
  and downloads share one mutex.
- **Resolve:** `--dump-single-json --flat-playlist --playlist-end 50 --no-warnings --socket-timeout 20`
  plus `--no-playlist` for single items; parse with kotlinx-serialization (`ignoreUnknownKeys`).
  Port the server's rules from `backend/app/providers/ytdlp.py`: playlists capped at 50
  (`item_count = min(total, 50)`, `total_items` kept), channels and profiles rejected with
  "Channels and profiles aren't supported yet. Share a single post, video, or playlist.", only
  `/playlist` URLs count as YouTube playlists, SoundCloud sets only. Options: video links offer
  the three video options plus both audio options; audio-only links offer the two audio options.
  Thumbnails are shown only from the server's trusted CDN hosts (`backend/app/urls.py`).
- **Download arguments** (from `format_options` and the server's `outtmpl`):
  - `video-best`: `-f "bv*+ba/b" -S "ext:mp4:m4a" --merge-output-format mp4`
  - `video-1080`: same with `-S "res:1080,ext:mp4:m4a"`; `video-720`: `-S "res:720,ext:mp4:m4a"`
  - `audio-m4a`: `-f "ba[ext=m4a]/ba/b"`; `audio-mp3`: `-f "ba/b"`; both then
    `-x --audio-format <codec> --audio-quality 0 --embed-metadata --embed-thumbnail --convert-thumbnails jpg`
  - common: `-o "<workDir>/%(playlist_index&{:02d} |)s%(title).150B [%(id)s].%(ext)s"
    --windows-filenames --playlist-items 1:50 --ignore-errors --socket-timeout 20 --retries 3`
- **Progress:** from the library callback `(progress, eta, line)`; speed is parsed from `line`;
  the phase becomes `PROCESSING` on `[Merger]`, `[ExtractAudio]`, `[EmbedThumbnail]`.
- **Cancel:** the job UUID is the yt-dlp `processId`; cancelling the coroutine calls
  `destroyProcessById`.
- **Error mapping** from stderr (`DeviceErrorMapper`): the server's `_LOGIN_HINTS`
  ("login required", "log in", "registered users", "not granting access", "empty media response",
  "there is no video in this post") -> `LoginRequired(platform)`; "Sign in to confirm" or an
  HTTP 403 on YouTube -> `YoutubeBlocked`; "rate-limit" -> `RateLimited`; "Unsupported URL" ->
  `Unsupported`; "No space left" -> `StorageFull`; otherwise `Platform(last stderr line)`.

## 5. Server engine (`engine/server/`)

`ServerEngine(config: ServerConfig, client: OkHttpClient)`. Every request carries
`Authorization: Bearer <token>`. **No tickets**: a native client can set headers on SSE and file
requests, and the token still never appears in a URL. HTTPS only.

1. `POST /api/resolve {url}` -> `MediaInfo`.
2. `POST /api/jobs {url, option}` -> `Job`.
3. `GET /api/jobs/{id}/events` (SSE; each event is `data: <full Job JSON>`). The stream ends after
   `done`/`error`; the client closes it. On a drop, `GET /api/jobs/{id}`: finished -> apply and
   stop; 404 -> `Lost`; 401 -> stop; otherwise reconnect with backoff 1, 2, 5, 10, 30 s, never
   shorter than a 429's `Retry-After`. If SSE fails twice, poll `GET /api/jobs/{id}` every 2 s.
4. `GET /api/jobs/{id}/files/{index}` for each name in `Job.files`, streamed into the work directory.
5. Persist the server job ID as soon as it is created. After process death, pass that ID back to
   `download` to reattach with `GET /api/jobs/{id}`. `DELETE /api/jobs/{id}` only after the files
   are saved to shared storage (frees server disk). A 409 or 404 is ignored. A save failure keeps
   the server job ID for Retry.

Minimum server version 1.0.0 (`GET /api/health`). `Job.error_code == "login_required"` ->
`LoginRequired`; the message tells the user to upload cookies in the server's web app.
Error mapping (same as `web/src/lib/api.ts`): 0 -> `Network`, 400 -> `NoLink`/`Unsupported` (by
message), 401 -> `Unauthorized`, 404 -> `Lost`, 409 -> `Conflict`, 422 -> `Platform` or
`LoginRequired` (`detail.code`), 429 -> `RateLimited(Retry-After)`, 503 -> `Busy`, else `Other`.
"Test connection": `GET /api/health`, then `GET /api/jobs/token-check` (404 means the token is
accepted, 401 means it is not).

## 6. Saving files (`storage/`)

- Work directory `cacheDir/jobs/<uuid>/`; free space is checked with `StatFs` first; the directory
  is always deleted afterwards (`finally`).
- API 29+: MediaStore with `RELATIVE_PATH` and `IS_PENDING` (1 while copying, 0 when done).
  Folder by MIME type: video -> `Movies/OpenTakez`, audio -> `Music/OpenTakez`, image ->
  `Pictures/OpenTakez`, other -> `Download/OpenTakez`.
- API 26-28: `WRITE_EXTERNAL_STORAGE` (requested at runtime), write to the public directory, then
  `MediaScannerConnection.scanFile`.
- Multi-file jobs save each file on its own, keeping the `01 Title [id]` names. Skipped items are
  reported with `job.skipped`.

## 7. Work, data, settings

- **WorkManager:** `DownloadWorker` is expedited work with a `dataSync` foreground service and a
  progress notification. Device jobs share the unique queue `device` (one at a time); server jobs
  run in parallel (at most 3). Android 15 limits `dataSync` to 6 h per 24 h: a stopped job is marked
  failed with a retry button. `POST_NOTIFICATIONS` is requested on the first download; downloads
  run without it.
- **Room, table `downloads`:** `id`, `url`, `platform`, `kind`, `title`, `uploader`, `thumbnail`,
  `option`, `engine`, `serverJobId`, `status`, `files` (JSON list of URIs), `skipped`,
  `errorCode`, `errorMessage`, `createdAt`, `finishedAt`. Live progress comes from
  `WorkInfo.progress` and is not stored. After process death a server job re-attaches with
  `GET /api/jobs/{id}`; 404 shows `job.lost`.
- **DataStore:** `serverUrl`, encrypted token, `defaultEngine`, `lastYtDlpCheck`, `firstRunDone`.
- **Token storage:** AES-256-GCM with a non-exportable Android Keystore key; only the ciphertext
  is stored. (`EncryptedSharedPreferences` is deprecated.) The token never goes to logs, backups
  (see `data_extraction_rules.xml`), URLs, or notifications.
- **Language:** per-app locales (`AppCompatDelegate.setApplicationLocales`) with
  `locales_config.xml` (en, de).

## 8. Share target and manifest

- `MainActivity` is `singleTask` and handles `ACTION_SEND` `text/plain` in `onCreate` and
  `onNewIntent`: read `EXTRA_TEXT` (then `EXTRA_SUBJECT`), run `UrlExtractor`; a link opens
  Preview and starts resolving, no link shows Home with the `error.noLink` text.
- There is deliberately **no `ACTION_VIEW` filter**, so the app never hijacks YouTube links.
- Permissions: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
  `POST_NOTIFICATIONS`, `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`). Nothing else.
- `allowBackup=false`; extraction rules exclude everything. Only `MainActivity` is exported.

## 9. Strings

Android resource names are the PWA keys with `.` replaced by `_` (`url.placeholder` becomes
`url_placeholder`); English and German texts are copied from `web/src/i18n/en.ts` and `de.ts`.
New keys, with English text (German written by the UI track in the same file pair):

| Key | English |
| --- | --- |
| `engine_label` | Download |
| `engine_device` | On this device |
| `engine_server` | Via my server |
| `error_needsServer` | Photos and carousels need your own server. Add it in Settings. |
| `error_youtubeBlocked` | YouTube blocked this request. Update yt-dlp in Settings, or try again later. |
| `error_loginRequiredServer` | This needs a login. Upload cookies for {platform} in your server's web app. |
| `error_storageFull` | Not enough free space on this device. |
| `engine_preparing` | Getting ready ... |
| `ytdlp_version` | yt-dlp version |
| `ytdlp_update` | Update |
| `ytdlp_updating` | Updating ... |
| `ytdlp_updated` | Updated to {version} |
| `ytdlp_updateFailed` | Update failed. Check your connection. |
| `notif_channel_downloads` | Downloads |
| `notif_done` | Saved: {title} |
| `notif_failed` | Failed: {title} |
| `perm_notifications_rationale` | Downloads show their progress as a notification. |
| `saved_to` | Saved to {folder} |
| `job_cancel` | Cancel |
| `settings_defaultEngine` | Default download location |
| `settings_about` | About |

All other keys are the PWA's: `url.*`, `media.*`, `option.*`, `job.*`, `history.*`, `settings.*`,
`token.*`, `error.*`.

## 10. Build, versions, release

- `compileSdk` 37, `targetSdk` 36, `minSdk` 26 (java.time and notification channels without
  desugaring; API 24-25 are under 1 % of devices). JDK 21 (`jvmToolchain(21)`); the machine's
  default `java` is 27 and must not be used: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.
- `applicationId` `io.github.timhofmann27.opentakez` (debug build: `.debug`). It cannot change later.
- Version: `opentakez.version` in `android/gradle.properties` is the single source of truth and
  must equal the release tag, `web/package.json`, and `backend/app/__init__.py`. The app version is
  the repo version, so every release ships server image and APKs together.
- `versionCode = (major*1_000_000 + minor*10_000 + patch*100 + (rc ? N : 99)) * 10 + abi`, with ABI
  codes armeabi-v7a 1, arm64-v8a 2, x86_64 3. An update from `rc.2` to the final release always
  increases it.
- Per-ABI APKs only (arm64-v8a, armeabi-v7a, x86_64), named `OpenTakez-<version>-<abi>.apk` with
  a `SHA256SUMS` file. Signed with a key held in GitHub secrets (`ANDROID_KEYSTORE_BASE64`,
  `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`). If the key is lost,
  every user has to reinstall, so it is backed up offline.
- Distribution: GitHub Releases and Obtainium. Not Google Play (its policy forbids YouTube
  downloaders). F-Droid later.

## 11. Tracks and file ownership

| Track | Owns |
| --- | --- |
| Integrator | `android/build.gradle.kts`, `settings.gradle.kts`, `gradle/`, `gradle.properties`, `app/build.gradle.kts`, `AndroidManifest.xml`, `core/model/`, `engine/MediaEngine.kt`, `storage/FileSaver.kt`, this contract |
| 1A Device | `core/` (except `model/`), `engine/device/`, their tests |
| 1B Server | `engine/server/`, its tests (MockWebServer) |
| 1C Data, work, storage | `data/`, `work/`, `storage/` (except `FileSaver.kt`), `OpenTakezApp.kt`, their tests |
| 1D UI | `ui/`, `MainActivity.kt`, `res/`, UI tests |
| 1E Ops, docs | `.github/**`, `docs/android.md`, `README.md`, `docs/roadmap.md`, `CHANGELOG.md` |

A track that needs a change in an integrator file asks for it in its report instead of editing.
Every track writes tests first. No track commits, pushes, or tags.

## 12. Tests

- JVM unit tests: `UrlExtractor` (cases from `backend/tests/test_urls.py`), `RoutingPolicy`,
  format arguments, `MediaInfo` mapping from yt-dlp JSON fixtures, `DeviceErrorMapper`, version
  code, and `ServerEngine` against MockWebServer (Bearer header present, **no token or ticket in
  any URL**, 401, 422 `login_required`, 429 with `Retry-After`, 503, an SSE sequence, the polling
  fallback, file streaming, `DELETE` after success).
- ViewModel tests with fake engines (Turbine). Compose tests on Robolectric.
- Instrumented tests on the emulator, run locally: Room DAO and migration, the MediaStore saver
  writing to `Music/OpenTakez`, a SEND-intent smoke test with a real download.
- Lint must pass with `MissingTranslation` as an error.
- Manual device checklist in `docs/design/evidence/m6-checklist.md` before any release.

## 13. Out of scope for v1

Cookies on the device (content that needs a login goes through the server), cleartext HTTP to a
LAN server, a universal APK, F-Droid, and Google Play.
