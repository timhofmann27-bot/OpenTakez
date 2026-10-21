import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../lib/api'
import { updateSettings } from '../lib/settings'
import type { CookieStatus, CookieUpload } from '../lib/types'
import { CookieSettings } from './CookieSettings'

const status = (platform: string, patch: Partial<CookieStatus> = {}): CookieStatus => ({
  platform,
  present: false,
  cookie_count: 0,
  expired: false,
  domains: [],
  ...patch,
})

const textFile = (content = '# Netscape HTTP Cookie File\n') =>
  new File([content], 'cookies.txt', { type: 'text/plain' })

beforeEach(() => updateSettings({ lang: 'en', serverUrl: 'https://dl.example.com', token: '' }))
afterEach(() => vi.restoreAllMocks())

describe('CookieSettings', () => {
  it('renders status per platform', async () => {
    vi.spyOn(api.cookies, 'list').mockResolvedValue([
      status('instagram', {
        present: true,
        cookie_count: 12,
        uploaded_at: '2026-10-01T10:00:00Z',
        earliest_expiry: '2027-01-15T00:00:00Z',
      }),
      status('tiktok', { present: true, cookie_count: 3, expired: true }),
    ])

    render(<CookieSettings />)

    expect(await screen.findByText('Logged in · 12 cookies')).toBeInTheDocument()
    expect(screen.getByText(/^Expires /)).toBeInTheDocument()
    expect(screen.getByText(/^Uploaded /)).toBeInTheDocument()
    expect(screen.getByText('Expired, upload a new file')).toBeInTheDocument()
    expect(screen.getAllByText('No login')).toHaveLength(1) // facebook
    expect(screen.getByRole('button', { name: 'Replace: Instagram' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Upload cookies.txt: Facebook' })).toBeInTheDocument()
  })

  it('uploads a file and reports ignored cookies', async () => {
    vi.spyOn(api.cookies, 'list').mockResolvedValue([])
    const put = vi.spyOn(api.cookies, 'put').mockResolvedValue({
      ...status('instagram', { present: true, cookie_count: 5 }),
      dropped_count: 2,
    } satisfies CookieUpload)
    render(<CookieSettings />)

    await userEvent.upload(screen.getByTestId('cookie-file-instagram'), textFile('abc'))

    expect(put).toHaveBeenCalledWith('instagram', 'abc')
    expect(await screen.findByText('Logged in · 5 cookies')).toBeInTheDocument()
    expect(screen.getByText('2 cookies for other sites were ignored.')).toBeInTheDocument()
  })

  it('rejects files over 256 KB without calling the server', async () => {
    vi.spyOn(api.cookies, 'list').mockResolvedValue([])
    const put = vi.spyOn(api.cookies, 'put')
    render(<CookieSettings />)

    await userEvent.upload(screen.getByTestId('cookie-file-tiktok'), textFile('x'.repeat(256 * 1024 + 1)))

    expect(await screen.findByRole('alert')).toHaveTextContent('That file is too large for a cookies.txt.')
    expect(put).not.toHaveBeenCalled()
  })

  it('shows the server detail for an invalid file', async () => {
    vi.spyOn(api.cookies, 'list').mockResolvedValue([])
    vi.spyOn(api.cookies, 'put').mockRejectedValue(new ApiError(422, 'No cookies for tiktok.com found'))
    render(<CookieSettings />)

    await userEvent.upload(screen.getByTestId('cookie-file-tiktok'), textFile())

    expect(await screen.findByRole('alert')).toHaveTextContent('No cookies for tiktok.com found')
  })

  it('maps a server 413 to the too-large text', async () => {
    vi.spyOn(api.cookies, 'list').mockResolvedValue([])
    vi.spyOn(api.cookies, 'put').mockRejectedValue(new ApiError(413, 'Too large'))
    render(<CookieSettings />)

    await userEvent.upload(screen.getByTestId('cookie-file-tiktok'), textFile())

    expect(await screen.findByRole('alert')).toHaveTextContent('too large')
  })

  it('deletes only after confirmation', async () => {
    vi.spyOn(api.cookies, 'list').mockResolvedValue([status('instagram', { present: true, cookie_count: 4 })])
    const remove = vi.spyOn(api.cookies, 'remove').mockResolvedValue(undefined)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    render(<CookieSettings />)
    const button = await screen.findByRole('button', { name: 'Delete: Instagram' })

    await userEvent.click(button)
    expect(confirm).toHaveBeenCalledWith('Delete the Instagram login from the server?')
    expect(remove).not.toHaveBeenCalled()

    await userEvent.click(button)
    await waitFor(() => expect(remove).toHaveBeenCalledWith('instagram'))
    expect(await screen.findAllByText('No login')).toHaveLength(3)
  })

  it('disables uploads over plain http to a remote host', async () => {
    updateSettings({ serverUrl: 'http://192.168.1.5:8000' })
    vi.spyOn(api.cookies, 'list').mockResolvedValue([])
    render(<CookieSettings />)

    expect(await screen.findByText(/unencrypted connection/)).toBeInTheDocument()
    for (const button of screen.getAllByRole('button', { name: /^Upload cookies.txt/ })) {
      expect(button).toHaveAttribute('aria-disabled', 'true')
    }
  })

  it('allows http on localhost', async () => {
    updateSettings({ serverUrl: 'http://localhost:8000' })
    vi.spyOn(api.cookies, 'list').mockResolvedValue([])
    render(<CookieSettings />)

    expect(screen.queryByText(/unencrypted connection/)).not.toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Upload cookies.txt: Instagram' })).toHaveAttribute(
        'aria-disabled',
        'false',
      ),
    )
  })

  it('shows load errors', async () => {
    vi.spyOn(api.cookies, 'list').mockRejectedValue(new ApiError(0, 'network'))
    render(<CookieSettings />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Server unreachable')
  })

  it('ignores a stale list response that arrives after an upload', async () => {
    const list = deferred<CookieStatus[]>()
    vi.spyOn(api.cookies, 'list').mockReturnValue(list.promise)
    // Uploading is blocked until the list settled, so settle a first load, then race a reload.
    const second = deferred<CookieStatus[]>()
    const spy = api.cookies.list as unknown as ReturnType<typeof vi.fn>
    spy.mockReturnValueOnce(list.promise).mockReturnValueOnce(second.promise)
    const { rerender } = render(<CookieSettings />)
    list.resolve([])
    await screen.findAllByText('No login')
    vi.spyOn(api.cookies, 'put').mockResolvedValue({
      ...status('instagram', { present: true, cookie_count: 5 }),
      dropped_count: 0,
    })

    // Re-trigger the list request (language change swaps `t`) and upload before it answers.
    updateSettings({ lang: 'de' })
    rerender(<CookieSettings />)
    await userEvent.upload(screen.getByTestId('cookie-file-instagram'), textFile('abc'))
    expect(await screen.findByText('Eingeloggt · 5 Cookies')).toBeInTheDocument()

    second.resolve([status('instagram', { present: false }), status('tiktok', { present: true, cookie_count: 2 })])
    expect(await screen.findByText('Eingeloggt · 2 Cookies')).toBeInTheDocument()
    expect(screen.getByText('Eingeloggt · 5 Cookies')).toBeInTheDocument()
  })

  it('shows a loading line instead of "No login" until the list answers', async () => {
    const list = deferred<CookieStatus[]>()
    vi.spyOn(api.cookies, 'list').mockReturnValue(list.promise)
    render(<CookieSettings />)

    expect(screen.getAllByText('Loading …')).toHaveLength(3)
    expect(screen.queryByText('No login')).not.toBeInTheDocument()
    const upload = screen.getByRole('button', { name: 'Upload cookies.txt: Instagram' })
    expect(upload).toHaveAttribute('aria-disabled', 'true')

    list.resolve([])
    expect(await screen.findAllByText('No login')).toHaveLength(3)
    expect(upload).toHaveAttribute('aria-disabled', 'false')
  })

  it('does not claim "No login" when the list failed', async () => {
    vi.spyOn(api.cookies, 'list').mockRejectedValue(new ApiError(0, 'network'))
    render(<CookieSettings />)

    await screen.findByRole('alert')
    expect(screen.queryByText('No login')).not.toBeInTheDocument()
    expect(screen.queryByText('Loading …')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Upload cookies.txt: Instagram' })).toHaveAttribute(
      'aria-disabled',
      'true',
    )
  })

  it('clears statuses when the server changes', async () => {
    vi.spyOn(api.cookies, 'list').mockResolvedValueOnce([status('instagram', { present: true, cookie_count: 4 })])
    render(<CookieSettings />)
    expect(await screen.findByText('Logged in · 4 cookies')).toBeInTheDocument()

    const next = deferred<CookieStatus[]>()
    vi.spyOn(api.cookies, 'list').mockReturnValueOnce(next.promise)
    act(() => updateSettings({ serverUrl: 'https://other.example.com' }))

    expect(screen.queryByText('Logged in · 4 cookies')).not.toBeInTheDocument()
    expect(screen.getAllByText('Loading …')).toHaveLength(3)
  })

  it('moves focus to the upload button after a delete and announces it', async () => {
    vi.spyOn(api.cookies, 'list').mockResolvedValue([status('instagram', { present: true, cookie_count: 4 })])
    vi.spyOn(api.cookies, 'remove').mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<CookieSettings />)
    const del = await screen.findByRole('button', { name: 'Delete: Instagram' })
    del.focus()

    await userEvent.click(del)

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Upload cookies.txt: Instagram' })).toHaveFocus(),
    )
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Upload cookies.txt: Instagram' }))
    expect(screen.getAllByRole('status').some((el) => el.textContent === 'Login removed')).toBe(true)
  })

  it('keeps focus on the button while busy and ignores clicks', async () => {
    vi.spyOn(api.cookies, 'list').mockResolvedValue([status('instagram', { present: true, cookie_count: 4 })])
    const pending = deferred<void>()
    const remove = vi.spyOn(api.cookies, 'remove').mockReturnValue(pending.promise)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<CookieSettings />)
    const del = await screen.findByRole('button', { name: 'Delete: Instagram' })
    del.focus()

    await userEvent.click(del)
    expect(del).toHaveAttribute('aria-disabled', 'true')
    expect(del).not.toBeDisabled()
    expect(del).toHaveFocus()
    await userEvent.click(del)
    expect(remove).toHaveBeenCalledTimes(1)

    pending.resolve()
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Delete: Instagram' })).not.toBeInTheDocument())
  })

  it('announces a saved upload in a persistent live region', async () => {
    vi.spyOn(api.cookies, 'list').mockResolvedValue([])
    vi.spyOn(api.cookies, 'put').mockResolvedValue({
      ...status('instagram', { present: true, cookie_count: 5 }),
      dropped_count: 0,
    })
    render(<CookieSettings />)
    await screen.findAllByText('No login')
    expect(screen.getAllByRole('status')).toHaveLength(3)

    await userEvent.upload(screen.getByTestId('cookie-file-instagram'), textFile('abc'))

    await waitFor(() =>
      expect(screen.getAllByRole('status').some((el) => el.textContent === 'Cookies saved')).toBe(true),
    )
    expect(screen.getAllByRole('status')).toHaveLength(3)
  })

  it('treats a 404 on delete as success', async () => {
    vi.spyOn(api.cookies, 'list').mockResolvedValue([status('instagram', { present: true, cookie_count: 4 })])
    vi.spyOn(api.cookies, 'remove').mockRejectedValue(new ApiError(404, 'Not found'))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<CookieSettings />)

    await userEvent.click(await screen.findByRole('button', { name: 'Delete: Instagram' }))

    expect(await screen.findAllByText('No login')).toHaveLength(3)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('still reports other delete failures', async () => {
    vi.spyOn(api.cookies, 'list').mockResolvedValue([status('instagram', { present: true, cookie_count: 4 })])
    vi.spyOn(api.cookies, 'remove').mockRejectedValue(new ApiError(0, 'network'))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<CookieSettings />)

    await userEvent.click(await screen.findByRole('button', { name: 'Delete: Instagram' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Server unreachable')
    expect(screen.getByText('Logged in · 4 cookies')).toBeInTheDocument()
  })

  it('skips date lines for invalid dates', async () => {
    vi.spyOn(api.cookies, 'list').mockResolvedValue([
      status('instagram', { present: true, cookie_count: 1, uploaded_at: 'garbage', earliest_expiry: 'nope' }),
    ])
    render(<CookieSettings />)

    expect(await screen.findByText('Logged in · 1 cookies')).toBeInTheDocument()
    expect(screen.queryByText(/Invalid Date/)).not.toBeInTheDocument()
    expect(screen.queryByText(/^Expires /)).not.toBeInTheDocument()
    expect(screen.queryByText(/^Uploaded /)).not.toBeInTheDocument()
  })

  it('uses a hidden attribute, not aria-hidden, on the file input', async () => {
    vi.spyOn(api.cookies, 'list').mockResolvedValue([])
    render(<CookieSettings />)
    const input = screen.getByTestId('cookie-file-instagram')
    expect(input).toHaveAttribute('hidden')
    expect(input).not.toHaveAttribute('aria-hidden')
    await screen.findAllByText('No login')
  })
})

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}
