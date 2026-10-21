import { createStore, useStore } from './store'

export type Path = '/' | '/downloads' | '/history' | '/settings' | '/share'

const routeStore = createStore(location.pathname)
window.addEventListener('popstate', () => routeStore.set(location.pathname))

export function navigate(path: Path, { replace = false } = {}) {
  if (replace) history.replaceState(null, '', path)
  else history.pushState(null, '', path)
  routeStore.set(location.pathname)
  window.scrollTo(0, 0)
}

export const useRoute = () => useStore(routeStore)
