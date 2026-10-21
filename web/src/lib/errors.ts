import type { Translate } from '../i18n'
import { ApiError } from './api'

/** User-facing text for an API error. Server details are shown as-is for platform errors. */
export function errorMessage(error: unknown, t: Translate): string {
  if (!(error instanceof ApiError)) return t('error.unknown', { status: '?' })
  switch (error.kind) {
    case 'network':
      return t('error.network')
    case 'badRequest':
      if (error.message === 'No link found') return t('error.noLink')
      if (error.message === "This site isn't supported") return t('error.unsupported')
      return error.message || t('error.unknown', { status: error.status })
    default:
      return error.message || t('error.unknown', { status: error.status })
  }
}
