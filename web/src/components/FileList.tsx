import { useEffect, useRef } from 'react'
import { useT } from '../i18n'
import { fileUrl, zipUrl } from '../lib/api'
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

/** Save links for a finished job: one button for a single file, ZIP plus a list for several. */
export function FileList({ jobId, files, sizes, tone = 'primary', autoFocus }: Props) {
  const t = useT()
  const first = useRef<HTMLAnchorElement>(null)
  useEffect(() => {
    if (autoFocus) first.current?.focus()
  }, [autoFocus])
  const buttonClass = `inline-flex min-h-11 items-center gap-1.5 rounded-lg px-3 text-sm ${tones[tone]}`

  if (files.length <= 1) {
    return (
      <a ref={first} href={fileUrl(jobId, 0)} download={files[0]} className={buttonClass}>
        <Icon name="save" className="size-4" />
        {t('job.save')}
      </a>
    )
  }

  return (
    <div className="flex w-full flex-col gap-1">
      <a ref={first} href={zipUrl(jobId)} download className={`${buttonClass} self-start`}>
        <Icon name="archive" className="size-4" />
        {t('job.saveZip')}
      </a>
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
              <a
                href={fileUrl(jobId, index)}
                download={name}
                aria-label={t('job.saveFile', { name })}
                className="grid size-11 shrink-0 place-items-center rounded-lg text-muted hover:bg-surface-2 hover:text-fg"
              >
                <Icon name="save" className="size-4" />
              </a>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
