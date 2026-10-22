import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { __resetTickets, api } from './api'
import { downloadFile, downloadZip } from './download'
import { updateSettings } from './settings'

beforeEach(() => {
  updateSettings({ serverUrl: '', token: 'master' })
  __resetTickets()
  vi.spyOn(api, 'ticket').mockResolvedValue({ ticket: 'tk', expires_at: new Date(Date.now() + 600_000).toISOString() })
})

afterEach(() => {
  vi.restoreAllMocks()
  __resetTickets()
})

it('attaches the file anchor before clicking, then removes it', async () => {
  let clicked: { href: string | null; download: string } | undefined
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    expect(document.body.contains(this)).toBe(true)
    clicked = { href: this.getAttribute('href'), download: this.download }
  })

  await downloadFile('j1', 2, 'song.mp3')

  expect(clicked?.href).toBe('/api/jobs/j1/files/2?ticket=tk')
  expect(clicked?.download).toBe('song.mp3')
  expect(document.body.querySelector('a[download]')).toBeNull()
})

it('uses an empty download attribute for a ZIP and removes its anchor', async () => {
  let clicked: { href: string | null; download: string | null } | undefined
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    expect(document.body.contains(this)).toBe(true)
    clicked = { href: this.getAttribute('href'), download: this.getAttribute('download') }
  })

  await downloadZip('j1')

  expect(clicked?.href).toBe('/api/jobs/j1/zip?ticket=tk')
  expect(clicked?.download).toBe('')
  expect(document.body.querySelector('a[download]')).toBeNull()
})
