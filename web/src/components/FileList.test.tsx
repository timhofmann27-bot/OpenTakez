import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, dropTicket } from '../lib/api'
import { updateSettings } from '../lib/settings'
import { FileList } from './FileList'

const clicked: { href: string | null; download: string }[] = []

beforeEach(() => {
  updateSettings({ lang: 'en', token: 'master', serverUrl: '' })
  dropTicket('j1')
  clicked.length = 0
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    clicked.push({ href: this.getAttribute('href'), download: this.download })
  })
})
afterEach(() => vi.restoreAllMocks())

const mockTicket = () =>
  vi.spyOn(api, 'ticket').mockResolvedValue({ ticket: 'tk', expires_at: new Date(Date.now() + 600_000).toISOString() })

describe('FileList', () => {
  it('shows a single Save button for one file, without any link', () => {
    render(<FileList jobId="j1" files={['a.mp3']} sizes={[1000]} />)

    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
    expect(screen.queryByText('Save all as ZIP')).not.toBeInTheDocument()
  })

  it('fetches a ticket, then clicks an anchor carrying ticket= and not token=', async () => {
    const ticket = mockTicket()
    render(<FileList jobId="j1" files={['a.mp3']} />)

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(clicked).toHaveLength(1))
    expect(ticket).toHaveBeenCalledWith('j1')
    expect(clicked[0]).toEqual({ href: '/api/jobs/j1/files/0?ticket=tk', download: 'a.mp3' })
    expect(clicked[0].href).not.toContain('token=')
  })

  it('is busy while the ticket is requested and ignores extra clicks', async () => {
    let release!: (v: { ticket: string; expires_at: string }) => void
    const ticket = vi.spyOn(api, 'ticket').mockReturnValue(new Promise((resolve) => (release = resolve)))
    render(<FileList jobId="j1" files={['a.mp3']} />)
    const button = screen.getByRole('button', { name: 'Save' })

    await userEvent.click(button)

    expect(button).toHaveAttribute('aria-disabled', 'true')
    expect(screen.getByRole('status')).toHaveTextContent('Preparing download')
    button.focus()
    await userEvent.click(button)
    expect(document.activeElement).toBe(button)
    expect(ticket).toHaveBeenCalledOnce()
    release({ ticket: 'tk', expires_at: new Date(Date.now() + 600_000).toISOString() })
    await waitFor(() => expect(button).toHaveAttribute('aria-disabled', 'false'))
    expect(document.activeElement).toBe(button)
    expect(ticket).toHaveBeenCalledOnce()
    expect(clicked).toHaveLength(1)
  })

  it('offers a ZIP and a labelled button per file with sizes for several files', async () => {
    mockTicket()
    render(<FileList jobId="j1" files={['a.jpg', 'b.jpg']} sizes={[2048, 3 * 1024 * 1024]} />)

    expect(screen.getByText('2 files')).toBeInTheDocument()
    const items = within(screen.getByRole('list')).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(within(items[0]).getByText('2.0 KB')).toBeInTheDocument()
    expect(within(items[1]).getByText('3.0 MB')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Save all as ZIP' }))
    await waitFor(() => expect(clicked).toHaveLength(1))
    expect(clicked[0].href).toBe('/api/jobs/j1/zip?ticket=tk')

    await userEvent.click(screen.getByRole('button', { name: 'Save b.jpg' }))
    await waitFor(() => expect(clicked).toHaveLength(2))
    expect(clicked[1]).toEqual({ href: '/api/jobs/j1/files/1?ticket=tk', download: 'b.jpg' })
  })

  it('shows a ticket failure in an alert and downloads nothing', async () => {
    vi.spyOn(api, 'ticket').mockRejectedValue(new ApiError(429, 'Too many', undefined, 9))
    render(<FileList jobId="j1" files={['a.mp3']} />)

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Too many requests. Try again in 9 s.')
    expect(clicked).toHaveLength(0)
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
  })

  it('reports a vanished job as gone', async () => {
    vi.spyOn(api, 'ticket').mockRejectedValue(new ApiError(404, 'Job not found'))
    render(<FileList jobId="j1" files={['a.mp3']} />)

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(clicked).toHaveLength(0)
  })

  it('omits sizes the server did not send', () => {
    render(<FileList jobId="j1" files={['a.jpg', 'b.jpg']} />)

    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    expect(screen.queryByText(/KB|MB|\bB$/)).not.toBeInTheDocument()
  })

  it('focuses the first button on request and uses 44px targets', () => {
    render(<FileList jobId="j1" files={['a.jpg', 'b.jpg']} autoFocus />)
    const zip = screen.getByRole('button', { name: 'Save all as ZIP' })
    expect(document.activeElement).toBe(zip)
    expect(zip).toHaveClass('min-h-11')
  })

  it('does not steal focus by default', () => {
    render(<FileList jobId="j1" files={['a.mp3']} />)
    expect(document.activeElement).toBe(document.body)
  })
})
