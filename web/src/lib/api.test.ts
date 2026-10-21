import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, eventsUrl, fileUrl, setUnauthorizedHandler, zipUrl } from './api'
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

  it('puts the token in the query for EventSource and file links', () => {
    updateSettings({ token: 'a b&c' })

    expect(eventsUrl('j1')).toBe('/api/jobs/j1/events?token=a%20b%26c')
    expect(fileUrl('j1')).toBe('/api/jobs/j1/files/0?token=a%20b%26c')
    expect(fileUrl('j1', 2)).toBe('/api/jobs/j1/files/2?token=a%20b%26c')
    expect(zipUrl('j1')).toBe('/api/jobs/j1/zip?token=a%20b%26c')
  })

  it('omits the token query when no token is set', () => {
    expect(fileUrl('j1')).toBe('/api/jobs/j1/files/0')
    expect(zipUrl('j1')).toBe('/api/jobs/j1/zip')
  })

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
