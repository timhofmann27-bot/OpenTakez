import { useEffect, useRef, useState } from 'react'
import { useT } from '../i18n'
import { ApiError, api } from '../lib/api'
import { errorMessage } from '../lib/errors'
import { isInsecureServer, useSettings } from '../lib/settings'
import type { CookieStatus } from '../lib/types'
import { PlatformChip } from './PlatformChip'
import { platformName } from '../lib/platforms'

const COOKIE_PLATFORMS = ['instagram', 'tiktok', 'facebook'] as const
/** Bigger than any real cookies.txt; mirrors the server's limit. */
const MAX_COOKIE_BYTES = 256 * 1024

const emptyStatus = (platform: string): CookieStatus => ({
  platform,
  present: false,
  cookie_count: 0,
  expired: false,
  domains: [],
})

export function CookieSettings() {
  const { serverUrl } = useSettings()
  // A different server has different cookies: start over instead of showing the old map.
  return <CookieList key={serverUrl} serverUrl={serverUrl} />
}

type LoadState = 'loading' | 'ready' | 'failed'

function CookieList({ serverUrl }: { serverUrl: string }) {
  const t = useT()
  const insecure = isInsecureServer(serverUrl)
  const [statuses, setStatuses] = useState<Record<string, CookieStatus>>({})
  const [load, setLoad] = useState<LoadState>('loading')
  const [loadError, setLoadError] = useState('')
  /** Bumped on every upload/delete so an older list response can't overwrite a newer result. */
  const versions = useRef<Record<string, number>>({})

  useEffect(() => {
    let current = true
    const started = { ...versions.current }
    api.cookies
      .list()
      .then((list) => {
        if (!current) return
        setStatuses((prev) => {
          const next = { ...prev }
          for (const s of list) {
            if ((versions.current[s.platform] ?? 0) === (started[s.platform] ?? 0)) next[s.platform] = s
          }
          return next
        })
        setLoad('ready')
        setLoadError('')
      })
      .catch((error) => {
        if (!current) return
        setLoad('failed')
        setLoadError(errorMessage(error, t))
      })
    return () => {
      current = false
    }
  }, [t])

  const update = (platform: string, status: CookieStatus) => {
    versions.current[platform] = (versions.current[platform] ?? 0) + 1
    setStatuses((prev) => ({ ...prev, [platform]: status }))
  }

  return (
    <section aria-labelledby="cookies-title" className="flex flex-col gap-4 rounded-2xl border border-line bg-surface p-4 shadow-card">
      <h2 id="cookies-title" className="text-xs font-medium tracking-wide text-muted uppercase">
        {t('settings.cookies.title')}
      </h2>
      <div className="flex flex-col gap-2 text-sm text-muted">
        <p>{t('settings.cookies.intro')}</p>
        <p>{t('settings.cookies.warning')}</p>
        <p>{t('settings.cookies.howto')}</p>
      </div>
      {insecure && (
        <p role="alert" className="text-sm text-danger">
          {t('settings.cookies.insecure')}
        </p>
      )}
      {loadError && (
        <p role="alert" className="text-sm text-danger">
          {loadError}
        </p>
      )}
      <ul className="divide-y divide-line">
        {COOKIE_PLATFORMS.map((platform) => (
          <CookieRow
            key={platform}
            platform={platform}
            status={statuses[platform] ?? (load === 'ready' ? emptyStatus(platform) : undefined)}
            load={load}
            blocked={insecure || load !== 'ready'}
            onChange={(status) => update(platform, status)}
          />
        ))}
      </ul>
    </section>
  )
}

