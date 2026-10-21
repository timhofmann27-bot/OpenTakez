import { useT } from '../i18n'
import type { DownloadOption } from '../lib/types'

type Props = {
  options: DownloadOption[]
  value: DownloadOption
  onChange: (option: DownloadOption) => void
}

export function OptionPicker({ options, value, onChange }: Props) {
  const t = useT()
  const groups = [
    { label: t('option.group.video'), items: options.filter((o) => o.startsWith('video')) },
    { label: t('option.group.audio'), items: options.filter((o) => o.startsWith('audio')) },
    { label: t('option.group.image'), items: options.filter((o) => o.startsWith('image')) },
  ].filter((g) => g.items.length)

  return (
    <fieldset className="flex flex-col gap-3">
      <legend className="sr-only">{t('preview.format')}</legend>
      {groups.map((group, index) => (
        <div key={group.label} role="radiogroup" aria-labelledby={`option-group-${index}`} className="flex flex-col gap-1.5">
          <span id={`option-group-${index}`} className="text-xs font-medium uppercase tracking-wide text-muted">
            {group.label}
          </span>
          <div className="flex gap-1 rounded-xl border border-field-line bg-bg p-1">
            {group.items.map((option) => (
              <label
                key={option}
                className="flex min-h-11 flex-1 cursor-pointer items-center justify-center rounded-lg text-sm font-medium text-muted transition-colors has-checked:bg-accent has-checked:font-semibold has-checked:text-accent-fg has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-accent"
              >
                <input
                  type="radio"
                  name="option"
                  value={option}
                  checked={value === option}
                  onChange={() => onChange(option)}
                  className="sr-only"
                />
                {t(`option.${option}`)}
              </label>
            ))}
          </div>
        </div>
      ))}
    </fieldset>
  )
}
