import { HistoryRow } from '../components/HistoryRow'
import { Icon } from '../components/Icon'
import { useT } from '../i18n'
import { clearHistory, useHistory } from '../lib/history'

export function History() {
  const t = useT()
  const items = useHistory()

  if (!items.length) return <p className="pt-2 text-sm text-muted">{t('history.empty')}</p>
  return (
    <div className="flex flex-col gap-2">
      <ul className="divide-y divide-line">
        {items.map((item) => (
          <HistoryRow key={item.id} item={item} />
        ))}
      </ul>
      <button
        type="button"
        onClick={() => void clearHistory()}
        className="inline-flex min-h-11 items-center gap-2 self-start rounded-lg px-3 text-sm text-muted hover:bg-surface-2"
      >
        <Icon name="trash" className="size-4" />
        {t('history.clear')}
      </button>
    </div>
  )
}
