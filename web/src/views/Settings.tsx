import { useState } from 'react'
import { CookieSettings } from '../components/CookieSettings'
import { useT } from '../i18n'
import { api } from '../lib/api'
import { errorMessage } from '../lib/errors'
import { type Lang, normalizeServerUrl, updateSettings, useSettings } from '../lib/settings'
import type { Health } from '../lib/types'

const inputClass = 'min-h-11 rounded-lg border border-field-line bg-bg px-3 text-sm'

export function Settings() {
  const t = useT()
  const settings = useSettings()
  const [serverUrl, setServerUrl] = useState(settings.serverUrl)
  const [token, setToken] = useState(settings.token)
  const [saved, setSaved] = useState(false)
  const [health, setHealth] = useState<Health | null>(null)
  const [testError, setTestError] = useState('')

  const normalized = normalizeServerUrl(serverUrl)
  const mixedContent = location.protocol === 'https:' && normalized?.startsWith('http:')

  function save(event: React.FormEvent) {
    event.preventDefault()
    if (normalized == null) return
    updateSettings({ serverUrl: normalized, token: token.trim() })
    setServerUrl(normalized)
    setSaved(true)
  }

  async function test() {
    setHealth(null)
    setTestError('')
    try {
      setHealth(await api.health(undefined, normalized ?? undefined))
    } catch (error) {
      setTestError(errorMessage(error, t))
    }
  }

  return (
    <div className="flex flex-col gap-8">
      <form onSubmit={save} className="flex flex-col gap-4" onChange={() => setSaved(false)}>
        <h2 className="text-base font-semibold">{t('settings.server')}</h2>

        <div className="flex flex-col gap-1.5">
          <label htmlFor="server-url" className="text-sm font-medium">
            {t('settings.serverUrl')}
          </label>
          <input
            id="server-url"
            type="url"
            inputMode="url"
            placeholder={location.origin}
            value={serverUrl}
            onChange={(e) => setServerUrl(e.target.value)}
            aria-invalid={normalized == null}
            aria-describedby="server-hint"
            className={`${inputClass} font-mono`}
          />
          <p id="server-hint" className={`text-xs ${normalized == null ? 'text-danger' : 'text-muted'}`}>
            {normalized == null ? t('settings.serverUrlInvalid') : t('settings.serverUrlHint')}
            {mixedContent && <span className="block text-danger">{t('settings.mixedContent')}</span>}
          </p>
        </div>

        <div className="flex flex-col gap-1.5">
          <label htmlFor="server-token" className="text-sm font-medium">
            {t('settings.token')}
          </label>
          <input
            id="server-token"
            type="password"
            autoComplete="current-password"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            aria-describedby="token-hint"
            className={`${inputClass} font-mono`}
          />
          <p id="token-hint" className="text-xs text-muted">
            {t('settings.tokenHint')}
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <button
            type="submit"
            disabled={normalized == null}
            className="min-h-11 rounded-lg bg-accent px-4 text-sm font-semibold text-accent-fg disabled:opacity-60"
          >
            {t('settings.save')}
          </button>
          <button
            type="button"
            onClick={test}
            className="min-h-11 rounded-lg bg-surface-2 px-4 text-sm font-medium"
          >
            {t('settings.test')}
          </button>
          <span role="status" className="text-sm text-success">
            {saved ? t('settings.saved') : ''}
          </span>
        </div>

        <div aria-live="polite" className="text-sm">
          {health && (
            <div className="flex flex-col gap-1 text-muted">
              <p className="text-success">
                {t('settings.connected', { version: health.version, ytdlp: health.yt_dlp })}
              </p>
              <p>{t('settings.platforms', { platforms: health.platforms.join(', ') })}</p>
            </div>
          )}
          {testError && (
            <p role="alert" className="text-danger">
              {testError}
            </p>
          )}
        </div>
      </form>

      <CookieSettings />

      <div className="flex flex-col gap-1.5">
        <label htmlFor="language" className="text-sm font-medium">
          {t('settings.language')}
        </label>
        <select
          id="language"
          value={settings.lang}
          onChange={(e) => updateSettings({ lang: e.target.value as Lang })}
          className={`${inputClass} self-start`}
        >
          <option value="de">Deutsch</option>
          <option value="en">English</option>
        </select>
      </div>
    </div>
  )
}
