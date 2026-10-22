export const en = {
  'nav.main': 'Main',
  'nav.home': 'Download',
  'nav.downloads': 'Downloads',
  'nav.history': 'History',
  'nav.settings': 'Settings',

  'url.label': 'Link',
  'url.placeholder': 'Paste or share a link',
  'url.paste': 'Paste',
  'url.clear': 'Clear',
  'url.submit': 'Check link',
  'url.pasteDenied': 'Clipboard access denied. Paste into the field instead.',
  'home.hint': 'Paste a link, or share one from another app.',
  'home.resolving': 'Checking link …',

  'preview.format': 'Format',
  'preview.download': 'Download',
  'preview.starting': 'Starting …',

  'option.video-best': 'Best',
  'option.video-1080': '1080p',
  'option.video-720': '720p',
  'option.audio-mp3': 'MP3',
  'option.audio-m4a': 'M4A',
  'option.group.video': 'Video',
  'option.group.audio': 'Audio only',

  'job.queued': 'Waiting …',
  'job.downloading': 'Downloading …',
  'job.processing': 'Processing …',
  'job.done': 'Done',
  'job.save': 'Save',
  'job.preparing': 'Preparing download …',
  'job.retry': 'Retry',
  'job.remove': 'Remove',
  'job.lost': 'The server restarted and lost this download.',
  'job.stillRunning': 'Still running, remove it when it is finished.',
  'downloads.active': '{count} running',
  'downloads.empty': 'No downloads yet. Start one from the Download tab.',

  'history.empty': 'Finished downloads show up here.',
  'history.clear': 'Clear history',
  'history.gone': 'No longer on the server',
  'history.again': 'Download again',

  'settings.server': 'Server',
  'settings.serverUrl': 'Server URL',
  'settings.serverUrlHint': 'Leave empty to use the server this app is served from.',
  'settings.serverUrlInvalid': 'Enter a full URL such as https://dl.example.com',
  'settings.mixedContent': 'This app runs on HTTPS; browsers block requests to an HTTP server.',
  'settings.token': 'Access token',
  'settings.tokenHint':
    'OPENTAKEZ_TOKEN from the server’s .env file, or the generated token in its log (docker compose logs).',
  'settings.save': 'Save',
  'settings.saved': 'Saved',
  'settings.test': 'Test connection',
  'settings.connected': 'Connected: OpenTakez {version}, yt-dlp {ytdlp}',
  'settings.platforms': 'Platforms: {platforms}',
  'settings.language': 'Language',

  'token.title': 'Access token required',
  'token.body':
    'This server is protected. Enter OPENTAKEZ_TOKEN from its .env file, or the generated token from its log (docker compose logs).',
  'token.save': 'Continue',
  'token.cancel': 'Cancel',
  'token.wrong': 'That token was not accepted.',

  'error.noLink': 'No link found in that text.',
  'error.unsupported': 'This site isn’t supported yet.',
  'error.network': 'Server unreachable. Check your connection or the server URL in Settings.',
  'error.unknown': 'Something went wrong ({status}).',

  'option.image-original': 'Original',
  'option.group.image': 'Photos',

  'media.items': '{count} items',
  'media.truncated': 'Only the first {count} of {total} items will be downloaded.',

  'job.saveZip': 'Save all as ZIP',
  'job.saveFile': 'Save {name}',
  'job.files': '{count} files',
  'job.skipped': "{count} items couldn't be downloaded",

  'error.loginRequired': 'This needs a login. Upload cookies for {platform} in Settings.',
  'error.loginRequiredGeneric': 'This needs a login. Upload cookies in Settings.',
  'error.rateLimited': 'Too many requests. Try again in {seconds} s.',
  'error.busy': 'The server is busy. Try again in a moment.',

  'settings.cookies.title': 'Logins (cookies)',
  'settings.cookies.intro':
    'Some content, like Instagram photos and stories, is only available when logged in. Upload a cookies.txt exported from your browser for that platform.',
  'settings.cookies.warning':
    'The server uses this login for downloads. Platforms can rate-limit or ban accounts used this way, so use a secondary account. The file stays on the server until you delete it.',
  'settings.cookies.howto':
    'Export with a browser extension that saves cookies.txt (Netscape format) while logged in.',
  'settings.cookies.upload': 'Upload cookies.txt',
  'settings.cookies.replace': 'Replace',
  'settings.cookies.delete': 'Delete',
  'settings.cookies.deleteConfirm': 'Delete the {platform} login from the server?',
  'settings.cookies.none': 'No login',
  'settings.cookies.loading': 'Loading …',
  'settings.cookies.saved': 'Cookies saved',
  'settings.cookies.removed': 'Login removed',
  'settings.cookies.present': 'Logged in · {count} cookies',
  'settings.cookies.expires': 'Expires {date}',
  'settings.cookies.expired': 'Expired, upload a new file',
  'settings.cookies.uploaded': 'Uploaded {date}',
  'settings.cookies.ignored': '{count} cookies for other sites were ignored.',
  'settings.cookies.tooLarge': 'That file is too large for a cookies.txt.',
  'settings.cookies.insecure':
    'Uploads are disabled over an unencrypted connection. Use HTTPS so the login can’t be intercepted.',
} as const

export type MessageKey = keyof typeof en
