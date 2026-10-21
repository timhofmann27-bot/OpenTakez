const names: Record<string, string> = {
  youtube: 'YouTube',
  soundcloud: 'SoundCloud',
  tiktok: 'TikTok',
  instagram: 'Instagram',
  facebook: 'Facebook',
  spotify: 'Spotify',
}

export const platformName = (platform: string) => names[platform] ?? platform
