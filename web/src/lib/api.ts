import { settingsStore } from './settings'
import type { CookieStatus, Ticket, CookieUpload, DownloadOption, Health, Job, MediaInfo } from './types'

export type ApiErrorKind =
  | 'badRequest'
  | 'unauthorized'
  | 'platform'
  | 'conflict'
  | 'notFound'
  | 'network'
  | 'busy'
  | 'rateLimited'
  | 'server'

export class ApiError extends Error {
  readonly status: number
  readonly kind: ApiErrorKind
  /** Machine-readable reason for known failures, e.g. 'login_required'. */
  readonly code?: string
  /** Seconds to wait before retrying, from the Retry-After header of a 429. */
  readonly retryAfter?: number

  constructor(status: number, message: string, code?: string, retryAfter?: number) {
    super(message)
    this.status = status
    this.code = code
    this.retryAfter = retryAfter
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
    case 429:
      return 'rateLimited'
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

function parseRetryAfter(response: Response): number | undefined {
  if (response.status !== 429) return undefined
  const seconds = Number.parseInt(response.headers.get('Retry-After') ?? '', 10)
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : undefined
}

/** Called on every 401 so the app can ask for a token. */
let onUnauthorized: () => void = () => {}
export const setUnauthorizedHandler = (handler: () => void) => {
  onUnauthorized = handler
}

const base = () => settingsStore.get().serverUrl

type Target = { base?: string; token?: string; onResponse?: (response: Response) => void }

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
  target.onResponse?.(response)
  if (!response.ok) {
    // Ignore 401s for a token that has since been replaced, or for an explicit candidate.
    if (response.status === 401 && !target.token && token === settingsStore.get().token)
      onUnauthorized()
    const { message, code } = await response
      .json()
      .then((body) => parseDetail(body?.detail))
      .catch(() => parseDetail(undefined))
    throw new ApiError(response.status, message, code, parseRetryAfter(response))
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
  /** A short-lived ticket for the job's events, files and ZIP links (which can't send headers). */
  ticket: async (id: string): Promise<Ticket> => {
    let serverDate: string | null = null
    const body = await request<Ticket>(
      `/api/jobs/${encodeURIComponent(id)}/ticket`,
      { method: 'POST' },
      { onResponse: (response) => void (serverDate = response.headers.get('Date')) },
    )
    // The server's own clock, so the lifetime doesn't depend on this device's clock being right.
    return serverDate ? { ...body, server_date: serverDate } : body
  },
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

const TICKET_MARGIN_MS = 30_000
/** `expiresAt` is on the local clock, derived from the lifetime the server reported. */
const tickets = new Map<string, { ticket: string; expiresAt: number }>()
/** Requests on their way. Dropping a job's entry also detaches its request from the cache. */
const inflight = new Map<string, Promise<string>>()

/** Forget the cached ticket (and ignore one on its way), e.g. after the job went away. */
export function dropTicket(id: string) {
  tickets.delete(id)
  inflight.delete(id)
}

/** For tests: forget every ticket. */
export function __resetTickets() {
  tickets.clear()
  inflight.clear()
}

function sweepTickets(now: number) {
  for (const [id, entry] of tickets) if (entry.expiresAt <= now) tickets.delete(id)
}

/**
 * Remaining lifetime in ms. Uses the server's `Date` header against `expires_at` so a wrong
 * device clock doesn't matter; without the header the two clocks are assumed to agree.
 * NaN when `expires_at` can't be parsed.
 */
function lifetimeMs({ expires_at, server_date }: Ticket, now: number): number {
  const expires = Date.parse(expires_at)
  const serverNow = server_date ? Date.parse(server_date) : NaN
  return expires - (Number.isFinite(serverNow) ? serverNow : now)
}

/** A valid ticket for the job: cached until ~30 s before it expires, otherwise requested anew. */
export function ticketFor(id: string): Promise<string> {
  const cached = tickets.get(id)
  if (cached && cached.expiresAt - TICKET_MARGIN_MS > Date.now()) return Promise.resolve(cached.ticket)
  tickets.delete(id)
  const pending = inflight.get(id)
  if (pending) return pending

  const promise = api.ticket(id).then((response) => {
    const received = Date.now()
    // Only cache if nobody dropped the job's tickets meanwhile.
    if (inflight.get(id) === promise) {
      sweepTickets(received)
      const lifetime = lifetimeMs(response, received)
      // An unparseable expiry is never cached, so it is never reused.
      if (Number.isFinite(lifetime)) tickets.set(id, { ticket: response.ticket, expiresAt: received + lifetime })
    }
    return response.ticket
  })
  inflight.set(id, promise)
  const settle = () => {
    if (inflight.get(id) === promise) inflight.delete(id)
  }
  promise.then(settle, settle)
  return promise
}

/** For EventSource and downloads, which can't send an Authorization header. */
function withTicket(path: string, ticket: string): string {
  return `${base()}${path}?ticket=${encodeURIComponent(ticket)}`
}

export const eventsUrl = (id: string, ticket: string) =>
  withTicket(`/api/jobs/${encodeURIComponent(id)}/events`, ticket)
export const fileUrl = (id: string, index: number, ticket: string) =>
  withTicket(`/api/jobs/${encodeURIComponent(id)}/files/${index}`, ticket)
export const zipUrl = (id: string, ticket: string) =>
  withTicket(`/api/jobs/${encodeURIComponent(id)}/zip`, ticket)
