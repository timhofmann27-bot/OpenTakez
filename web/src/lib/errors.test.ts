import { describe, expect, it } from 'vitest'
import { translate } from '../i18n'
import { ApiError } from './api'
import { errorMessage, guessPlatform } from './errors'

const t = (key: Parameters<typeof translate>[1], vars?: Record<string, string | number>) =>
  translate('en', key, vars)

describe('errorMessage', () => {
  it('maps 503 to the busy text', () => {
    expect(errorMessage(new ApiError(503, 'Server busy'), t)).toBe('The server is busy. Try again in a moment.')
  })

  it('maps login_required to a platform-specific hint', () => {
    const error = new ApiError(422, 'Login required', 'login_required')
    expect(errorMessage(error, t, 'instagram')).toBe(
      'This needs a login. Upload cookies for Instagram in Settings.',
    )
  })

  it('drops the platform from the login hint when it is unknown', () => {
    const error = new ApiError(422, 'Login required', 'login_required')
    expect(errorMessage(error, t)).toBe('This needs a login. Upload cookies in Settings.')
    expect(errorMessage(error, t, '')).not.toMatch(/\s{2}/)
  })

  it('still shows server detail for other 422s', () => {
    expect(errorMessage(new ApiError(422, 'Video unavailable'), t)).toBe('Video unavailable')
  })

  it('guesses the platform from pasted text', () => {
    expect(guessPlatform('look https://www.instagram.com/p/abc/')).toBe('instagram')
    expect(guessPlatform('https://vm.tiktok.com/xyz')).toBe('tiktok')
    expect(guessPlatform('https://fb.watch/abc')).toBe('facebook')
    expect(guessPlatform('https://notinstagram.com/x')).toBe('')
    expect(guessPlatform('no link')).toBe('')
  })
})
