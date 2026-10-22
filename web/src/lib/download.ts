import { fileUrl, ticketFor, zipUrl } from './api'

function trigger(href: string, fileName?: string) {
  const link = document.createElement('a')
  link.href = href
  link.download = fileName ?? ''
  // Cross-origin servers may ignore download; the API sets Content-Disposition: attachment.
  document.body.append(link)
  try {
    link.click()
  } finally {
    link.remove()
  }
}

/** Fetches a ticket, then starts the browser download of one file. */
export async function downloadFile(jobId: string, index: number, fileName: string) {
  trigger(fileUrl(jobId, index, await ticketFor(jobId)), fileName)
}

/** Fetches a ticket, then starts the browser download of the ZIP. */
export async function downloadZip(jobId: string) {
  trigger(zipUrl(jobId, await ticketFor(jobId)))
}
