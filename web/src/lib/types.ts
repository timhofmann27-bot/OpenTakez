// Mirrors the backend models in backend/app/providers/base.py and backend/app/jobs/manager.py.

export type DownloadOption =
  | 'video-best'
  | 'video-1080'
  | 'video-720'
  | 'audio-mp3'
  | 'audio-m4a'
  | 'image-original'

export type Health = {
  status: string
  version: string
  yt_dlp: string
  auth_required: boolean
  platforms: string[]
}

export type MediaInfo = {
  url: string
  platform: string
  kind: 'video' | 'audio' | 'image'
  title: string
  uploader?: string | null
  thumbnail?: string | null
  duration?: number | null
  options: DownloadOption[]
  /** Items that will be downloaded (null for a single item) and how many the source has. */
  item_count?: number | null
  total_items?: number | null
}

export type Progress = {
  phase: 'downloading' | 'processing'
  fraction?: number | null
  speed?: number | null
  eta?: number | null
}

export type JobStatus = 'queued' | 'running' | 'done' | 'error'

export type Job = {
  id: string
  url: string
  platform: string
  option: DownloadOption
  status: JobStatus
  progress: Progress | null
  files: string[]
  /** Byte size per entry in `files`. */
  sizes?: number[]
  error?: string | null
  /** Known failure, e.g. 'login_required' → cookies needed. */
  error_code?: string | null
  created_at: string
}

export type CookieStatus = {
  platform: string
  present: boolean
  uploaded_at?: string | null
  cookie_count: number
  earliest_expiry?: string | null
  expired: boolean
  domains: string[]
}

export type CookieUpload = CookieStatus & { dropped_count: number }

export const isFinished = (job: Job) => job.status === 'done' || job.status === 'error'
