import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, eventsUrl, fileUrl, setUnauthorizedHandler } from './api'
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
    expect(fileUrl('j1')).toBe('/api/jobs/j1/file?token=a%20b%26c')
  })

  it('omits the token query when no token is set', () => {
    expect(fileUrl('j1')).toBe('/api/jobs/j1/file')
  })
})
