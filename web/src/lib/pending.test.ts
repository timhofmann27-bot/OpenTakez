import { beforeEach, describe, expect, it } from 'vitest'
import { consumeShareTarget, pendingLink } from './pending'

describe('consumeShareTarget', () => {
  beforeEach(() => pendingLink.set(null))

  it('takes the link from text (Android) and rewrites the URL to /', () => {
    history.replaceState(null, '', '/share?title=Cool&text=Look%20https%3A%2F%2Fyoutu.be%2Fabc')

    consumeShareTarget()

    expect(pendingLink.get()).toBe('Look https://youtu.be/abc Cool')
    expect(location.pathname).toBe('/')
    expect(location.search).toBe('')
  })

  it('does nothing outside /share', () => {
    history.replaceState(null, '', '/history?text=x')

    consumeShareTarget()

    expect(pendingLink.get()).toBeNull()
    expect(location.pathname).toBe('/history')
  })
})
