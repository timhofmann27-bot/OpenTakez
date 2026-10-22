import type { Page, Route } from '@playwright/test'

export type Job = {
  id: string
  url: string
  platform: string
  option: string
  status: 'queued' | 'running' | 'done' | 'error'
  progress: { phase: string; fraction: number | null; speed: number | null; eta: number | null } | null
  files: string[]
  sizes?: number[]
  error: string | null
  error_code?: string | null
  created_at: string
}

export const MEDIA = {
  url: 'https://youtu.be/abc',
  platform: 'youtube',
  kind: 'video',
  title: 'Never Gonna Test You Up',
  uploader: 'QA Channel',
  thumbnail: null,
  duration: 213,
  options: ['video-best', 'video-1080', 'video-720', 'audio-mp3', 'audio-m4a'],
}

export const HEALTH = {
  status: 'ok',
  version: '0.1.0',
  yt_dlp: '2026.8.19',
  auth_required: false,
  platforms: ['youtube', 'soundcloud'],
}

export type CookieStatus = {
  platform: string
  present: boolean
  uploaded_at: string | null
  cookie_count: number
  earliest_expiry: string | null
  expired: boolean
  domains: string[]
}

export type Mock = {
  health: Record<string, unknown>
  /** Replaces the default MediaInfo returned by resolve. */
  media: Record<string, unknown>
  /** Override resolve; `detail` may be a string or {message, code}. */
  resolveError: { status: number; detail: unknown } | null
  /** What a finished job looks like (default: one mp3). */
  result: { files: string[]; sizes?: number[] } | { error: string; error_code?: string }
  /** Cookie state served by /api/cookies. */
  cookies: Record<string, CookieStatus>
  /** Raw bodies received by PUT /api/cookies/{platform}. */
  cookiePuts: { platform: string; contentType: string | undefined; body: string }[]
  /** `dropped_count` returned by the next upload. */
  cookieDropped: number

  /** Status of GET /api/jobs/<id>; 'ok' returns the done job. */
  jobLookup: 'ok' | 'gone'
  /** Return the status for GET /api/jobs/token-check given the Authorization header. */
  tokenCheck: (authorization: string | undefined) => number
  resolveBodies: { url: string }[]
  jobBodies: { url: string; option: string }[]
  /** Tickets issued by POST /api/jobs/{id}/ticket. */
  tickets: { id: string; ticket: string }[]
  /** Full request URLs of events/files/zip requests, in order. */
  mediaRequests: string[]
  /** Authorization headers seen, per request path. */
  auth: { path: string; authorization: string | undefined }[]
}

const json = (route: Route, status: number, body: unknown) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

export function makeJob(
  id: string,
  option: string,
  status: Job['status'],
  result: Mock['result'] = { files: ['Never Gonna Test You Up.mp3'] },
  platform = 'youtube',
): Job {
  const failed = status === 'done' && 'error' in result
  return {
    id,
    url: MEDIA.url,
    platform,
    option,
    status: failed ? 'error' : status,
    progress: status === 'done' ? null : { phase: 'downloading', fraction: 0.5, speed: 1e6, eta: 3 },
    files: status === 'done' && 'files' in result ? result.files : [],
    sizes: status === 'done' && 'files' in result ? result.sizes : undefined,
    error: failed ? result.error : null,
    error_code: failed ? (result.error_code ?? null) : null,
    created_at: new Date().toISOString(),
  }
}

export const emptyCookie = (platform: string): CookieStatus => ({
  platform,
  present: false,
  uploaded_at: null,
  cookie_count: 0,
  earliest_expiry: null,
  expired: false,
  domains: [],
})

