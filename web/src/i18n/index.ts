import { useMemo } from 'react'
import { type Lang, settingsStore, useSettings } from '../lib/settings'
import { de } from './de'
import { en, type MessageKey } from './en'

const dictionaries: Record<Lang, Record<MessageKey, string>> = { de, en }

export type Translate = (key: MessageKey, vars?: Record<string, string | number>) => string

export function translate(lang: Lang, key: MessageKey, vars?: Record<string, string | number>) {
  const text = dictionaries[lang][key]
  return vars ? text.replace(/\{(\w+)\}/g, (_, name) => String(vars[name] ?? '')) : text
}

export const t: Translate = (key, vars) => translate(settingsStore.get().lang, key, vars)

export function useT(): Translate {
  const { lang } = useSettings()
  return useMemo<Translate>(() => (key, vars) => translate(lang, key, vars), [lang])
}

export type { MessageKey }
