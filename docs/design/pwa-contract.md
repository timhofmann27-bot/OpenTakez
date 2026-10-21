# PWA design contract (M2)

## Product surface
- **Job:** turn a link into a file on the phone, fast, often one-handed, often arriving via Android *Share*.
- **Audience:** the person who self-hosts the instance (and maybe friends). Repeated, short sessions.
- **Primary action:** paste/share link → pick format → download → save.
- **First thing to scan:** the link field (empty state) or the resolved preview (shared state).
- **Target viewports:** `390x844` (primary, phone) and `1280x800` (desktop, centered column max 40rem).

## Direction
- **Tone:** utilitarian and calm, a tool, not a landing page. Dense enough to scan, no marketing copy, no hero.
- **Palette:** neutral zinc base (dark default, light supported via `prefers-color-scheme`). One brand accent (violet, from the logo) only for the primary action and focus ring. Platform identity comes from small colored platform chips (YouTube red, SoundCloud orange, TikTok cyan, Instagram pink, Facebook blue), so the UI is not one hue family. No gradients, no blobs.
- **Type:** system UI stack; tabular numbers for percent, speed, ETA, and duration; URLs in monospace.
- **Memorable detail:** the link field lives in a fixed bottom "dock" on phones (thumb zone) with a big Paste button. Downloads show a thin, segmented progress meter that switches to an indeterminate stripe in the "processing" phase.
- **Motion:** only state changes (preview appears, progress fills, item done). Respect `prefers-reduced-motion`.
- **Layout:** single column, no cards inside cards. Preview is one card; format choice is a segmented radio group with ≥44px targets. Stable thumbnail aspect ratio (16:9 video, 1:1 audio), so nothing jumps while loading.
- **Icons:** inline SVG for paste, download, save, delete, settings, retry (no icon font dependency).

## Content states
| State | Trigger | Feedback | Next action |
| --- | --- | --- | --- |
| Loading | resolve in flight, job queued/running | skeleton preview with fixed size; progress meter with %/speed/ETA | cancel resolve (clear field) |
| Empty | first start, no jobs, empty history | short hint in the dock; empty lists say what goes there | paste / share a link |
| Error | 400 no link/unsupported, 422 platform error, network down, job error | specific message from server `detail`, inline next to the field or on the job row | edit link, retry |
| Partial | preview without thumbnail/uploader/duration; history item whose server file expired | placeholder art with platform chip; "file no longer on server" on the item | download again |
| Success | job done | row shows done state + Save button; item lands in history | save, start another |
| Permission | 401 / `auth_required` without token; clipboard read denied | token prompt that explains the token comes from the server's `.env`; clipboard: focus field instead | enter token / paste manually |