function CookieRow({
  platform,
  status,
  load,
  blocked,
  onChange,
}: {
  platform: string
  /** Undefined until the server's answer (or our own upload) is known. */
  status: CookieStatus | undefined
  load: LoadState
  blocked: boolean
  onChange: (status: CookieStatus) => void
}) {
  const t = useT()
  const input = useRef<HTMLInputElement>(null)
  const uploadButton = useRef<HTMLButtonElement>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [ignored, setIgnored] = useState(0)
  const [done, setDone] = useState('')
  const name = platformName(platform)
  const present = status?.present ?? false
  const inactive = blocked || busy

  const date = (iso: string) => {
    const parsed = new Date(iso)
    return Number.isNaN(parsed.getTime()) ? '' : parsed.toLocaleDateString(undefined, { dateStyle: 'medium' })
  }

  const lines: { text: string; tone: 'muted' | 'danger' }[] = []
  if (!status) {
    // Don't claim "No login" before we know; on a failed load say nothing.
    if (load === 'loading') lines.push({ text: t('settings.cookies.loading'), tone: 'muted' })
  } else if (!status.present) {
    lines.push({ text: t('settings.cookies.none'), tone: 'muted' })
  } else {
    lines.push({ text: t('settings.cookies.present', { count: status.cookie_count }), tone: 'muted' })
    if (status.expired) lines.push({ text: t('settings.cookies.expired'), tone: 'danger' })
    else if (status.earliest_expiry) {
      const when = date(status.earliest_expiry)
      if (when) lines.push({ text: t('settings.cookies.expires', { date: when }), tone: 'muted' })
    }
    if (status.uploaded_at) {
      const when = date(status.uploaded_at)
      if (when) lines.push({ text: t('settings.cookies.uploaded', { date: when }), tone: 'muted' })
    }
  }

  async function upload(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file || inactive) return
    setError('')
    setIgnored(0)
    setDone('')
    if (file.size > MAX_COOKIE_BYTES) {
      setError(t('settings.cookies.tooLarge'))
      return
    }
    setBusy(true)
    try {
      // The text only lives in this call; it is never stored in state or logged.
      const { dropped_count, ...next } = await api.cookies.put(platform, await file.text())
      onChange(next)
      setIgnored(dropped_count)
      setDone(t('settings.cookies.saved'))
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 413 ? t('settings.cookies.tooLarge') : errorMessage(e, t),
      )
    } finally {
      setBusy(false)
    }
  }

  async function remove() {
    if (inactive) return
    if (!window.confirm(t('settings.cookies.deleteConfirm', { platform: name }))) return
    setError('')
    setIgnored(0)
    setDone('')
    setBusy(true)
    try {
      try {
        await api.cookies.remove(platform)
      } catch (e) {
        // Already gone is the state we wanted.
        if (!(e instanceof ApiError && e.kind === 'notFound')) throw e
      }
      onChange({ platform, present: false, cookie_count: 0, expired: false, uploaded_at: null, earliest_expiry: null, domains: [] })
      setDone(t('settings.cookies.removed'))
      // The Delete button is about to unmount; keep keyboard users in this row.
      uploadButton.current?.focus()
    } catch (e) {
      setError(errorMessage(e, t))
    } finally {
      setBusy(false)
    }
  }

  // aria-disabled keeps the button focusable (a `disabled` button drops focus), so clicks are ignored here.
  const buttonClass =
    'inline-flex min-h-11 items-center rounded-lg px-4 text-sm font-medium aria-disabled:opacity-60'

  return (
    <li className="flex flex-col gap-2 py-3">
      <div className="flex items-center gap-2">
        <PlatformChip platform={platform} />
      </div>
      <div className="flex flex-col gap-0.5 text-xs">
        {lines.map((line) => (
          <p key={line.text} className={line.tone === 'danger' ? 'text-danger' : 'text-muted'}>
            {line.text}
          </p>
        ))}
      </div>
      <input
        ref={input}
        type="file"
        accept=".txt,text/plain"
        onChange={upload}
        hidden
        data-testid={`cookie-file-${platform}`}
      />
      <div className="flex flex-wrap gap-2">
        <button
          ref={uploadButton}
          type="button"
          aria-disabled={inactive}
          onClick={() => {
            if (!inactive) input.current?.click()
          }}
          aria-label={`${present ? t('settings.cookies.replace') : t('settings.cookies.upload')}: ${name}`}
          className={`${buttonClass} ${present ? 'bg-surface-2' : 'bg-accent font-semibold text-accent-fg'}`}
        >
          {present ? t('settings.cookies.replace') : t('settings.cookies.upload')}
        </button>
        {present && (
          <button
            type="button"
            aria-disabled={busy}
            onClick={remove}
            aria-label={`${t('settings.cookies.delete')}: ${name}`}
            className={`${buttonClass} text-muted hover:bg-surface-2`}
          >
            {t('settings.cookies.delete')}
          </button>
        )}
      </div>
      <p role="status" className="sr-only">
        {done}
      </p>
      {ignored > 0 && <p className="text-xs text-muted">{t('settings.cookies.ignored', { count: ignored })}</p>}
      {error && (
        <p role="alert" className="text-xs text-danger">
          {error}
        </p>
      )}
    </li>
  )
}
