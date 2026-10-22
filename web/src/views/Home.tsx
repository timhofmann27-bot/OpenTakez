import { useCallback, useEffect, useReducer, useRef, useState } from 'react'
import { Icon } from '../components/Icon'
import { PlatformChip } from '../components/PlatformChip'
import { PreviewCard } from '../components/PreviewCard'
import { SettingsLink } from '../components/SettingsLink'
import { useT } from '../i18n'
import { ApiError, api } from '../lib/api'
import { errorMessage, guessPlatform, isLoginRequired } from '../lib/errors'
import { startJob } from '../lib/jobs'
import { pendingLink } from '../lib/pending'
import { useSettings } from '../lib/settings'
import { useStore } from '../lib/store'
import type { DownloadOption, MediaInfo } from '../lib/types'
import { navigate } from '../lib/useRoute'

type State =
  | { status: 'idle' }
  | { status: 'resolving' }
  | { status: 'ready'; info: MediaInfo }
  | { status: 'error'; error: unknown; platform: string }

type Action =
  | { type: 'resolve' }
  | { type: 'resolved'; info: MediaInfo }
  | { type: 'failed'; error: unknown; platform: string }
  | { type: 'reset' }

function reducer(_: State, action: Action): State {
  switch (action.type) {
    case 'resolve':
      return { status: 'resolving' }
    case 'resolved':
      return { status: 'ready', info: action.info }
    case 'failed':
      return { status: 'error', error: action.error, platform: action.platform }
    case 'reset':
      return { status: 'idle' }
  }
}

