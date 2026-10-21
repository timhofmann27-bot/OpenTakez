import { useState } from 'react'
import { useT } from '../i18n'
import { formatDuration } from '../lib/format'
import type { DownloadOption, MediaInfo } from '../lib/types'
import { Icon } from './Icon'
import { OptionPicker } from './OptionPicker'
import { PlatformChip } from './PlatformChip'
import { Thumbnail } from './Thumbnail'

type Props = {
  info: MediaInfo
  onDownload: (option: DownloadOption) => Promise<void>
}

export function PreviewCard({ info, onDownload }: Props) {
  const t = useT()
  const [option, setOption] = useState<DownloadOption>(info.options[0])
  const [starting, setStarting] = useState(false)
  const duration = formatDuration(info.duration)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setStarting(true)
    try {
      await onDownload(option)
    } finally {
      setStarting(false)
    }
  }

  return (
    <form
      onSubmit={submit}
      aria-label={info.title}
      className="flex flex-col gap-4 rounded-2xl border border-line bg-surface p-4"
    >
      <div className={info.kind === 'audio' ? 'flex gap-4' : 'flex flex-col gap-3'}>
        <Thumbnail
          src={info.thumbnail}
          kind={info.kind}
          className={info.kind === 'audio' ? 'w-24 shrink-0' : 'w-full'}
        />
        <div className="flex min-w-0 flex-col gap-1.5">
          <div className="flex flex-wrap items-center gap-2">
            <PlatformChip platform={info.platform} />
            {duration && <span className="text-xs text-muted tabular-nums">{duration}</span>}
          </div>
          <h2 className="line-clamp-2 text-base font-semibold leading-snug">{info.title}</h2>
          {info.uploader && <p className="truncate text-sm text-muted">{info.uploader}</p>}
        </div>
      </div>

      <OptionPicker options={info.options} value={option} onChange={setOption} />

      <button
        type="submit"
        disabled={starting}
        className="flex min-h-12 items-center justify-center gap-2 rounded-xl bg-accent font-semibold text-accent-fg transition-opacity disabled:opacity-60"
      >
        <Icon name="download" />
        {starting ? t('preview.starting') : t('preview.download')}
      </button>
    </form>
  )
}
