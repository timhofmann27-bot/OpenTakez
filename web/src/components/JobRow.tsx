import { memo, useState } from 'react'
import { useT } from '../i18n'
import { ApiError } from '../lib/api'
import { errorMessage } from '../lib/errors'
import { formatDuration, formatPercent, formatSpeed } from '../lib/format'
import { type JobView, removeJob, retryJob } from '../lib/jobs'
import { FileList } from './FileList'
import { Icon } from './Icon'
import { PlatformChip } from './PlatformChip'
import { platformName } from '../lib/platforms'
import { ProgressMeter } from './ProgressMeter'
import { SettingsLink } from './SettingsLink'
import { Thumbnail } from './Thumbnail'

export const JobRow = memo(function JobRow({ view }: { view: JobView }) {
  const t = useT()
  const { job, info, lost } = view
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)

  const loginRequired = job.status === 'error' && job.error_code === 'login_required'
  const platform = info.platform || job.platform
  const errorText = loginRequired
    ? platform
      ? t('error.loginRequired', { platform: platformName(platform) })
      : t('error.loginRequiredGeneric')
    : (job.error ?? t('error.unknown', { status: '' }))

  // Announced to screen readers only when the state changes, not on every progress tick.
  const announcement = (() => {
    if (lost) return t('job.lost')
    if (job.status === 'error') return errorText
    if (job.status === 'done') return t('job.done')
    if (job.progress?.phase === 'processing') return t('job.processing')
    return job.status === 'running' ? t('job.downloading') : t('job.queued')
  })()

  const status = (() => {
    if (lost) return t('job.lost')
    if (job.status === 'error') return errorText
    if (job.status === 'done') return t('job.done')
    if (job.status === 'queued' || !job.progress) return t('job.queued')
    if (job.progress.phase === 'processing') return t('job.processing')
    const eta = job.progress.eta ? formatDuration(job.progress.eta) : ''
    return [formatPercent(job.progress.fraction), formatSpeed(job.progress.speed), eta]
      .filter(Boolean)
      .join(' · ')
  })()
  const failed = lost || job.status === 'error'
  const showFiles = job.status === 'done' && !lost
  // Remove sits at the start of a line unless a button precedes it (Retry, or a single Save link).
  const removeStartsLine = !failed && !(showFiles && job.files.length <= 1)
  const active = !failed && job.status !== 'done'

  async function guarded(action: () => Promise<void>) {
    if (busy) return
    setBusy(true)
    setNotice('')
    try {
      await action()
    } catch (error) {
      setNotice(
        error instanceof ApiError && error.kind === 'conflict'
          ? t('job.stillRunning')
          : errorMessage(error, t),
      )
    } finally {
      setBusy(false)
    }
  }

  const remove = () => guarded(() => removeJob(job.id))
  const retry = () => guarded(() => retryJob(view))

  return (
    <li className="flex gap-3 py-3">
      <Thumbnail src={info.thumbnail} kind={info.kind} className="w-20 shrink-0 self-start" />
      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
        <div className="flex items-center gap-2">
          <PlatformChip platform={info.platform} />
          <span className="text-xs text-muted">{t(`option.${job.option}`)}</span>
        </div>
        <p className="line-clamp-2 text-sm font-medium leading-snug">{info.title}</p>

        {active && <ProgressMeter progress={job.progress} label={info.title} />}
        <p
          className={`text-xs tabular-nums ${failed ? 'text-danger' : job.status === 'done' ? 'text-success' : 'text-muted'}`}
          aria-hidden="true"
        >
          {status}
        </p>
        <p className="sr-only" role="status">
          {announcement}
        </p>
        {showFiles && !!job.skipped && job.skipped > 0 && (
          <p className="text-xs text-muted">{t('job.skipped', { count: job.skipped })}</p>
        )}
        {loginRequired && !lost && <SettingsLink />}
        {notice && (
          <p role="alert" className="text-xs text-danger">
            {notice}
          </p>
        )}

        <div className="mt-1 flex flex-wrap gap-2">
          {showFiles && (
            <FileList jobId={job.id} files={job.files} sizes={job.sizes} />
          )}
          {failed && (
            <button
              type="button"
              onClick={retry}
              disabled={busy}
              className="inline-flex min-h-11 items-center gap-1.5 rounded-lg bg-surface-2 px-3 text-sm font-medium"
            >
              <Icon name="retry" className="size-4" />
              {t('job.retry')}
            </button>
          )}
          <button
            type="button"
            onClick={remove}
            disabled={busy}
            aria-label={`${t('job.remove')}: ${info.title}`}
            className={`inline-flex min-h-11 items-center gap-1.5 rounded-lg px-3 text-sm text-muted hover:bg-surface-2 ${removeStartsLine ? '-ml-3' : ''}`}
          >
            <Icon name="trash" className="size-4" />
            {t('job.remove')}
          </button>
        </div>
      </div>
    </li>
  )
})
