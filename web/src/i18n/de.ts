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
    'Füge einen Link von YouTube, TikTok, Instagram, Facebook oder SoundCloud ein oder teile ihn aus einer anderen App an OpenTakez.',
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

  'option.image-original': 'Original',
  'option.group.image': 'Fotos',

  'media.items': '{count} Elemente',
  'media.truncated': 'Nur die ersten {count} von {total} Elementen werden geladen.',

  'job.saveZip': 'Alle als ZIP speichern',
  'job.saveFile': '{name} speichern',
  'job.files': '{count} Dateien',
  'job.skipped': '{count} Elemente konnten nicht geladen werden',

  'error.loginRequired': 'Dafür ist ein Login nötig. Lade in den Einstellungen Cookies für {platform} hoch.',
  'error.loginRequiredGeneric': 'Dafür ist ein Login nötig. Lade in den Einstellungen Cookies hoch.',
  'error.busy': 'Der Server ist ausgelastet. Versuch es gleich noch einmal.',

  'settings.cookies.title': 'Logins (Cookies)',
  'settings.cookies.intro':
    'Manche Inhalte, etwa Instagram-Fotos und Storys, gibt es nur mit Login. Lade dafür eine aus deinem Browser exportierte cookies.txt für die Plattform hoch.',
  'settings.cookies.warning':
    'Der Server nutzt diesen Login für Downloads. Plattformen können so genutzte Accounts drosseln oder sperren, nimm deshalb einen Zweit-Account. Die Datei bleibt auf dem Server, bis du sie löschst.',
  'settings.cookies.howto':
    'Exportiere sie eingeloggt mit einer Browser-Erweiterung, die cookies.txt (Netscape-Format) speichert.',
  'settings.cookies.upload': 'cookies.txt hochladen',
  'settings.cookies.replace': 'Ersetzen',
  'settings.cookies.delete': 'Löschen',
  'settings.cookies.deleteConfirm': 'Den {platform}-Login vom Server löschen?',
  'settings.cookies.none': 'Kein Login',
  'settings.cookies.loading': 'Lädt …',
  'settings.cookies.saved': 'Cookies gespeichert',
  'settings.cookies.removed': 'Login entfernt',
  'settings.cookies.present': 'Eingeloggt · {count} Cookies',
  'settings.cookies.expires': 'Läuft ab am {date}',
  'settings.cookies.expired': 'Abgelaufen, lade eine neue Datei hoch',
  'settings.cookies.uploaded': 'Hochgeladen am {date}',
  'settings.cookies.ignored': '{count} Cookies anderer Seiten wurden ignoriert.',
  'settings.cookies.tooLarge': 'Die Datei ist zu groß für eine cookies.txt.',
  'settings.cookies.insecure':
    'Hochladen ist über eine unverschlüsselte Verbindung gesperrt. Nutze HTTPS, damit der Login nicht abgefangen werden kann.',
} satisfies Record<MessageKey, string>