export function Home() {
  const t = useT()
  const { token } = useSettings()
  const pending = useStore(pendingLink)
  const [text, setText] = useState('')
  const [state, dispatch] = useReducer(reducer, { status: 'idle' })
  const [startError, setStartError] = useState<unknown>(null)
  const [pasteDenied, setPasteDenied] = useState(false)
  const inflight = useRef<AbortController | null>(null)
  const input = useRef<HTMLInputElement>(null)

  const resolve = useCallback(async (value: string) => {
    if (!value.trim()) return
    inflight.current?.abort()
    const controller = new AbortController()
    inflight.current = controller
    setStartError(null)
    dispatch({ type: 'resolve' })
    try {
      dispatch({ type: 'resolved', info: await api.resolve(value, controller.signal) })
    } catch (error) {
      if (!controller.signal.aborted) dispatch({ type: 'failed', error, platform: guessPlatform(value) })
    }
  }, [])

  // Links arriving from the share target or "download again".
  useEffect(() => {
    if (!pending) return
    pendingLink.set(null)
    setText(pending)
    void resolve(pending)
  }, [pending, resolve])

  // After the token dialog fixed a 401, try again.
  const unauthorized =
    state.status === 'error' && state.error instanceof ApiError && state.error.kind === 'unauthorized'
  useEffect(() => {
    if (unauthorized && token) void resolve(text)
    // Only re-run when the token changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token])

  useEffect(() => () => inflight.current?.abort(), [])

  async function paste() {
    setPasteDenied(false)
    try {
      const clip = await navigator.clipboard.readText()
      if (clip.trim()) {
        setText(clip)
        void resolve(clip)
      }
    } catch {
      setPasteDenied(true)
      input.current?.focus()
    }
  }

  function clear() {
    inflight.current?.abort()
    setText('')
    dispatch({ type: 'reset' })
    input.current?.focus()
  }

  async function download(info: MediaInfo, option: DownloadOption) {
    setStartError(null)
    try {
      await startJob(info, option)
      navigate('/downloads')
    } catch (error) {
      setStartError(error)
    }
  }

  return (
    <div className="flex flex-col gap-4 pb-40 sm:pb-0">

      <section aria-live="polite" aria-busy={state.status === 'resolving'}>
        {state.status === 'idle' && (
          <div className="flex flex-col items-center gap-5 px-2 pt-10 pb-6 text-center sm:pt-16">
            <img src="/logo.svg" alt="" className="size-16 rounded-2xl shadow-card" />
            <div className="flex flex-col gap-1.5">
              <p className="text-xl font-semibold tracking-tight">OpenTakez</p>
              <p className="text-sm text-muted">{t('home.hint')}</p>
            </div>
            <ul className="flex max-w-full flex-wrap justify-center gap-1">
              {['youtube', 'tiktok', 'instagram', 'facebook', 'soundcloud'].map((platform) => (
                <li key={platform}>
                  <PlatformChip platform={platform} compact />
                </li>
              ))}
            </ul>
          </div>
        )}

        {state.status === 'resolving' && (
          <div className="flex flex-col gap-3 rounded-2xl border border-line bg-surface p-4 shadow-card">
            <div className="shimmer aspect-video w-full rounded-xl bg-surface-2" />
            <div className="shimmer h-4 w-3/4 rounded bg-surface-2" />
            <div className="shimmer h-3 w-1/3 rounded bg-surface-2" />
            <span className="sr-only">{t('home.resolving')}</span>
          </div>
        )}

        {state.status === 'error' && (
          <div
            id="link-error"
            role="alert"
            className="flex gap-2 rounded-xl border border-danger/40 bg-danger/10 p-3 text-sm text-danger"
          >
            <Icon name="alert" className="size-5 shrink-0" />
            <div className="flex flex-col gap-1">
              <span>{errorMessage(state.error, t, state.platform)}</span>
              {isLoginRequired(state.error) && <SettingsLink />}
            </div>
          </div>
        )}

        {state.status === 'ready' && (
          <div className="flex flex-col gap-2">
            <PreviewCard
              key={state.info.url}
              info={state.info}
              onDownload={(option) => download(state.info, option)}
            />
            {startError != null && (
              <div role="alert" className="flex flex-col gap-1 text-sm text-danger">
                <p>{errorMessage(startError, t, state.info.platform)}</p>
                {isLoginRequired(startError) && <SettingsLink />}
              </div>
            )}
          </div>
        )}
      </section>

      <form
        onSubmit={(e) => {
          e.preventDefault()
          void resolve(text)
        }}
        className="fixed inset-x-0 bottom-[calc(3.5rem+env(safe-area-inset-bottom))] z-10 border-t border-line bg-bg/85 p-3 backdrop-blur-md sm:static sm:order-first sm:border-0 sm:bg-transparent sm:p-0 sm:backdrop-blur-none"
      >
        <div className="mx-auto flex max-w-[40rem] flex-col gap-2">
          <label htmlFor="link" className="sr-only">
            {t('url.label')}
          </label>
          <div className="flex gap-2">
            <div className="relative flex-1">
              <input
                id="link"
                ref={input}
                type="text"
                inputMode="url"
                autoComplete="off"
                autoCapitalize="off"
                spellCheck={false}
                enterKeyHint="go"
                placeholder={t('url.placeholder')}
                value={text}
                onChange={(e) => setText(e.target.value)}
                aria-invalid={state.status === 'error'}
                aria-describedby={state.status === 'error' ? 'link-error' : undefined}
                className="min-h-12 w-full rounded-xl border border-field-line bg-surface pr-10 pl-3 font-mono text-sm shadow-card transition-colors placeholder:font-sans placeholder:text-muted focus:border-accent sm:shadow-none"
              />
              {text && (
                <button
                  type="button"
                  onClick={clear}
                  aria-label={t('url.clear')}
                  className="absolute top-1/2 right-1 grid size-10 -translate-y-1/2 place-items-center rounded-lg text-muted hover:text-fg"
                >
                  <Icon name="close" className="size-4" />
                </button>
              )}
            </div>
            {text.trim() ? (
              <button
                type="submit"
                className="flex min-h-12 items-center gap-2 rounded-xl bg-accent px-4 text-sm font-semibold text-accent-fg shadow-[0_8px_20px_-8px_var(--accent)] transition-transform active:scale-[0.98] motion-reduce:transition-none"
              >
                {t('url.submit')}
              </button>
            ) : (
              <button
                type="button"
                onClick={paste}
                className="flex min-h-12 items-center gap-2 rounded-xl bg-accent px-4 text-sm font-semibold text-accent-fg shadow-[0_8px_20px_-8px_var(--accent)] transition-transform active:scale-[0.98] motion-reduce:transition-none"
              >
                <Icon name="paste" />
                {t('url.paste')}
              </button>
            )}
          </div>
          {pasteDenied && <p className="text-xs text-muted">{t('url.pasteDenied')}</p>}
        </div>
      </form>
    </div>
  )
}
