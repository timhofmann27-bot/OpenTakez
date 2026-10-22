import { useState } from 'react'
import type { MediaInfo } from '../lib/types'
import { Icon, type IconName } from './Icon'

type Props = { src?: string | null; kind: MediaInfo['kind']; className?: string }

const placeholder: Record<MediaInfo['kind'], IconName> = { video: 'film', audio: 'music', image: 'image' }

/** Fixed aspect ratio (16:9 video, 1:1 audio/photos) so layout doesn't jump while loading. */
export function Thumbnail({ src, kind, className = '' }: Props) {
  const [failed, setFailed] = useState(false)
  const aspect = kind === 'video' ? 'aspect-video' : 'aspect-square'
  return (
    <div className={`${aspect} overflow-hidden rounded-xl bg-surface-2 ring-1 ring-line ring-inset ${className}`}>
      {src && !failed ? (
        <img
          src={src}
          alt=""
          loading="lazy"
          referrerPolicy="no-referrer"
          onError={() => setFailed(true)}
          className="size-full object-cover"
        />
      ) : (
        <div className="grid size-full place-items-center text-muted">
          <Icon name={placeholder[kind]} className="size-1/3 max-h-10" />
        </div>
      )}
    </div>
  )
}
