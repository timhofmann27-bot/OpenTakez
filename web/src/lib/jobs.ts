import { ApiError, dropTicket, eventsUrl, ticketFor, api } from './api'
import { type HistoryItem, addHistoryOnce } from './history'
import { createStore, storage, useStore } from './store'
import { type DownloadOption, type Job, type MediaInfo, isFinished } from './types'

/** A server job plus the preview it was started from (the job itself has no title). */
export type JobView = {
  job: Job
  info: MediaInfo
  /** The server no longer knows this job (it restarted). */
  lost?: boolean
}

type Saved = { id: string; info: MediaInfo }

const KEY = 'opentakez.jobs'
const RECONNECT_MS = [1000, 2000, 5000, 10000, 30000]

export const jobsStore = createStore<JobView[]>([])
export const useJobs = () => useStore(jobsStore)

const streams = new Map<string, EventSource>()
const retries = new Map<string, { timer: ReturnType<typeof setTimeout>; attempt: number }>()
const generations = new Map<string, number>()
let nextGeneration = 0
const streamFailures = new Map<string, number>()
/** Saved jobs the server couldn't be asked about yet (offline); kept until it answers. */
let unresolved: Saved[] = []

function persist() {
  const views = jobsStore.get()
  const saved: Saved[] = [
    ...views.map(({ job, info }) => ({ id: job.id, info })),
    ...unresolved.filter((s) => !views.some((v) => v.job.id === s.id)),
  ]
  storage.set(KEY, JSON.stringify(saved))
}

function readSaved(): Saved[] {
  try {
    const value: unknown = JSON.parse(storage.get(KEY) ?? '[]')
    if (!Array.isArray(value)) return []
    return value.filter(
      (s): s is Saved =>
        typeof s?.id === 'string' && typeof s?.info?.url === 'string' && typeof s?.info?.title === 'string',
    )
  } catch {
    return []
  }
}

function patch(id: string, change: (view: JobView) => JobView) {
  jobsStore.set((prev) => prev.map((view) => (view.job.id === id ? change(view) : view)))
}

function historyItem(job: Job, info: MediaInfo): HistoryItem {
  return {
    id: job.id,
    url: info.url,
    title: info.title,
    uploader: info.uploader,
    thumbnail: info.thumbnail,
    platform: info.platform,
    kind: info.kind,
    option: job.option,
    fileName: job.files[0] ?? info.title,
    fileNames: job.files.length ? job.files : [info.title],
    createdAt: Date.now(),
  }
}

function apply(job: Job) {
  const view = jobsStore.get().find((v) => v.job.id === job.id)
  if (!view) return
  const justFinished = job.status === 'done' && view.job.status !== 'done'
  patch(job.id, (v) => ({ ...v, job, lost: false }))
  if (justFinished) void addHistoryOnce(historyItem(job, view.info))
}

function close(id: string) {
  generations.delete(id)
  streams.get(id)?.close()
  streams.delete(id)
  const retry = retries.get(id)
  if (retry) clearTimeout(retry.timer)
  retries.delete(id)
  streamFailures.delete(id)
  dropTicket(id)
}

function scheduleReconnect(id: string, minDelay = 0) {
  const attempt = retries.get(id)?.attempt ?? 0
  const previous = retries.get(id)
  if (previous) clearTimeout(previous.timer)
  const delay = Math.max(RECONNECT_MS[Math.min(attempt, RECONNECT_MS.length - 1)], minDelay)
  const timer = setTimeout(() => {
    if (retries.get(id)?.timer !== timer) return
    void watch(id, { keepAttempts: true })
  }, delay)
  retries.set(id, { timer, attempt: attempt + 1 })
}

/** What to do when the server can't be reached or refuses us while following a job. */
function handleFailure(id: string, error: unknown) {
  if (error instanceof ApiError && error.kind === 'notFound') {
    close(id)
    patch(id, (v) => ({ ...v, lost: true }))
  } else if (error instanceof ApiError && error.kind === 'unauthorized') {
    // Waits for resumeJobs() after a new token.
    close(id)
  } else {
    const wait = error instanceof ApiError && error.kind === 'rateLimited' ? (error.retryAfter ?? 0) * 1000 : 0
    scheduleReconnect(id, wait)
  }
}

/** Streams whose ticket is being requested, so a second watch() doesn't open a duplicate. */
const connecting = new Set<string>()

