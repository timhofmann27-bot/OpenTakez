# M6 device checklist

Run this by hand before every Android release, on the signed release APK (not the debug build),
on a real phone if possible and otherwise on an emulator. Copy the list into the release notes
draft or an issue and tick it off; note the device, Android version, and APK ABI.

Device: ______  Android: ______  APK: `OpenTakez-<version>-<abi>.apk`

## Install and update

- [ ] Fresh install from the GitHub Release APK works; the launcher icon and its monochrome
      (themed) variant look right.
- [ ] Installing the new APK over the previous release keeps history and settings (same
      signature; no "app not installed").
- [ ] `SHA256SUMS` matches the downloaded APK.

## Download on the device

- [ ] Share a YouTube link from the YouTube app: the app opens on the preview and resolves.
- [ ] Paste a link with **Paste**, then **Check link**; the keyboard closes on submit.
- [ ] Video (best quality) lands in `Movies/OpenTakez` and plays from the gallery.
- [ ] MP3 lands in `Music/OpenTakez` with title and cover, and plays.
- [ ] A SoundCloud track downloads as audio.
- [ ] The progress notification shows phase and percentage and disappears when done.
- [ ] Cancel a running download; it leaves no partial file behind.
- [ ] Flight mode during a download: the job fails with a readable error and **Retry** works.
- [ ] A link from an unsupported site shows the "not supported" message; text without a link
      shows the "no link" message.

## Via the server

- [ ] Settings: server URL and token, **Test connection** succeeds; a wrong token is reported.
- [ ] An Instagram photo post goes through the server and lands in `Pictures/OpenTakez`.
- [ ] A carousel or playlist saves every item; skipped items are reported.
- [ ] Without a server configured, the engine switch is disabled and photo links explain why.

## History and settings

- [ ] **Open** and **Share** work for a finished file; **Download again** starts a new job.
- [ ] **Clear** empties the history after confirmation.
- [ ] Language switch (System, Deutsch, English) applies immediately.
- [ ] yt-dlp **Update** in Settings reports the new or current version.

## Look and accessibility

- [ ] Light and dark mode on every tab: no unreadable text, brand colors (not wallpaper colors).
- [ ] German texts fit on a 360 dp wide screen; nothing is cut off in the nav bar.
- [ ] Font size at 200 %: screens scroll, buttons stay usable.
- [ ] TalkBack reads the tab names, the link field, the option chips, and the progress.
- [ ] Rotation and returning from the background keep the preview and running jobs.
