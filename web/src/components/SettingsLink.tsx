import { useT } from '../i18n'
import { navigate } from '../lib/useRoute'

/** In-app link to the Settings view (where cookies are uploaded). */
export function SettingsLink() {
  const t = useT()
  return (
    <a
      href="/settings"
      onClick={(event) => {
        if (event.metaKey || event.ctrlKey || event.shiftKey || event.button !== 0) return
        event.preventDefault()
        navigate('/settings')
      }}
      className="inline-flex min-h-11 items-center self-start rounded-lg px-1 text-sm font-medium text-accent underline underline-offset-2"
    >
      {t('nav.settings')}
    </a>
  )
}
