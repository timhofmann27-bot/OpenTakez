// Mirrors the backend models in backend/app/providers/base.py and backend/app/jobs/manager.py.

export type DownloadOption = 'video-best' | 'video-1080' | 'video-720' | 'audio-mp3' | 'audio-m4a'

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
  kind: 'video' | 'audio'
  title: string
  uploader?: string | null
  thumbnail?: string | null
  duration?: number | null
  options: DownloadOption[]
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
  error?: string | null
  created_at: string
}

export const isFinished = (job: Job) => job.status === 'done' || job.status === 'error'
