import { describe, expect, it } from 'vitest'
import { isInsecureServer, normalizeServerUrl, settingsStore, updateSettings } from './settings'

describe('normalizeServerUrl', () => {
  it.each([
    ['', ''],
    ['  ', ''],
    ['https://dl.example.com/', 'https://dl.example.com'],
    ['http://192.168.1.5:8000', 'http://192.168.1.5:8000'],
    ['https://example.com/opentakez//', 'https://example.com/opentakez'],
    ['dl.example.com', null],
    ['ftp://example.com', null],
    ['javascript:alert(1)', null],
  ])('%j → %j', (input, expected) => {
    expect(normalizeServerUrl(input)).toBe(expected)
  })
})

describe('settings store', () => {
  it('persists updates to localStorage', () => {
    updateSettings({ token: 'abc' })

    expect(settingsStore.get().token).toBe('abc')
    expect(JSON.parse(localStorage.getItem('opentakez.settings')!).token).toBe('abc')
  })
})

describe('isInsecureServer', () => {
  it('flags plain http to remote hosts only', () => {
    expect(isInsecureServer('http://192.168.1.5:8000')).toBe(true)
    expect(isInsecureServer('http://localhost:8000')).toBe(false)
    expect(isInsecureServer('http://127.0.0.1:8000')).toBe(false)
    expect(isInsecureServer('https://dl.example.com')).toBe(false)
  })

  it('uses the page origin when no server is set', () => {
    expect(isInsecureServer('', 'http://nas.local')).toBe(true)
    expect(isInsecureServer('', 'https://nas.local')).toBe(false)
    expect(isInsecureServer('', 'http://localhost:5173')).toBe(false)
  })
})
