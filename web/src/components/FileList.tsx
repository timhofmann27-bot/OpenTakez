import { useEffect, useRef, useState } from 'react'
import { useT } from '../i18n'
import { ApiError } from '../lib/api'
import { downloadFile, downloadZip } from '../lib/download'
import { errorMessage } from '../lib/errors'
import { formatBytes } from '../lib/format'
import { Icon } from './Icon'

type Props = {
  jobId: string
  files: string[]
  sizes?: number[]
  /** `primary` for the live job row, `quiet` for history. */
  tone?: 'primary' | 'quiet'
  /** Move focus to the first link on mount (the button that revealed this list just unmounted). */
  autoFocus?: boolean
}

const tones = {
  primary: 'bg-accent font-semibold text-accent-fg',
  quiet: 'bg-surface-2 font-medium',
}

/** Save buttons for a finished job: one button for a single file, ZIP plus a list for several. */
export function FileList({ jobId, files, sizes, tone = 'primary', autoFocus }: Props) {
  const t = useT()
  const first = useRef<HTMLButtonElement>(null)
  const busyRef = useRef(false)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')
  useEffect(() => {
    if (autoFocus) first.current?.focus()
  }, [autoFocus])

  /** Fetches a ticket (inside `action`), then the browser takes over the download. */
  async function run(action: () => Promise<void>) {
    if (busyRef.current) return
    busyRef.current = true
    setBusy(true)
    setNotice('')
    try {
      await action()
    } catch (error) {
      setNotice(
        error instanceof ApiError && error.kind === 'notFound' ? t('history.gone') : errorMessage(error, t),
      )
    } finally {
      busyRef.current = false
      setBusy(false)
    }
  }

  const errorNote = notice && (
    <p role="alert" className="w-full text-xs text-danger">
      {notice}
    </p>
  )
  const buttonClass = `inline-flex min-h-11 items-center gap-1.5 rounded-lg px-3 text-sm ${tones[tone]}`

  if (files.length <= 1) {
    return (
      <>
        <button
          ref={first}
          type="button"
          onClick={() => run(() => downloadFile(jobId, 0, files[0] ?? ''))}
          aria-disabled={busy}
          className={`${buttonClass} aria-disabled:opacity-60`}
        >
          <Icon name="save" className="size-4" />
          {t('job.save')}
        </button>
        {busy && <span role="status" className="sr-only">{t('job.preparing')}</span>}
        {errorNote}
      </>
    )
  }

  return (
    <div className="flex w-full flex-col gap-1">
      <button
        ref={first}
        type="button"
        onClick={() => run(() => downloadZip(jobId))}
        aria-disabled={busy}
        className={`${buttonClass} self-start aria-disabled:opacity-60`}
      >
        <Icon name="archive" className="size-4" />
        {t('job.saveZip')}
      </button>
      {busy && <span role="status" className="sr-only">{t('job.preparing')}</span>}
      {errorNote}
      <p className="mt-1 text-xs text-muted">{t('job.files', { count: files.length })}</p>
      <ul className="divide-y divide-line">
        {files.map((name, index) => {
          const size = formatBytes(sizes?.[index])
          return (
            <li key={`${index}-${name}`} className="flex items-center gap-2">
              <span className="min-w-0 flex-1 truncate py-2 text-xs" title={name}>
                {name}
              </span>
              {size && <span className="shrink-0 text-xs text-muted tabular-nums">{size}</span>}
              <button
                type="button"
                onClick={() => run(() => downloadFile(jobId, index, name))}
                aria-disabled={busy}
                aria-label={t('job.saveFile', { name })}
                className="grid size-11 shrink-0 place-items-center rounded-lg text-muted hover:bg-surface-2 hover:text-fg aria-disabled:opacity-60"
              >
                <Icon name="save" className="size-4" />
              </button>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
