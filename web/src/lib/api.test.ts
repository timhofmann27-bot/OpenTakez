import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, __resetTickets, api, dropTicket, eventsUrl, fileUrl, setUnauthorizedHandler, ticketFor, zipUrl } from './api'
import { updateSettings } from './settings'

const fetchMock = vi.fn()

function respond(status: number, body?: unknown) {
  fetchMock.mockResolvedValueOnce(
    new Response(body === undefined ? null : JSON.stringify(body), { status }),
  )
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
  updateSettings({ serverUrl: '', token: '' })
})
afterEach(() => {
  fetchMock.mockReset()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  __resetTickets()
  vi.useRealTimers()
})

describe('api', () => {
  it('sends the token as a Bearer header and JSON body', async () => {
    updateSettings({ token: 's3cret', serverUrl: 'https://dl.example.com' })
    respond(200, { title: 'x' })

    await api.resolve('https://youtu.be/abc')

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('https://dl.example.com/api/resolve')
    expect(init.headers.get('Authorization')).toBe('Bearer s3cret')
    expect(init.headers.get('Content-Type')).toBe('application/json')
    expect(JSON.parse(init.body)).toEqual({ url: 'https://youtu.be/abc' })
  })

  it.each([
    [400, 'badRequest'],
    [401, 'unauthorized'],
    [404, 'notFound'],
    [409, 'conflict'],
    [422, 'platform'],
    [500, 'server'],
  ] as const)('maps HTTP %i to %s with the server detail', async (status, kind) => {
    respond(status, { detail: 'Nope' })

    const error = await api.getJob('x').catch((e) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status, kind, message: 'Nope' })
  })

  it('reports network failures as kind "network"', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'))

    await expect(api.health()).rejects.toMatchObject({ kind: 'network' })
  })

  it('calls the unauthorized handler on 401', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    respond(401, { detail: 'Invalid or missing token' })

    await api.getJob('x').catch(() => {})

    expect(handler).toHaveBeenCalledOnce()
  })

  it('checks a candidate token without saving it or opening the token prompt', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)

    respond(404, { detail: 'Job not found' })
    await expect(api.checkToken('right')).resolves.toBeUndefined()
    expect(fetchMock.mock.calls[0][1].headers.get('Authorization')).toBe('Bearer right')

    respond(401, { detail: 'Invalid or missing token' })
    await expect(api.checkToken('wrong')).rejects.toMatchObject({ kind: 'unauthorized' })
    expect(handler).not.toHaveBeenCalled()
  })

  it('ignores a 401 for a token that was replaced while the request was in flight', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    updateSettings({ token: 'old' })
    let finish!: (r: Response) => void
    fetchMock.mockReturnValueOnce(new Promise((resolve) => (finish = resolve)))

    const pending = api.getJob('x').catch(() => {})
    updateSettings({ token: 'new' })
    finish(new Response(JSON.stringify({ detail: 'Invalid or missing token' }), { status: 401 }))
    await pending

    expect(handler).not.toHaveBeenCalled()
  })

  it('checks health against an explicit server URL', async () => {
    respond(200, { status: 'ok' })

    await api.health(undefined, 'https://other.example.com')

    expect(fetchMock.mock.calls[0][0]).toBe('https://other.example.com/api/health')
  })

  it('puts the ticket, never the token, in the query for EventSource and file links', () => {
    updateSettings({ token: 'master-secret', serverUrl: 'https://dl.example.com' })
    const urls = [eventsUrl('j1', 'a b&c'), fileUrl('j1', 0, 'tk'), fileUrl('j1', 2, 'tk'), zipUrl('j1', 'tk')]

    expect(urls[0]).toBe('https://dl.example.com/api/jobs/j1/events?ticket=a%20b%26c')
    expect(urls[1]).toBe('https://dl.example.com/api/jobs/j1/files/0?ticket=tk')
    expect(urls[2]).toBe('https://dl.example.com/api/jobs/j1/files/2?ticket=tk')
    expect(urls[3]).toBe('https://dl.example.com/api/jobs/j1/zip?ticket=tk')
    for (const url of urls) {
      expect(url).not.toContain('token=')
      expect(url).not.toContain('master-secret')
    }
  })

  it('requests a ticket with POST and the Bearer header', async () => {
    updateSettings({ token: 's3cret' })
    respond(200, { ticket: 'tk', expires_at: '2030-01-01T00:00:00Z' })

    await expect(api.ticket('j 1')).resolves.toEqual({ ticket: 'tk', expires_at: '2030-01-01T00:00:00Z' })

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/jobs/j%201/ticket')
    expect(init.method).toBe('POST')
    expect(init.headers.get('Authorization')).toBe('Bearer s3cret')
  })

  it('maps 429 to rateLimited with Retry-After seconds and no token prompt', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ detail: 'Too many requests, try again later' }), {
        status: 429,
        headers: { 'Retry-After': '42' },
      }),
    )

    const error = await api.ticket('j1').catch((e) => e)

    expect(error).toMatchObject({ kind: 'rateLimited', status: 429, retryAfter: 42 })
    expect(handler).not.toHaveBeenCalled()
  })

  it('leaves retryAfter undefined when the header is missing or invalid', async () => {
    respond(429, { detail: 'x' })
    expect(await api.ticket('j1').catch((e) => e)).toMatchObject({ kind: 'rateLimited', retryAfter: undefined })
    fetchMock.mockResolvedValueOnce(new Response('{}', { status: 429, headers: { 'Retry-After': 'soon' } }))
    expect(await api.ticket('j1').catch((e) => e)).toMatchObject({ retryAfter: undefined })
  })
})

