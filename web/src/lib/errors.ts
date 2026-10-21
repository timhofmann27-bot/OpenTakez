import type { Translate } from '../i18n'
import { platformName } from './platforms'
import { ApiError } from './api'

export const isLoginRequired = (error: unknown) =>
  error instanceof ApiError && error.code === 'login_required'

/**
 * User-facing text for an API error. Server details are shown as-is for platform errors.
 * `platform` names the source for errors that need it (login required).
 */
export function errorMessage(error: unknown, t: Translate, platform = ''): string {
  if (!(error instanceof ApiError)) return t('error.unknown', { status: '?' })
  if (error.code === 'login_required')
    return platform
      ? t('error.loginRequired', { platform: platformName(platform) })
      : t('error.loginRequiredGeneric')
  switch (error.kind) {
    case 'network':
      return t('error.network')
    case 'busy':
      return t('error.busy')
    case 'badRequest':
      if (error.message === 'No link found') return t('error.noLink')
      if (error.message === "This site isn't supported") return t('error.unsupported')
      return error.message || t('error.unknown', { status: error.status })
    default:
      return error.message || t('error.unknown', { status: error.status })
  }
}

const HOSTS: [RegExp, string][] = [
  [/(^|\.)instagram\.com$/, 'instagram'],
  [/(^|\.)tiktok\.com$/, 'tiktok'],
  [/(^|\.)(facebook\.com|fb\.watch|fb\.com)$/, 'facebook'],
]

/** Best-effort platform from pasted text, for errors raised before the server tells us. */
export function guessPlatform(text: string): string {
  const link = text.match(/https?:\/\/[^\s]+/)?.[0]
  if (!link) return ''
  try {
    const host = new URL(link).hostname.toLowerCase()
    return HOSTS.find(([pattern]) => pattern.test(host))?.[1] ?? ''
  } catch {
    return ''
  }
}