async function watch(id: string, { keepAttempts = false } = {}) {
  if (streams.has(id) || connecting.has(id) || !jobsStore.get().some((v) => v.job.id === id)) return
  if (!keepAttempts) {
    const retry = retries.get(id)
    if (retry) clearTimeout(retry.timer)
    retries.delete(id)
  }
  const generation = ++nextGeneration
  generations.set(id, generation)
  connecting.add(id)
  let ticket: string
  try {
    ticket = await ticketFor(id)
  } catch (error) {
    connecting.delete(id)
    if (generations.get(id) === generation && jobsStore.get().some((v) => v.job.id === id))
      handleFailure(id, error)
    return
  }
  connecting.delete(id)
  // The job may have been removed while the ticket was on its way.
  if (generations.get(id) !== generation || streams.has(id) || !jobsStore.get().some((v) => v.job.id === id)) return
  const source = new EventSource(eventsUrl(id, ticket))
  streams.set(id, source)

  source.onmessage = (event) => {
    if (streams.get(id) !== source || generations.get(id) !== generation) return
    let job: Job
    try {
      job = JSON.parse(event.data)
    } catch {
      return
    }
    const retry = retries.get(id)
    if (retry) clearTimeout(retry.timer)
    retries.delete(id)
    streamFailures.delete(id)
    apply(job)
    // The server ends the stream after the final state; without closing, the browser reconnects.
    if (isFinished(job)) close(id)
  }
  source.onerror = () => {
    if (streams.get(id) !== source || generations.get(id) !== generation) return
    // Dropped connection, server restart, or a 401/404 that EventSource can't report.
    source.close()
    streams.delete(id)
    const failures = (streamFailures.get(id) ?? 0) + 1
    streamFailures.set(id, failures)
    if (failures > 1) dropTicket(id)
    // A token change or removal can start a newer watch while getJob is pending.
    const stillCurrent = () =>
      generations.get(id) === generation && jobsStore.get().some((v) => v.job.id === id)
    api
      .getJob(id)
      .then((job) => {
        if (!stillCurrent()) return
        apply(job)
        if (isFinished(job)) close(id)
        else scheduleReconnect(id)
      })
      .catch((error) => {
        if (stillCurrent()) handleFailure(id, error)
      })
  }
}

export async function startJob(info: MediaInfo, option: DownloadOption): Promise<Job> {
  const job = await api.createJob(info.url, option)
  jobsStore.set((prev) => [{ job, info }, ...prev])
  persist()
  void watch(job.id)
  return job
}

/** Throws ApiError(409) while the job is still running. */
export async function removeJob(id: string) {
  const view = jobsStore.get().find((v) => v.job.id === id)
  if (view && !view.lost) {
    try {
      await api.deleteJob(id)
    } catch (error) {
      if (!(error instanceof ApiError && error.kind === 'notFound')) throw error
    }
  }
  close(id)
  jobsStore.set((prev) => prev.filter((v) => v.job.id !== id))
  persist()
}

export async function retryJob(view: JobView) {
  await startJob(view.info, view.job.option)
  await removeJob(view.job.id).catch(() => {})
}

/** Reconnect unfinished jobs, e.g. after the user entered a valid token. */
export function resumeJobs() {
  jobsStore
    .get()
    .filter((v) => !v.lost && !isFinished(v.job))
    .forEach((v) => void watch(v.job.id))
}

/** Restore jobs from a previous visit (once at startup) and reconnect to the unfinished ones. */
export async function restoreJobs() {
  const saved = readSaved()
  const results = await Promise.allSettled(saved.map(({ id }) => api.getJob(id)))

  const restored: JobView[] = []
  unresolved = []
  saved.forEach((entry, i) => {
    const result = results[i]
    if (result.status === 'fulfilled') restored.push({ job: result.value, info: entry.info })
    else if (!(result.reason instanceof ApiError && result.reason.kind === 'notFound'))
      unresolved.push(entry)
  })

  // Jobs started while we were waiting stay in front.
  jobsStore.set((current) => [
    ...current,
    ...restored.filter((r) => !current.some((v) => v.job.id === r.job.id)),
  ])
  persist()

  for (const view of restored) {
    // Finished while the app was closed: never seen a "done" event, so record it now.
    if (view.job.status === 'done') void addHistoryOnce(historyItem(view.job, view.info))
    else if (!isFinished(view.job)) void watch(view.job.id)
  }
}
