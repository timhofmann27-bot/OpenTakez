const colors: Record<string, string> = {
  youtube: 'bg-youtube',
  soundcloud: 'bg-soundcloud',
  tiktok: 'bg-tiktok',
  instagram: 'bg-instagram',
  facebook: 'bg-facebook',
  spotify: 'bg-spotify',
}

const names: Record<string, string> = {
  youtube: 'YouTube',
  soundcloud: 'SoundCloud',
  tiktok: 'TikTok',
  instagram: 'Instagram',
  facebook: 'Facebook',
  spotify: 'Spotify',
}

export function PlatformChip({ platform }: { platform: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full bg-surface-2 px-2 py-0.5 text-xs font-medium text-muted">
      <span className={`size-2 rounded-full ${colors[platform] ?? 'bg-muted'}`} aria-hidden="true" />
      {names[platform] ?? platform}
    </span>
  )
}
