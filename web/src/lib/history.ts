import { clear, createStore as createIdbStore, del, get, set, values } from 'idb-keyval'
import { createStore, useStore } from './store'
import type { DownloadOption, MediaInfo } from './types'

export type HistoryItem = {
  /** The server job id; also the IndexedDB key. */
  id: string
  url: string
  title: string
  uploader?: string | null
  thumbnail?: string | null
  platform: string
  kind: MediaInfo['kind']
  option: DownloadOption
  /** First file; kept for records saved before multi-file jobs. */
  fileName: string
  fileNames: string[]
  createdAt: number
  /** The server deleted the file (TTL expired or server restarted). */
  gone?: boolean
}

const db = createIdbStore('opentakez', 'history')

export const historyStore = createStore<HistoryItem[]>([])
export const useHistory = () => useStore(historyStore)

const newestFirst = (a: HistoryItem, b: HistoryItem) => b.createdAt - a.createdAt

/** Records from before multi-file jobs only have `fileName`. */
export function normalizeHistoryItem(item: HistoryItem): HistoryItem {
  const named = Array.isArray(item.fileNames)
    ? item.fileNames.filter((name): name is string => typeof name === 'string' && name !== '')
    : []
  const fileNames = named.length ? named : [typeof item.fileName === 'string' && item.fileName ? item.fileName : item.title]
  return { ...item, fileNames }
}

export async function loadHistory() {
  const items = (await values<HistoryItem>(db)).map(normalizeHistoryItem)
  // Merge: entries added while IndexedDB was being read must survive.
  historyStore.set((prev) =>
    [...prev, ...items.filter((i) => !prev.some((p) => p.id === i.id))].sort(newestFirst),
  )
}

export async function addHistory(item: HistoryItem) {
  historyStore.set((prev) => [item, ...prev.filter((i) => i.id !== item.id)].sort(newestFirst))
  await set(item.id, item, db)
}

/** Record a finished job unless it is already in the history (keeps its original date). */
export async function addHistoryOnce(item: HistoryItem) {
  if (historyStore.get().some((i) => i.id === item.id) || (await get(item.id, db))) return
  await addHistory(item)
}

export async function markGone(id: string) {
  const item = historyStore.get().find((i) => i.id === id)
  if (!item || item.gone) return
  await addHistory({ ...item, gone: true })
}

export async function removeHistory(id: string) {
  historyStore.set((prev) => prev.filter((i) => i.id !== id))
  await del(id, db)
}

export async function clearHistory() {
  historyStore.set([])
  await clear(db)
}
