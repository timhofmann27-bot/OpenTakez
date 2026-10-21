import { useEffect, useRef } from 'react'
import { Nav } from './components/Nav'
import { TokenDialog } from './components/TokenDialog'
import { type MessageKey, useT } from './i18n'
import { api, setUnauthorizedHandler } from './lib/api'
import { loadHistory } from './lib/history'
import { restoreJobs, resumeJobs } from './lib/jobs'
import { tokenPrompt } from './lib/pending'
import { settingsStore, useSettings } from './lib/settings'
import { useRoute } from './lib/useRoute'
import { Downloads } from './views/Downloads'
import { History } from './views/History'
import { Home } from './views/Home'
import { Settings } from './views/Settings'

const views: Record<string, { title: MessageKey; view: () => React.ReactNode }> = {
  '/downloads': { title: 'nav.downloads', view: Downloads },
  '/history': { title: 'nav.history', view: History },
  '/settings': { title: 'nav.settings', view: Settings },
}

export default function App() {
  const t = useT()
  const route = useRoute()
  const { lang, serverUrl, token } = useSettings()
  const page = views[route]
  const title = page ? t(page.title) : 'OpenTakez'
  const heading = useRef<HTMLHeadingElement>(null)
  const firstRoute = useRef(true)

  useEffect(() => {
    document.documentElement.lang = lang
  }, [lang])

  // Announce client-side navigation: update the title and move focus to the new heading.
  useEffect(() => {
    document.title = page ? `${title} · OpenTakez` : 'OpenTakez'
    if (firstRoute.current) firstRoute.current = false
    else heading.current?.focus()
  }, [route, title, page])

  useEffect(() => {
    setUnauthorizedHandler(() => tokenPrompt.set(true))
    void loadHistory().then(restoreJobs)
  }, [])

  // Streams that stopped on a 401 continue once a valid token is set.
  useEffect(() => {
    if (token) resumeJobs()
  }, [token])

  // Ask for a token up front when this server needs one.
  useEffect(() => {
    api
      .health()
      .then((health) => {
        if (health.auth_required && !settingsStore.get().token) tokenPrompt.set(true)
      })
      .catch(() => {})
  }, [serverUrl])

  return (
    <div className="min-h-dvh pb-[calc(3.5rem+env(safe-area-inset-bottom))] sm:pb-0">
      <header className="mx-auto flex max-w-[40rem] items-center gap-3 px-4 pt-4 pb-3 sm:pt-6">
        <img src="/logo.svg" alt="" className="size-8" />
        <h1 ref={heading} tabIndex={-1} className="text-lg font-bold tracking-tight focus:outline-none">
          {title}
        </h1>
        <div className="ml-auto hidden sm:block">
          <Nav />
        </div>
      </header>
      <main className="mx-auto max-w-[40rem] px-4">{page ? <page.view /> : <Home />}</main>
      <div className="sm:hidden">
        <Nav />
      </div>
      <TokenDialog />
    </div>
  )
}
