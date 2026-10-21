import { settingsStore } from './settings'
import type { DownloadOption, Health, Job, MediaInfo } from './types'

export type ApiErrorKind =
  | 'badRequest'
  | 'unauthorized'
  | 'platform'
  | 'conflict'
  | 'notFound'
  | 'network'
  | 'server'

export class ApiError extends Error {
  readonly status: number
  readonly kind: ApiErrorKind

  constructor(status: number, message: string) {
    super(message)
    this.status = status
    this.kind = kindFor(status)
  }
}

function kindFor(status: number): ApiErrorKind {
  switch (status) {
    case 0:
      return 'network'
    case 400:
      return 'badRequest'
    case 401:
      return 'unauthorized'
    case 404:
      return 'notFound'
    case 409:
      return 'conflict'
    case 422:
      return 'platform'
    default:
      return 'server'
  }
}

/** Called on every 401 so the app can ask for a token. */
let onUnauthorized: () => void = () => {}
export const setUnauthorizedHandler = (handler: () => void) => {
  onUnauthorized = handler
}

const base = () => settingsStore.get().serverUrl

type Target = { base?: string; token?: string }

async function request<T>(path: string, init: RequestInit = {}, target: Target = {}): Promise<T> {
  const token = target.token ?? settingsStore.get().token
  const headers = new Headers(init.headers)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (init.body) headers.set('Content-Type', 'application/json')

  let response: Response
  try {
    response = await fetch(`${target.base ?? base()}${path}`, { ...init, headers })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError(0, 'network')
  }
  if (!response.ok) {
    // Ignore 401s for a token that has since been replaced, or for an explicit candidate.
    if (response.status === 401 && !target.token && token === settingsStore.get().token)
      onUnauthorized()
    const detail = await response
      .json()
      .then((body) => (typeof body?.detail === 'string' ? body.detail : ''))
      .catch(() => '')
    throw new ApiError(response.status, detail)
  }
  return response.status === 204 ? (undefined as T) : response.json()
}

const post = (body: unknown, signal?: AbortSignal): RequestInit => ({
  method: 'POST',
  body: JSON.stringify(body),
  signal,
})

export const api = {
  health: (signal?: AbortSignal, base?: string) => request<Health>('/api/health', { signal }, { base }),
  resolve: (text: string, signal?: AbortSignal) =>
    request<MediaInfo>('/api/resolve', post({ url: text }, signal)),
  createJob: (url: string, option: DownloadOption) =>
    request<Job>('/api/jobs', post({ url, option })),
  getJob: (id: string) => request<Job>(`/api/jobs/${encodeURIComponent(id)}`),
  /** Resolves if the current token is accepted (a 404 for an unknown job means we got past auth). */
  checkToken: (token: string) =>
    request<void>('/api/jobs/token-check', {}, { token }).catch((error) => {
      if (!(error instanceof ApiError && error.kind === 'notFound')) throw error
    }),
  deleteJob: (id: string) =>
    request<void>(`/api/jobs/${encodeURIComponent(id)}`, { method: 'DELETE' }),
}

/** For EventSource and plain links, which can't send an Authorization header. */
function withToken(path: string): string {
  const { token } = settingsStore.get()
  return `${base()}${path}${token ? `?token=${encodeURIComponent(token)}` : ''}`
}

export const eventsUrl = (id: string) => withToken(`/api/jobs/${encodeURIComponent(id)}/events`)
export const fileUrl = (id: string) => withToken(`/api/jobs/${encodeURIComponent(id)}/file`)
