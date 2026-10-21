import { settingsStore } from './settings'
import type { CookieStatus, CookieUpload, DownloadOption, Health, Job, MediaInfo } from './types'

export type ApiErrorKind =
  | 'badRequest'
  | 'unauthorized'
  | 'platform'
  | 'conflict'
  | 'notFound'
  | 'network'
  | 'busy'
  | 'server'

export class ApiError extends Error {
  readonly status: number
  readonly kind: ApiErrorKind
  /** Machine-readable reason for known failures, e.g. 'login_required'. */
  readonly code?: string

  constructor(status: number, message: string, code?: string) {
    super(message)
    this.status = status
    this.code = code
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
    case 503:
      return 'busy'
    default:
      return 'server'
  }
}

/** FastAPI `detail` is a string, or `{message, code}` for known failures. */
function parseDetail(detail: unknown): { message: string; code?: string } {
  if (typeof detail === 'string') return { message: detail }
  if (detail && typeof detail === 'object') {
    const { message, code } = detail as { message?: unknown; code?: unknown }
    return {
      message: typeof message === 'string' ? message : '',
      code: typeof code === 'string' ? code : undefined,
    }
  }
  return { message: '' }
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
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')

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
    const { message, code } = await response
      .json()
      .then((body) => parseDetail(body?.detail))
      .catch(() => parseDetail(undefined))
    throw new ApiError(response.status, message, code)
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
  cookies: {
    list: () => request<CookieStatus[]>('/api/cookies'),
    /** Sends the raw cookies.txt; the caller must not keep the text around. */
    put: (platform: string, text: string) =>
      request<CookieUpload>(`/api/cookies/${encodeURIComponent(platform)}`, {
        method: 'PUT',
        body: text,
        headers: { 'Content-Type': 'text/plain' },
      }),
    remove: (platform: string) =>
      request<void>(`/api/cookies/${encodeURIComponent(platform)}`, { method: 'DELETE' }),
  },
}

/** For EventSource and plain links, which can't send an Authorization header. */
function withToken(path: string): string {
  const { token } = settingsStore.get()
  return `${base()}${path}${token ? `?token=${encodeURIComponent(token)}` : ''}`
}

export const eventsUrl = (id: string) => withToken(`/api/jobs/${encodeURIComponent(id)}/events`)
export const fileUrl = (id: string, index = 0) =>
  withToken(`/api/jobs/${encodeURIComponent(id)}/files/${index}`)
export const zipUrl = (id: string) => withToken(`/api/jobs/${encodeURIComponent(id)}/zip`)