describe('ticket cache', () => {
  const ticketBody = (ticket: string, ttlMs: number) => ({
    ticket,
    expires_at: new Date(Date.now() + ttlMs).toISOString(),
  })

  beforeEach(() => {
    dropTicket('j1')
    dropTicket('j2')
  })
  afterEach(() => vi.useRealTimers())

  it('reuses a ticket until 30 s before it expires, per job', async () => {
    vi.useFakeTimers()
    respond(200, ticketBody('t1', 600_000))
    respond(200, ticketBody('t2', 600_000))
    respond(200, ticketBody('t3', 600_000))

    expect(await ticketFor('j1')).toBe('t1')
    expect(await ticketFor('j1')).toBe('t1')
    expect(await ticketFor('j2')).toBe('t2')
    expect(fetchMock).toHaveBeenCalledTimes(2)

    vi.advanceTimersByTime(600_000 - 29_000)
    expect(await ticketFor('j1')).toBe('t3')
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('never reuses a ticket that is about to expire', async () => {
    respond(200, ticketBody('short', 10_000))
    respond(200, ticketBody('next', 600_000))

    expect(await ticketFor('j1')).toBe('short')
    expect(await ticketFor('j1')).toBe('next')
  })

  it('does not reuse a ticket after a 401 during refresh', async () => {
    vi.useFakeTimers()
    respond(200, ticketBody('expiring', 31_000))
    expect(await ticketFor('j1')).toBe('expiring')
    vi.advanceTimersByTime(2_000)
    respond(401, { detail: 'Invalid or missing token' })
    await expect(ticketFor('j1')).rejects.toMatchObject({ kind: 'unauthorized' })
    respond(200, ticketBody('replacement', 600_000))
    expect(await ticketFor('j1')).toBe('replacement')
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('propagates errors such as a vanished job', async () => {
    respond(404, { detail: 'Job not found' })
    await expect(ticketFor('j1')).rejects.toMatchObject({ kind: 'notFound' })
  })

  it('shares one in-flight ticket POST between parallel callers', async () => {
    let finish!: (value: Response) => void
    fetchMock.mockReturnValueOnce(new Promise((resolve) => { finish = resolve }))
    const first = ticketFor('j1')
    const second = ticketFor('j1')
    expect(fetchMock).toHaveBeenCalledOnce()

    finish(new Response(JSON.stringify(ticketBody('shared', 600_000))))
    expect(await Promise.all([first, second])).toEqual(['shared', 'shared'])
    expect(await ticketFor('j1')).toBe('shared')
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('does not re-cache a ticket returned after dropTicket', async () => {
    let finish!: (value: Response) => void
    fetchMock.mockReturnValueOnce(new Promise((resolve) => { finish = resolve }))
    const old = ticketFor('j1')
    dropTicket('j1')
    respond(200, ticketBody('fresh', 600_000))
    expect(await ticketFor('j1')).toBe('fresh')
    finish(new Response(JSON.stringify(ticketBody('old', 600_000))))
    expect(await old).toBe('old')
    expect(await ticketFor('j1')).toBe('fresh')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('uses the server Date to cache correctly when the phone clock is fast', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2030-01-01T00:20:00Z'))
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({
      ticket: 'skew-safe', expires_at: '2030-01-01T00:10:00Z',
    }), { headers: { Date: 'Tue, 01 Jan 2030 00:00:00 GMT' } }))

    expect(await ticketFor('j1')).toBe('skew-safe')
    expect(await ticketFor('j1')).toBe('skew-safe')
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('does not cache an unparseable expiry', async () => {
    respond(200, { ticket: 'bad-date', expires_at: 'not a date' })
    respond(200, ticketBody('valid', 600_000))

    expect(await ticketFor('j1')).toBe('bad-date')
    expect(await ticketFor('j1')).toBe('valid')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})

describe('api (misc)', () => {
  it('reads a {message, code} detail from a 422', async () => {
    respond(422, { detail: { message: 'Login required', code: 'login_required' } })

    const error = await api.resolve('x').catch((e) => e)

    expect(error).toMatchObject({ kind: 'platform', status: 422, message: 'Login required', code: 'login_required' })
  })

  it('tolerates odd detail shapes', async () => {
    respond(422, { detail: [{ msg: 'bad' }] })
    expect(await api.resolve('x').catch((e) => e)).toMatchObject({ message: '', code: undefined })
    respond(422, { detail: { message: 5, code: 7 } })
    expect(await api.resolve('x').catch((e) => e)).toMatchObject({ message: '', code: undefined })
  })

  it('maps 503 to busy', async () => {
    respond(503, { detail: 'Server busy' })

    await expect(api.createJob('u', 'video-best')).rejects.toMatchObject({ kind: 'busy', status: 503 })
  })

  it('keeps a caller-supplied Content-Type', async () => {
    respond(200, { platform: 'instagram', present: true, cookie_count: 1, expired: false, domains: [], dropped_count: 0 })

    await api.cookies.put('instagram', '# Netscape\n')

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/cookies/instagram')
    expect(init.method).toBe('PUT')
    expect(init.headers.get('Content-Type')).toBe('text/plain')
    expect(init.body).toBe('# Netscape\n')
  })

  it('lists and removes cookies', async () => {
    respond(200, [{ platform: 'tiktok', present: false, cookie_count: 0, expired: false, domains: [] }])
    await expect(api.cookies.list()).resolves.toHaveLength(1)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/cookies')

    respond(204)
    await expect(api.cookies.remove('tiktok')).resolves.toBeUndefined()
    expect(fetchMock.mock.calls[1][0]).toBe('/api/cookies/tiktok')
    expect(fetchMock.mock.calls[1][1].method).toBe('DELETE')
  })

  it('surfaces 413 and 422 on cookie upload', async () => {
    respond(413, { detail: 'Too large' })
    await expect(api.cookies.put('tiktok', 'x')).rejects.toMatchObject({ status: 413 })
    respond(422, { detail: 'Not a cookies.txt' })
    await expect(api.cookies.put('tiktok', 'x')).rejects.toMatchObject({ kind: 'platform', message: 'Not a cookies.txt' })
  })
})

describe('no master token in URLs', () => {
  it('has no "token=" query building anywhere in the app sources', () => {
    const sources = import.meta.glob(['../**/*.{ts,tsx}', '!../**/*.test.{ts,tsx}'], {
      query: '?raw',
      import: 'default',
      eager: true,
    }) as Record<string, string>
    const entries = Object.entries(sources)

    const masterTokenQuery = /(?:[?&]token\b|\bURLSearchParams\s*\(\s*\{[^}]*\btoken\b|\.(?:set|append)\(\s*['"`]token['"`])/m
    const offenders = entries.filter(([, source]) => masterTokenQuery.test(source)).map(([file]) => file)

    expect(entries.length).toBeGreaterThan(10)
    expect(offenders).toEqual([])
  })
})
