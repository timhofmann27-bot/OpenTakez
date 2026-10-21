import { useState } from 'react'
import { useT } from '../i18n'
import { ApiError, api, fileUrl } from '../lib/api'
import { errorMessage } from '../lib/errors'
import { type HistoryItem, markGone, removeHistory } from '../lib/history'
import { pendingLink } from '../lib/pending'
import { navigate } from '../lib/useRoute'
import type { Job } from '../lib/types'
import { FileList } from './FileList'
import { Icon } from './Icon'
import { PlatformChip } from './PlatformChip'
import { Thumbnail } from './Thumbnail'

function triggerDownload(href: string, fileName: string) {
  const link = document.createElement('a')
  link.href = href
  link.download = fileName
  link.click()
}

export function HistoryRow({ item }: { item: HistoryItem }) {
  const t = useT()
  const [notice, setNotice] = useState('')
  /** The job as the server reports it, once we know its files are still there (multi-file only). */
  const [available, setAvailable] = useState<Job | null>(null)
  const multi = item.fileNames.length > 1
  const date = new Date(item.createdAt).toLocaleString(undefined, {
    dateStyle: 'short',
    timeStyle: 'short',
  })

  async function save() {
    setNotice('')
    // Files expire on the server, so check before navigating to a dead link.
    try {
      const job = await api.getJob(item.id)
      if (job.status !== 'done') {
        setAvailable(null)
        await markGone(item.id)
      } else if (multi) setAvailable(job)
      else triggerDownload(fileUrl(item.id), item.fileName)
    } catch (error) {
      setAvailable(null)
      if (error instanceof ApiError && error.kind === 'notFound') await markGone(item.id)
      else setNotice(errorMessage(error, t))
    }
  }

  function downloadAgain() {
    pendingLink.set(item.url)
    navigate('/')
  }

  return (
    <li className="flex gap-3 py-3">
      <Thumbnail src={item.thumbnail} kind={item.kind} className="w-20 shrink-0 self-start" />
      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
        <div className="flex flex-wrap items-center gap-2">
          <PlatformChip platform={item.platform} />
          <span className="text-xs text-muted">{t(`option.${item.option}`)}</span>
          <time className="text-xs text-muted tabular-nums" dateTime={new Date(item.createdAt).toISOString()}>
            {date}
          </time>
        </div>
        <p className="line-clamp-2 text-sm font-medium leading-snug">{item.title}</p>
        {item.gone && <p className="text-xs text-muted">{t('history.gone')}</p>}
        {notice && (
          <p role="alert" className="text-xs text-danger">
            {notice}
          </p>
        )}
        <div className="mt-1 flex flex-wrap gap-2">
          {item.gone ? (
            <button
              type="button"
              onClick={downloadAgain}
              className="inline-flex min-h-11 items-center gap-1.5 rounded-lg bg-surface-2 px-3 text-sm font-medium"
            >
              <Icon name="retry" className="size-4" />
              {t('history.again')}
            </button>
          ) : available ? (
            <FileList
              jobId={item.id}
              files={available.files.length ? available.files : item.fileNames}
              sizes={available.sizes}
              tone="quiet"
              autoFocus
            />
          ) : (
            <button
              type="button"
              onClick={save}
              className="inline-flex min-h-11 items-center gap-1.5 rounded-lg bg-surface-2 px-3 text-sm font-medium"
            >
              <Icon name="save" className="size-4" />
              {t('job.save')}
            </button>
          )}
          <button
            type="button"
            onClick={() => void removeHistory(item.id)}
            aria-label={`${t('job.remove')}: ${item.title}`}
            className={`inline-flex min-h-11 items-center gap-1.5 rounded-lg px-3 text-sm text-muted hover:bg-surface-2 ${available ? '-ml-3' : ''}`}
          >
            <Icon name="trash" className="size-4" />
            {t('job.remove')}
          </button>
        </div>
      </div>
    </li>
  )
}
