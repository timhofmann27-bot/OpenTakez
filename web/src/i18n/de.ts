import type { MessageKey } from './en'

export const de = {
  'nav.main': 'Hauptnavigation',
  'nav.home': 'Laden',
  'nav.downloads': 'Downloads',
  'nav.history': 'Verlauf',
  'nav.settings': 'Einstellungen',

  'url.label': 'Link',
  'url.placeholder': 'Link einfügen oder teilen',
  'url.paste': 'Einfügen',
  'url.clear': 'Leeren',
  'url.submit': 'Link prüfen',
  'url.pasteDenied': 'Kein Zugriff auf die Zwischenablage. Füge den Link direkt ins Feld ein.',
  'home.hint':
    'Füge einen Link von YouTube oder SoundCloud ein oder teile ihn aus einer anderen App an OpenTakez.',
  'home.resolving': 'Link wird geprüft …',

  'preview.format': 'Format',
  'preview.download': 'Herunterladen',
  'preview.starting': 'Startet …',

  'option.video-best': 'Beste',
  'option.video-1080': '1080p',
  'option.video-720': '720p',
  'option.audio-mp3': 'MP3',
  'option.audio-m4a': 'M4A',
  'option.group.video': 'Video',
  'option.group.audio': 'Nur Audio',

  'job.queued': 'Wartet …',
  'job.downloading': 'Wird geladen …',
  'job.processing': 'Wird verarbeitet …',
  'job.done': 'Fertig',
  'job.save': 'Speichern',
  'job.retry': 'Erneut',
  'job.remove': 'Entfernen',
  'job.lost': 'Der Server wurde neu gestartet, dieser Download ist verloren.',
  'job.stillRunning': 'Läuft noch, entferne ihn, wenn er fertig ist.',
  'downloads.active': '{count} aktiv',
  'downloads.empty': 'Noch keine Downloads. Starte einen im Tab „Laden“.',

  'history.empty': 'Fertige Downloads erscheinen hier.',
  'history.clear': 'Verlauf leeren',
  'history.gone': 'Nicht mehr auf dem Server',
  'history.again': 'Erneut laden',

  'settings.server': 'Server',
  'settings.serverUrl': 'Server-URL',
  'settings.serverUrlHint': 'Leer lassen, um den Server zu nutzen, von dem diese App kommt.',
  'settings.serverUrlInvalid': 'Gib eine vollständige URL ein, z. B. https://dl.example.com',
  'settings.mixedContent': 'Diese App läuft über HTTPS; Browser blockieren Anfragen an einen HTTP-Server.',
  'settings.token': 'Zugangs-Token',
  'settings.tokenHint':
    'OPENTAKEZ_TOKEN aus der .env-Datei des Servers oder der generierte Token aus seinem Log (docker compose logs).',
  'settings.save': 'Speichern',
  'settings.saved': 'Gespeichert',
  'settings.test': 'Verbindung testen',
  'settings.connected': 'Verbunden: OpenTakez {version}, yt-dlp {ytdlp}',
  'settings.platforms': 'Plattformen: {platforms}',
  'settings.language': 'Sprache',

  'token.title': 'Zugangs-Token nötig',
  'token.body':
    'Dieser Server ist geschützt. Gib OPENTAKEZ_TOKEN aus seiner .env-Datei ein oder den generierten Token aus seinem Log (docker compose logs).',
  'token.save': 'Weiter',
  'token.cancel': 'Abbrechen',
  'token.wrong': 'Dieser Token wurde nicht akzeptiert.',

  'error.noLink': 'In diesem Text wurde kein Link gefunden.',
  'error.unsupported': 'Diese Seite wird noch nicht unterstützt.',
  'error.network':
    'Server nicht erreichbar. Prüfe deine Verbindung oder die Server-URL in den Einstellungen.',
  'error.unknown': 'Etwas ist schiefgelaufen ({status}).',
} satisfies Record<MessageKey, string>
