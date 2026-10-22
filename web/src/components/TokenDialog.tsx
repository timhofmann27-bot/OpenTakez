import { useEffect, useRef, useState } from 'react'
import { useT } from '../i18n'
import { ApiError, api } from '../lib/api'
import { errorMessage } from '../lib/errors'
import { tokenPrompt } from '../lib/pending'
import { updateSettings, useSettings } from '../lib/settings'
import { useStore } from '../lib/store'

export function TokenDialog() {
  const t = useT()
  const open = useStore(tokenPrompt)
  const settings = useSettings()
  const dialog = useRef<HTMLDialogElement>(null)
  const [value, setValue] = useState(settings.token)
  const [error, setError] = useState('')
  const [checking, setChecking] = useState(false)

  useEffect(() => {
    const el = dialog.current
    if (!el) return
    if (open && !el.open) el.showModal()
    if (!open && el.open) el.close()
  }, [open])

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    const candidate = value.trim()
    setChecking(true)
    try {
      await api.checkToken(candidate)
      setError('')
      updateSettings({ token: candidate })
      tokenPrompt.set(false)
    } catch (err) {
      if (err instanceof ApiError && err.kind === 'unauthorized') setError(t('token.wrong'))
      else setError(errorMessage(err, t))
    } finally {
      setChecking(false)
    }
  }

  return (
    <dialog
      ref={dialog}
      onClose={() => tokenPrompt.set(false)}
      aria-labelledby="token-title"
      className="m-auto w-[min(26rem,calc(100%-2rem))] rounded-2xl border border-line bg-surface p-5 text-fg shadow-card backdrop:bg-black/70 backdrop:backdrop-blur-sm"
    >
      <form onSubmit={submit} className="flex flex-col gap-4">
        <h2 id="token-title" className="text-lg font-semibold">
          {t('token.title')}
        </h2>
        <p className="text-sm text-muted">{t('token.body')}</p>
        <label className="flex flex-col gap-1.5 text-sm font-medium">
          {t('settings.token')}
          <input
            id="token-input"
            name="token"
            type="password"
            autoComplete="current-password"
            required
            autoFocus
            value={value}
            onChange={(e) => setValue(e.target.value)}
            aria-invalid={Boolean(error)}
            aria-describedby={error ? 'token-error' : undefined}
            className="min-h-11 rounded-lg border border-field-line bg-bg px-3 font-mono transition-colors focus:border-accent"
          />
        </label>
        {error && (
          <p id="token-error" role="alert" className="text-sm text-danger">
            {error}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <button
            type="button"
            onClick={() => tokenPrompt.set(false)}
            className="min-h-11 rounded-lg px-4 text-sm font-medium text-muted hover:bg-surface-2"
          >
            {t('token.cancel')}
          </button>
          <button
            type="submit"
            disabled={checking}
            className="min-h-11 rounded-lg bg-accent px-4 text-sm font-semibold text-accent-fg shadow-[0_8px_20px_-8px_var(--accent)] transition-transform active:scale-[0.98] disabled:opacity-60 disabled:shadow-none motion-reduce:transition-none"
          >
            {t('token.save')}
          </button>
        </div>
      </form>
    </dialog>
  )
}