export async function mockApi(page: Page, overrides: Partial<Mock> = {}): Promise<Mock> {
  const mock: Mock = {
    health: HEALTH,
    media: MEDIA,
    resolveError: null,
    result: { files: ['Never Gonna Test You Up.mp3'] },
    cookies: Object.fromEntries(['instagram', 'tiktok', 'facebook'].map((p) => [p, emptyCookie(p)])),
    cookiePuts: [],
    cookieDropped: 0,
    jobLookup: 'ok',
    tokenCheck: () => 404,
    resolveBodies: [],
    jobBodies: [],
    tickets: [],
    mediaRequests: [],
    auth: [],
    ...overrides,
  }
  const jobs = new Map<string, Job>()
  let counter = 0

  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    const method = request.method()
    const authorization = request.headers()['authorization']
    mock.auth.push({ path: pathname, authorization })

    if (pathname === '/api/health') return json(route, 200, mock.health)

    if (pathname === '/api/resolve') {
      mock.resolveBodies.push(request.postDataJSON())
      if (mock.resolveError) return json(route, mock.resolveError.status, { detail: mock.resolveError.detail })
      return json(route, 200, mock.media)
    }

    const cookie = pathname.match(/^\/api\/cookies(?:\/([^/]+))?$/)
    if (cookie) {
      const platform = cookie[1]
      if (!platform) return json(route, 200, Object.values(mock.cookies))
      if (method === 'PUT') {
        const body = request.postData() ?? ''
        mock.cookiePuts.push({ platform, contentType: request.headers()['content-type'], body })
        if (!body.includes('# Netscape')) return json(route, 422, { detail: 'Not a valid cookies.txt' })
        mock.cookies[platform] = {
          ...emptyCookie(platform),
          present: true,
          cookie_count: 7,
          uploaded_at: new Date().toISOString(),
          earliest_expiry: '2027-03-01T00:00:00Z',
          domains: [`.${platform}.com`],
        }
        return json(route, 200, { ...mock.cookies[platform], dropped_count: mock.cookieDropped })
      }
      if (method === 'DELETE') {
        mock.cookies[platform] = emptyCookie(platform)
        return route.fulfill({ status: 204 })
      }
    }

    if (pathname === '/api/jobs' && method === 'POST') {
      const body = request.postDataJSON()
      mock.jobBodies.push(body)
      const id = `job${++counter}`
      jobs.set(id, makeJob(id, body.option, 'queued', mock.result, mock.media.platform as string))
      return json(route, 201, jobs.get(id))
    }

    if (pathname === '/api/jobs/token-check') {
      return json(route, mock.tokenCheck(authorization), { detail: 'x' })
    }

    const match = pathname.match(/^\/api\/jobs\/([^/]+)(\/events|\/files\/\d+|\/zip|\/ticket)?$/)
    if (match) {
      const [, id, sub] = match
      const job = jobs.get(id)
      if (sub === '/ticket') {
        if (method !== 'POST') return json(route, 405, { detail: 'Method not allowed' })
        if (!job) return json(route, 404, { detail: 'Job not found' })
        const ticket = `tk-${id}-${mock.tickets.length + 1}`
        mock.tickets.push({ id, ticket })
        return json(route, 200, { ticket, expires_at: new Date(Date.now() + 600_000).toISOString() })
      }
      if (sub) {
        // Events, files and ZIP take a ticket for this job; the master token is never accepted in URLs.
        const params = new URL(request.url()).searchParams
        mock.mediaRequests.push(request.url())
        const ticket = params.get('ticket')
        if (params.has('token') || !ticket || !mock.tickets.some((t) => t.id === id && t.ticket === ticket))
          return json(route, 401, { detail: 'Invalid or expired ticket' })
      }
      if (sub === '/events') {
        if (!job) return json(route, 404, { detail: 'Not found' })
        const frames = [
          { ...job, status: 'running', progress: { phase: 'downloading', fraction: 0.5, speed: 1e6, eta: 3 } },
          makeJob(id, job.option, 'done', mock.result, job.platform),
        ]
        return route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          headers: { 'cache-control': 'no-cache' },
          body: frames.map((f) => `data: ${JSON.stringify(f)}\n\n`).join(''),
        })
      }
      if (sub?.startsWith('/files/') || sub === '/zip') {
        return route.fulfill({ status: 200, contentType: 'application/octet-stream', body: 'x' })
      }
      if (method === 'DELETE') return route.fulfill({ status: 204 })
      if (!job || mock.jobLookup === 'gone') return json(route, 404, { detail: 'Not found' })
      return json(route, 200, makeJob(id, job.option, 'done', mock.result, job.platform))
    }

    return json(route, 404, { detail: 'unmocked' })
  })
  return mock
}
