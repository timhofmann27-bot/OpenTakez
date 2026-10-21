import { createStore, storage, useStore } from './store'

export type Lang = 'de' | 'en'

export type Settings = {
  /** Empty means the server that serves this PWA. */
  serverUrl: string
  token: string
  lang: Lang
}

const KEY = 'opentakez.settings'

function detectLang(): Lang {
  return navigator.language?.toLowerCase().startsWith('de') ? 'de' : 'en'
}

function load(): Settings {
  const defaults: Settings = { serverUrl: '', token: '', lang: detectLang() }
  try {
    const saved = JSON.parse(storage.get(KEY) ?? '{}')
    return {
      serverUrl: typeof saved?.serverUrl === 'string' ? saved.serverUrl : defaults.serverUrl,
      token: typeof saved?.token === 'string' ? saved.token : defaults.token,
      lang: saved?.lang === 'de' || saved?.lang === 'en' ? saved.lang : defaults.lang,
    }
  } catch {
    return defaults
  }
}

export const settingsStore = createStore<Settings>(load())

export function updateSettings(patch: Partial<Settings>) {
  settingsStore.set((prev) => ({ ...prev, ...patch }))
  storage.set(KEY, JSON.stringify(settingsStore.get()))
}

export const useSettings = () => useStore(settingsStore)

/** "https://dl.example.com/" → "https://dl.example.com". Returns null for invalid input. */
export function normalizeServerUrl(input: string): string | null {
  const trimmed = input.trim()
  if (!trimmed) return ''
  try {
    const url = new URL(trimmed)
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null
    return `${url.origin}${url.pathname}`.replace(/\/+$/, '')
  } catch {
    return null
  }
}
