const colors: Record<string, string> = {
  youtube: 'bg-youtube',
  soundcloud: 'bg-soundcloud',
  tiktok: 'bg-tiktok',
  instagram: 'bg-instagram',
  facebook: 'bg-facebook',
  spotify: 'bg-spotify',
}

import { platformName } from '../lib/platforms'

export function PlatformChip({ platform, compact = false }: { platform: string; compact?: boolean }) {
  return (
    <span
      className={`inline-flex items-center rounded-full bg-surface-2 font-medium text-muted ring-1 ring-line ring-inset ${compact ? 'gap-1 py-0.5 pr-1.5 pl-1 text-[0.7rem]' : 'gap-1.5 py-0.5 pr-2 pl-1.5 text-xs'}`}
    >
      <span className={`size-1.5 rounded-full ${colors[platform] ?? 'bg-muted'}`} aria-hidden="true" />
      {platformName(platform)}
    </span>
  )
}
