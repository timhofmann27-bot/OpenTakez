import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from './api'
import { historyStore } from './history'
import { jobsStore, removeJob, restoreJobs, startJob } from './jobs'
import type { Job, MediaInfo } from './types'

class FakeEventSource {
  static instances: FakeEventSource[] = []
  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: (() => void) | null = null
  closed = false
  url: string

  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }
  emit(job: Job) {
    this.onmessage?.(new MessageEvent('message', { data: JSON.stringify(job) }))
  }
  close() {
    this.closed = true
  }
  static latest() {
    return FakeEventSource.instances.at(-1)!
  }
}

const info: MediaInfo = {
  url: 'https://www.youtube.com/watch?v=abc',
  platform: 'youtube',
  kind: 'video',
  title: 'A video',
  thumbnail: 'https://i.ytimg.com/abc.jpg',
  options: ['video-best', 'audio-mp3'],
}

const job = (patch: Partial<Job> = {}): Job => ({
  id: 'j1',
  url: info.url,
  platform: 'youtube',
  option: 'audio-mp3',
  status: 'queued',
  progress: null,
  files: [],
  created_at: '2026-10-21T20:00:00Z',
  ...patch,
})

beforeEach(() => {
  FakeEventSource.instances = []
  vi.stubGlobal('EventSource', FakeEventSource)
  jobsStore.set([])
  historyStore.set([])
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('jobs store', () => {
  it('follows SSE updates and closes the stream when the job is done', async () => {
    vi.spyOn(api, 'createJob').mockResolvedValue(job())

    await startJob(info, 'audio-mp3')
    const source = FakeEventSource.latest()
    source.emit(job({ status: 'running', progress: { phase: 'downloading', fraction: 0.4 } }))

    expect(source.url).toBe('/api/jobs/j1/events')
    expect(jobsStore.get()[0].job.progress?.fraction).toBe(0.4)

    source.emit(job({ status: 'done', files: ['A video [abc].mp3'] }))

    expect(jobsStore.get()[0].job.status).toBe('done')
    expect(source.closed).toBe(true)
  })

  it('writes exactly one history entry per finished job', async () => {
    vi.spyOn(api, 'createJob').mockResolvedValue(job())
    await startJob(info, 'audio-mp3')
    const done = job({ status: 'done', files: ['A video [abc].mp3'] })

    FakeEventSource.latest().emit(done)
    FakeEventSource.latest().emit(done)

    await vi.waitFor(() => expect(historyStore.get()).toHaveLength(1))
    expect(historyStore.get()[0]).toMatchObject({
      id: 'j1',
      title: 'A video',
      option: 'audio-mp3',
      fileName: 'A video [abc].mp3',
    })
  })

  it('marks the job lost when the server no longer knows it after a dropped stream', async () => {
    vi.spyOn(api, 'createJob').mockResolvedValue(job())
    vi.spyOn(api, 'getJob').mockRejectedValue(new ApiError(404, 'Job not found'))
    await startJob(info, 'audio-mp3')

    FakeEventSource.latest().onerror?.()
    await vi.waitFor(() => expect(jobsStore.get()[0].lost).toBe(true))
  })

  it('reconnects after a dropped stream while the job is still running', async () => {
    vi.useFakeTimers()
    vi.spyOn(api, 'createJob').mockResolvedValue(job())
    vi.spyOn(api, 'getJob').mockResolvedValue(job({ status: 'running' }))
    await startJob(info, 'audio-mp3')
    const first = FakeEventSource.latest()

    first.onerror?.()
    await vi.runAllTimersAsync()

    expect(first.closed).toBe(true)
    expect(FakeEventSource.instances).toHaveLength(2)
  })

  it('keeps a running job when the server refuses to delete it', async () => {
    vi.spyOn(api, 'createJob').mockResolvedValue(job())
    vi.spyOn(api, 'deleteJob').mockRejectedValue(new ApiError(409, 'Job is still running'))
    await startJob(info, 'audio-mp3')

    await expect(removeJob('j1')).rejects.toMatchObject({ kind: 'conflict' })
    expect(jobsStore.get()).toHaveLength(1)
  })

  it('restores saved jobs, drops ones the server forgot, and keeps all when offline', async () => {
    localStorage.setItem(
      'opentakez.jobs',
      JSON.stringify([
        { id: 'r1', info },
        { id: 'gone', info },
      ]),
    )
    const getJob = vi.spyOn(api, 'getJob')
    getJob.mockImplementation(async (id) => {
      if (id === 'gone') throw new ApiError(404, 'Job not found')
      return job({ id, status: 'running' })
    })

    await restoreJobs()

    expect(jobsStore.get().map((v) => v.job.id)).toEqual(['r1'])
    expect(JSON.parse(localStorage.getItem('opentakez.jobs')!)).toHaveLength(1)
    expect(FakeEventSource.latest().url).toBe('/api/jobs/r1/events')

    jobsStore.set([])
    localStorage.setItem('opentakez.jobs', JSON.stringify([{ id: 'r2', info }]))
    getJob.mockRejectedValue(new ApiError(0, 'network'))

    await restoreJobs()

    expect(JSON.parse(localStorage.getItem('opentakez.jobs')!)).toEqual([{ id: 'r2', info }])
  })

  it('records jobs that finished while the app was closed in the history', async () => {
    localStorage.setItem('opentakez.jobs', JSON.stringify([{ id: 'closed', info }]))
    vi.spyOn(api, 'getJob').mockResolvedValue(
      job({ id: 'closed', status: 'done', files: ['A video [abc].mp3'] }),
    )

    await restoreJobs()

    await vi.waitFor(() => expect(historyStore.get().map((i) => i.id)).toEqual(['closed']))
  })

  it('ignores corrupt saved data', async () => {
    localStorage.setItem('opentakez.jobs', '{"not":"a list"}')

    await expect(restoreJobs()).resolves.toBeUndefined()
  })

  it('keeps a job started while restoring', async () => {
    localStorage.setItem('opentakez.jobs', JSON.stringify([{ id: 'old', info }]))
    let release!: () => void
    vi.spyOn(api, 'getJob').mockImplementation(
      (id) => new Promise((resolve) => (release = () => resolve(job({ id, status: 'done' })))),
    )
    vi.spyOn(api, 'createJob').mockResolvedValue(job({ id: 'new' }))

    const restoring = restoreJobs()
    await startJob(info, 'audio-mp3')
    release()
    await restoring

    expect(jobsStore.get().map((v) => v.job.id)).toEqual(['new', 'old'])
  })
})
