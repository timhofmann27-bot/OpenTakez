import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { updateSettings } from '../lib/settings'
import type { JobView } from '../lib/jobs'
import { ApiError, api } from '../lib/api'
import { HistoryRow } from './HistoryRow'
import { JobRow } from './JobRow'

const view = (job: Partial<JobView['job']>): JobView => ({
  info: { url: 'https://www.instagram.com/p/x/', platform: 'instagram', kind: 'image', title: 'Post', options: ['image-original'] },
  job: {
    id: 'j1',
    url: 'https://www.instagram.com/p/x/',
    platform: 'instagram',
    option: 'image-original',
    status: 'done',
    progress: null,
    files: ['a.jpg'],
    created_at: '2026-10-21T20:00:00Z',
    ...job,
  },
})

beforeEach(() => updateSettings({ lang: 'en', token: '', serverUrl: '' }))

describe('JobRow', () => {
  it('shows ZIP and per-file links for a multi-file job', () => {
    render(<ul><JobRow view={view({ files: ['a.jpg', 'b.jpg'], sizes: [10, 20] })} /></ul>)

    expect(screen.getByRole('link', { name: 'Save all as ZIP' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Save a.jpg' })).toBeInTheDocument()
  })

  it('points a login_required failure to Settings', async () => {
    render(
      <ul>
        <JobRow view={view({ status: 'error', files: [], error: 'Login required', error_code: 'login_required' })} />
      </ul>,
    )

    expect(screen.getAllByText('This needs a login. Upload cookies for Instagram in Settings.').length).toBeGreaterThan(0)
    await userEvent.click(screen.getByRole('link', { name: 'Settings' }))
    expect(location.pathname).toBe('/settings')
  })

  it('falls back to the job platform, then to a platform-less login hint', () => {
    const failed = { status: 'error' as const, files: [], error: 'Login required', error_code: 'login_required' }
    const { rerender } = render(<ul><JobRow view={{ ...view(failed), info: { ...view({}).info, platform: '' } }} /></ul>)
    expect(screen.getAllByText('This needs a login. Upload cookies for Instagram in Settings.').length).toBeGreaterThan(0)

    rerender(<ul><JobRow view={{ ...view({ ...failed, platform: '' }), info: { ...view({}).info, platform: '' } }} /></ul>)
    expect(screen.getAllByText('This needs a login. Upload cookies in Settings.').length).toBeGreaterThan(0)
    expect(screen.queryByText(/for\s+in Settings/)).not.toBeInTheDocument()
  })

  it('notes skipped items in the done state only when there are some', () => {
    const { rerender } = render(<ul><JobRow view={view({ skipped: 3 })} /></ul>)
    expect(screen.getByText("3 items couldn't be downloaded")).toBeInTheDocument()

    rerender(<ul><JobRow view={view({ skipped: 0 })} /></ul>)
    expect(screen.queryByText(/couldn't be downloaded/)).not.toBeInTheDocument()
  })

  it('uses 44px targets for its actions', () => {
    render(<ul><JobRow view={view({})} /></ul>)
    expect(screen.getByRole('link', { name: 'Save' })).toHaveClass('min-h-11')
    expect(screen.getByRole('button', { name: /^Remove/ })).toHaveClass('min-h-11')
  })

  it('aligns Remove with the text when it is the first action', () => {
    render(<ul><JobRow view={view({ status: 'running', files: [] })} /></ul>)
    expect(screen.getByRole('button', { name: /^Remove/ })).toHaveClass('-ml-3')
  })

  it('aligns Remove under a multi-file list but not next to a single Save link', () => {
    const { unmount } = render(<ul><JobRow view={view({ files: ['a.jpg', 'b.jpg'] })} /></ul>)
    expect(screen.getByRole('button', { name: /^Remove/ })).toHaveClass('-ml-3')
    unmount()
    render(<ul><JobRow view={view({ files: ['a.jpg'] })} /></ul>)
    expect(screen.getByRole('button', { name: /^Remove/ })).not.toHaveClass('-ml-3')
  })
})

describe('HistoryRow', () => {
  const item = {
    id: 'j1',
    url: 'https://www.instagram.com/p/x/',
    title: 'Post',
    platform: 'instagram',
    kind: 'image' as const,
    option: 'image-original' as const,
    fileName: 'a.jpg',
    fileNames: ['a.jpg', 'b.jpg'],
    createdAt: 1,
  }

  it('checks the server, then lists the files of a multi-file item', async () => {
    const job = view({ files: ['a.jpg', 'b.jpg'], sizes: [1, 2] }).job
    const getJob = vi.spyOn(api, 'getJob').mockResolvedValue(job)
    render(<ul><HistoryRow item={item} /></ul>)

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(getJob).toHaveBeenCalledWith('j1')
    expect(await screen.findByRole('link', { name: 'Save all as ZIP' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Save b.jpg' })).toBeInTheDocument()
  })

  it('moves focus to the ZIP link once the file list replaces the Save button', async () => {
    vi.spyOn(api, 'getJob').mockResolvedValue(view({ files: ['a.jpg', 'b.jpg'] }).job)
    render(<ul><HistoryRow item={item} /></ul>)

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    const zip = await screen.findByRole('link', { name: 'Save all as ZIP' })
    expect(document.activeElement).toBe(zip)
  })

  it('shows the error and keeps the Save button when the check fails', async () => {
    vi.spyOn(api, 'getJob').mockRejectedValue(new ApiError(0, 'network'))
    render(<ul><HistoryRow item={item} /></ul>)

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Server unreachable')
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Save all as ZIP' })).not.toBeInTheDocument()
  })
})
