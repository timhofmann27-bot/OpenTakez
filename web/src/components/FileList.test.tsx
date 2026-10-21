import { render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { updateSettings } from '../lib/settings'
import { FileList } from './FileList'

beforeEach(() => updateSettings({ lang: 'en', token: '', serverUrl: '' }))

describe('FileList', () => {
  it('shows a single Save link for one file', () => {
    render(<FileList jobId="j1" files={['a.mp3']} sizes={[1000]} />)

    expect(screen.getByRole('link', { name: 'Save' })).toHaveAttribute('href', '/api/jobs/j1/files/0')
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
    expect(screen.queryByText('Save all as ZIP')).not.toBeInTheDocument()
  })

  it('offers a ZIP and a labelled link per file with sizes for several files', () => {
    render(<FileList jobId="j1" files={['a.jpg', 'b.jpg']} sizes={[2048, 3 * 1024 * 1024]} />)

    expect(screen.getByRole('link', { name: 'Save all as ZIP' })).toHaveAttribute('href', '/api/jobs/j1/zip')
    expect(screen.getByText('2 files')).toBeInTheDocument()
    const items = within(screen.getByRole('list')).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(within(items[0]).getByText('2.0 KB')).toBeInTheDocument()
    expect(within(items[1]).getByText('3.0 MB')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Save b.jpg' })).toHaveAttribute('href', '/api/jobs/j1/files/1')
  })

  it('omits sizes the server did not send', () => {
    render(<FileList jobId="j1" files={['a.jpg', 'b.jpg']} />)

    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    expect(screen.queryByText(/KB|MB|\bB$/)).not.toBeInTheDocument()
  })

  it('focuses the first link on request and uses 44px targets', () => {
    render(<FileList jobId="j1" files={['a.jpg', 'b.jpg']} autoFocus />)
    const zip = screen.getByRole('link', { name: 'Save all as ZIP' })
    expect(document.activeElement).toBe(zip)
    expect(zip).toHaveClass('min-h-11')
  })

  it('does not steal focus by default', () => {
    render(<FileList jobId="j1" files={['a.mp3']} />)
    expect(document.activeElement).toBe(document.body)
  })
})
