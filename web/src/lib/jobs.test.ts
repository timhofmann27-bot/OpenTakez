import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, __resetTickets, api } from './api'
import { historyStore } from './history'
import { jobsStore, removeJob, restoreJobs, resumeJobs, startJob } from './jobs'
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

let ticketCount = 0

beforeEach(() => {
  FakeEventSource.instances = []
  vi.stubGlobal('EventSource', FakeEventSource)
  jobsStore.set([])
  historyStore.set([])
  __resetTickets()
  ticketCount = 0
  vi.spyOn(api, 'ticket').mockImplementation(async () => ({
    ticket: `tk${++ticketCount}`,
    expires_at: new Date(Date.now() + 600_000).toISOString(),
  }))
})
afterEach(async () => {
  // Close leftover streams and reconnect timers so jobs don't leak into the next test.
  vi.spyOn(api, 'deleteJob').mockResolvedValue(undefined)
  for (const { job } of jobsStore.get()) await removeJob(job.id).catch(() => {})
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

const sourceReady = () => vi.waitFor(() => expect(FakeEventSource.instances.length).toBeGreaterThan(0))

describe('jobs store', () => {
  it('follows SSE updates and closes the stream when the job is done', async () => {
    vi.spyOn(api, 'createJob').mockResolvedValue(job())

    await startJob(info, 'audio-mp3')
    await sourceReady()
    const source = FakeEventSource.latest()
    source.emit(job({ status: 'running', progress: { phase: 'downloading', fraction: 0.4 } }))

    expect(source.url).toBe('/api/jobs/j1/events?ticket=tk1')
    expect(jobsStore.get()[0].job.progress?.fraction).toBe(0.4)

    source.emit(job({ status: 'done', files: ['A video [abc].mp3'] }))

    expect(jobsStore.get()[0].job.status).toBe('done')
    expect(source.closed).toBe(true)
  })

  it('writes exactly one history entry per finished job', async () => {
    vi.spyOn(api, 'createJob').mockResolvedValue(job())
    await startJob(info, 'audio-mp3')
    await sourceReady()
    const done = job({ status: 'done', files: ['A video [abc].mp3'] })

    FakeEventSource.latest().emit(done)
    FakeEventSource.latest().emit(done)

    await vi.waitFor(() => expect(historyStore.get()).toHaveLength(1))
    expect(historyStore.get()[0]).toMatchObject({
      id: 'j1',
      title: 'A video',
      option: 'audio-mp3',
      fileName: 'A video [abc].mp3',
      fileNames: ['A video [abc].mp3'],
    })
  })

  it('records every file of a multi-file job in the history', async () => {
    vi.spyOn(api, 'createJob').mockResolvedValue(job({ id: 'multi' }))
    await startJob(info, 'audio-mp3')
    await sourceReady()

    FakeEventSource.latest().emit(job({ id: 'multi', status: 'done', files: ['1.jpg', '2.jpg', '3.jpg'] }))

    await vi.waitFor(() => expect(historyStore.get()).toHaveLength(1))
    expect(historyStore.get()[0]).toMatchObject({ fileName: '1.jpg', fileNames: ['1.jpg', '2.jpg', '3.jpg'] })
  })

  it('marks the job lost when the server no longer knows it after a dropped stream', async () => {
    vi.spyOn(api, 'createJob').mockResolvedValue(job())
    vi.spyOn(api, 'getJob').mockRejectedValue(new ApiError(404, 'Job not found'))
    await startJob(info, 'audio-mp3')
    await sourceReady()

    FakeEventSource.latest().onerror?.()
    await vi.waitFor(() => expect(jobsStore.get()[0].lost).toBe(true))
  })

  it('reconnects after a dropped stream while the job is still running', async () => {
    vi.useFakeTimers()
    vi.spyOn(api, 'createJob').mockResolvedValue(job())
    vi.spyOn(api, 'getJob').mockResolvedValue(job({ status: 'running' }))
    await startJob(info, 'audio-mp3')
    await vi.advanceTimersByTimeAsync(0)
    const first = FakeEventSource.latest()

    first.onerror?.()
    await vi.runAllTimersAsync()

    expect(first.closed).toBe(true)
    expect(FakeEventSource.instances).toHaveLength(2)
  })

  it('reuses the ticket on the first drop and refreshes it after a second drop', async () => {
    vi.useFakeTimers()
    vi.spyOn(api, 'createJob').mockResolvedValue(job())
    vi.spyOn(api, 'getJob').mockResolvedValue(job({ status: 'running' }))
    await startJob(info, 'audio-mp3')
    await vi.advanceTimersByTimeAsync(0)
    const first = FakeEventSource.latest()
    expect(first.url).toBe('/api/jobs/j1/events?ticket=tk1')

    first.onerror?.()
    await vi.runAllTimersAsync()

    expect(FakeEventSource.instances).toHaveLength(2)
    expect(FakeEventSource.latest().url).toBe('/api/jobs/j1/events?ticket=tk1')
    FakeEventSource.latest().onerror?.()
    await vi.runAllTimersAsync()
    expect(FakeEventSource.latest().url).toBe('/api/jobs/j1/events?ticket=tk2')
    expect(FakeEventSource.instances.every((s) => !s.url.includes('token='))).toBe(true)
  })

  it('cancels a pending reconnect when resumeJobs opens a stream', async () => {
    vi.useFakeTimers()
    vi.spyOn(api, 'createJob').mockResolvedValue(job())
    vi.spyOn(api, 'ticket')
      .mockRejectedValueOnce(new ApiError(0, 'network'))
      .mockResolvedValue({ ticket: 'ok', expires_at: new Date(Date.now() + 600_000).toISOString() })
    await startJob(info, 'audio-mp3')
    await vi.advanceTimersByTimeAsync(0)
    resumeJobs()
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(1000)

    expect(FakeEventSource.instances.filter((s) => !s.closed)).toHaveLength(1)
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('ignores a late health check after the job was removed', async () => {
    vi.useFakeTimers()
    vi.spyOn(api, 'createJob').mockResolvedValue(job())
    vi.spyOn(api, 'deleteJob').mockResolvedValue(undefined)
    let finish!: (value: Job) => void
    vi.spyOn(api, 'getJob').mockReturnValue(new Promise((resolve) => { finish = resolve }))
    await startJob(info, 'audio-mp3')
    await vi.advanceTimersByTimeAsync(0)

    FakeEventSource.latest().onerror?.()
    await removeJob('j1')
    finish(job({ status: 'running' }))
    await vi.advanceTimersByTimeAsync(0)

    expect(jobsStore.get()).toHaveLength(0)
    expect(vi.getTimerCount()).toBe(0)
    expect(FakeEventSource.instances).toHaveLength(1)
  })

  it('ignores a stale unauthorized check after a newer stream opens', async () => {
    vi.useFakeTimers()
    vi.spyOn(api, 'createJob').mockResolvedValue(job())
    let fail!: (reason: ApiError) => void
    vi.spyOn(api, 'getJob').mockReturnValue(new Promise((_, reject) => { fail = reject }))
    await startJob(info, 'audio-mp3')
    await vi.advanceTimersByTimeAsync(0)

    FakeEventSource.latest().onerror?.()
    resumeJobs()
    await vi.advanceTimersByTimeAsync(0)
    fail(new ApiError(401, 'old token'))
    await vi.advanceTimersByTimeAsync(0)

    expect(FakeEventSource.instances.filter((s) => !s.closed)).toHaveLength(1)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('honors Retry-After when the ticket request is rate limited', async () => {
    vi.useFakeTimers()
    vi.spyOn(api, 'createJob').mockResolvedValue(job())
    vi.spyOn(api, 'ticket')
      .mockRejectedValueOnce(new ApiError(429, 'rate limit', undefined, 12))
      .mockResolvedValue({ ticket: 'ok', expires_at: new Date(Date.now() + 600_000).toISOString() })
    await startJob(info, 'audio-mp3')
    await vi.advanceTimersByTimeAsync(0)

    await vi.advanceTimersByTimeAsync(11_999)
    expect(FakeEventSource.instances).toHaveLength(0)
    await vi.advanceTimersByTimeAsync(1)
    expect(FakeEventSource.instances).toHaveLength(1)
  })

  it('does not open a stream when the ticket request is refused with 401', async () => {
    vi.spyOn(api, 'ticket').mockRejectedValue(new ApiError(401, 'Invalid or missing token'))
    vi.spyOn(api, 'createJob').mockResolvedValue(job())
    await startJob(info, 'audio-mp3')
    await new Promise((r) => setTimeout(r, 0))

    expect(FakeEventSource.instances).toHaveLength(0)
  })

  it('marks the job lost when the ticket request says it is gone', async () => {
    vi.spyOn(api, 'ticket').mockRejectedValue(new ApiError(404, 'Job not found'))
    vi.spyOn(api, 'createJob').mockResolvedValue(job())
    await startJob(info, 'audio-mp3')

    await vi.waitFor(() => expect(jobsStore.get()[0].lost).toBe(true))
    expect(FakeEventSource.instances).toHaveLength(0)
  })

  it('retries later when the ticket request fails with a network error', async () => {
    vi.useFakeTimers()
    vi.spyOn(api, 'ticket')
      .mockRejectedValueOnce(new ApiError(0, 'network'))
      .mockResolvedValue({ ticket: 'ok', expires_at: new Date(Date.now() + 600_000).toISOString() })
    vi.spyOn(api, 'createJob').mockResolvedValue(job())
    await startJob(info, 'audio-mp3')
    await vi.runAllTimersAsync()

    expect(FakeEventSource.latest().url).toBe('/api/jobs/j1/events?ticket=ok')
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
    await sourceReady()
    expect(FakeEventSource.latest().url).toMatch(/^\/api\/jobs\/r1\/events\?ticket=tk\d+$/)

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
