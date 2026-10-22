import { useT } from '../i18n'
import { useJobs } from '../lib/jobs'
import { isFinished } from '../lib/types'
import { type Path, navigate, useRoute } from '../lib/useRoute'
import { Icon, type IconName } from './Icon'

const items: { path: Path; icon: IconName; label: 'nav.home' | 'nav.downloads' | 'nav.history' | 'nav.settings' }[] = [
  { path: '/', icon: 'home', label: 'nav.home' },
  { path: '/downloads', icon: 'list', label: 'nav.downloads' },
  { path: '/history', icon: 'clock', label: 'nav.history' },
  { path: '/settings', icon: 'settings', label: 'nav.settings' },
]

export function Nav() {
  const t = useT()
  const route = useRoute()
  const active = useJobs().filter((v) => !v.lost && !isFinished(v.job)).length

  return (
    <nav
      aria-label={t('nav.main')}
      className="fixed inset-x-0 bottom-0 z-20 border-t border-line bg-bg/85 pb-[env(safe-area-inset-bottom)] backdrop-blur-md sm:static sm:border-0 sm:bg-transparent sm:pb-0 sm:backdrop-blur-none"
    >
      <ul className="mx-auto flex max-w-[40rem] sm:gap-1">
        {items.map((item) => {
          const current = route === item.path
          return (
            <li key={item.path} className="flex-1 sm:flex-none">
              <a
                href={item.path}
                aria-current={current ? 'page' : undefined}
                onClick={(e) => {
                  e.preventDefault()
                  navigate(item.path)
                }}
                className={`relative flex min-h-14 flex-col items-center justify-center gap-0.5 text-[0.7rem] font-medium sm:min-h-9 sm:flex-row sm:gap-1.5 sm:rounded-lg sm:px-2.5 sm:text-sm ${current ? 'text-accent sm:bg-accent/10 sm:text-fg' : 'text-muted hover:text-fg'}`}
              >
                {current && (
                  <span aria-hidden="true" className="absolute inset-x-5 top-0 h-0.5 rounded-full bg-accent sm:hidden" />
                )}
                <Icon name={item.icon} />
                {t(item.label)}
                {item.path === '/downloads' && active > 0 && (
                  <span className="absolute top-1.5 left-1/2 ml-2 min-w-4 rounded-full bg-accent px-1 text-center text-[0.65rem] leading-4 text-accent-fg tabular-nums sm:static sm:ml-0">
                    <span aria-hidden="true">{active}</span>
                    <span className="sr-only">{t('downloads.active', { count: active })}</span>
                  </span>
                )}
              </a>
            </li>
          )
        })}
      </ul>
    </nav>
  )
}
