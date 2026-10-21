import { createStore } from './store'
import { navigate } from './useRoute'

/** A link handed to the Home view from outside: Android share target or "download again". */
export const pendingLink = createStore<string | null>(null)

/** Read /share?url=&text=&title= once. Android apps often put the link in `text`. */
export function consumeShareTarget() {
  if (location.pathname !== '/share') return
  const params = new URLSearchParams(location.search)
  const shared = ['url', 'text', 'title']
    .map((name) => params.get(name)?.trim())
    .filter(Boolean)
    .join(' ')
  if (shared) pendingLink.set(shared)
  navigate('/', { replace: true })
}

/** Whether the app should ask for a token (401 or server requires one and none is set). */
export const tokenPrompt = createStore(false)
