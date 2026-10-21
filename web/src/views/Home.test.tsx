import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api } from '../lib/api'
import { updateSettings } from '../lib/settings'
import type { MediaInfo } from '../lib/types'
import { Home } from './Home'

const info: MediaInfo = {
  url: 'https://www.instagram.com/p/x/',
  platform: 'instagram',
  kind: 'image',
  title: 'Post',
  options: ['image-original'],
}

beforeEach(() => updateSettings({ lang: 'en', token: '', serverUrl: '' }))
afterEach(() => vi.restoreAllMocks())

async function check(link: string) {
  await userEvent.type(screen.getByLabelText('Link'), link)
  await userEvent.click(screen.getByRole('button', { name: 'Check link' }))
}

describe('Home errors', () => {
  it('names the platform guessed from the pasted link', async () => {
    vi.spyOn(api, 'resolve').mockRejectedValue(new ApiError(422, 'Login required', 'login_required'))
    render(<Home />)

    await check('https://www.instagram.com/stories/x/1')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This needs a login. Upload cookies for Instagram in Settings.',
    )
  })

  it('uses the platform-less hint when none can be guessed, and keeps it after editing the field', async () => {
    vi.spyOn(api, 'resolve').mockRejectedValue(new ApiError(422, 'Login required', 'login_required'))
    render(<Home />)

    await check('https://example.com/private')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('This needs a login. Upload cookies in Settings.')
    expect(alert.textContent).not.toMatch(/for\s+in/)
    await userEvent.type(screen.getByLabelText('Link'), 'https://www.tiktok.com/x')
    expect(screen.getByRole('alert')).toHaveTextContent('This needs a login. Upload cookies in Settings.')
  })

  it('shows the plain-string 422 when the option does not fit the URL', async () => {
    vi.spyOn(api, 'resolve').mockResolvedValue(info)
    vi.spyOn(api, 'createJob').mockRejectedValue(new ApiError(422, 'Option image-original does not fit this link'))
    render(<Home />)

    await check(info.url)
    await userEvent.click(await screen.findByRole('button', { name: 'Download' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Option image-original does not fit this link')
  })
})
