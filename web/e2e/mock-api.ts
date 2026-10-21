import type { Page, Route } from '@playwright/test'

export type Job = {
  id: string
  url: string
  platform: string
  option: string
  status: 'queued' | 'running' | 'done' | 'error'
  progress: { phase: string; fraction: number | null; speed: number | null; eta: number | null } | null
  files: string[]
  error: string | null
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

export type Mock = {
  health: Record<string, unknown>
  /** Override resolve; return null to use default MediaInfo. */
  resolveError: { status: number; detail: string } | null
  /** Status of GET /api/jobs/<id>; 'ok' returns the done job. */
  jobLookup: 'ok' | 'gone'
  /** Return the status for GET /api/jobs/token-check given the Authorization header. */
  tokenCheck: (authorization: string | undefined) => number
  resolveBodies: { url: string }[]
  jobBodies: { url: string; option: string }[]
  /** Authorization headers seen, per request path. */
  auth: { path: string; authorization: string | undefined }[]
}

const json = (route: Route, status: number, body: unknown) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

export function makeJob(id: string, option: string, status: Job['status']): Job {
  return {
    id,
    url: MEDIA.url,
    platform: 'youtube',
    option,
    status,
    progress: status === 'done' ? null : { phase: 'downloading', fraction: 0.5, speed: 1e6, eta: 3 },
    files: status === 'done' ? ['Never Gonna Test You Up.mp3'] : [],
    error: null,
    created_at: new Date().toISOString(),
  }
}

export async function mockApi(page: Page, overrides: Partial<Mock> = {}): Promise<Mock> {
  const mock: Mock = {
    health: HEALTH,
    resolveError: null,
    jobLookup: 'ok',
    tokenCheck: () => 404,
    resolveBodies: [],
    jobBodies: [],
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
      return json(route, 200, MEDIA)
    }

    if (pathname === '/api/jobs' && method === 'POST') {
      const body = request.postDataJSON()
      mock.jobBodies.push(body)
      const id = `job${++counter}`
      jobs.set(id, makeJob(id, body.option, 'queued'))
      return json(route, 201, jobs.get(id))
    }

    if (pathname === '/api/jobs/token-check') {
      return json(route, mock.tokenCheck(authorization), { detail: 'x' })
    }

    const match = pathname.match(/^\/api\/jobs\/([^/]+)(\/events|\/file)?$/)
    if (match) {
      const [, id, sub] = match
      const job = jobs.get(id)
      if (sub === '/events') {
        if (!job) return json(route, 404, { detail: 'Not found' })
        const frames = [
          { ...job, status: 'running', progress: { phase: 'downloading', fraction: 0.5, speed: 1e6, eta: 3 } },
          makeJob(id, job.option, 'done'),
        ]
        return route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          headers: { 'cache-control': 'no-cache' },
          body: frames.map((f) => `data: ${JSON.stringify(f)}\n\n`).join(''),
        })
      }
      if (sub === '/file') return route.fulfill({ status: 200, contentType: 'audio/mpeg', body: 'x' })
      if (method === 'DELETE') return route.fulfill({ status: 204 })
      if (!job || mock.jobLookup === 'gone') return json(route, 404, { detail: 'Not found' })
      return json(route, 200, makeJob(id, job.option, 'done'))
    }

    return json(route, 404, { detail: 'unmocked' })
  })
  return mock
}
