import { describe, expect, it } from 'vitest'
import { de } from './de'
import { en } from './en'
import { translate } from './index'

describe('i18n', () => {
  it('has the same keys in both languages', () => {
    expect(Object.keys(de).sort()).toEqual(Object.keys(en).sort())
  })

  it('fills placeholders', () => {
    expect(translate('de', 'settings.connected', { version: '0.1.0', ytdlp: '2026.8.19' })).toBe(
      'Verbunden: OpenTakez 0.1.0, yt-dlp 2026.8.19',
    )
  })

  it('uses the same placeholders in both languages', () => {
    const placeholders = (text: string) => (text.match(/\{\w+\}/g) ?? []).sort()
    for (const key of Object.keys(en) as (keyof typeof en)[]) {
      expect(placeholders(de[key]), key).toEqual(placeholders(en[key]))
    }
  })
})
