import { useState } from 'react'
import { Icon } from './Icon'

type Props = { src?: string | null; kind: 'video' | 'audio'; className?: string }

/** Fixed aspect ratio (16:9 video, 1:1 audio) so layout doesn't jump while the image loads. */
export function Thumbnail({ src, kind, className = '' }: Props) {
  const [failed, setFailed] = useState(false)
  const aspect = kind === 'video' ? 'aspect-video' : 'aspect-square'
  return (
    <div className={`${aspect} overflow-hidden rounded-lg bg-surface-2 ${className}`}>
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
          <Icon name={kind === 'video' ? 'film' : 'music'} className="size-1/3 max-h-10" />
        </div>
      )}
    </div>
  )
}
