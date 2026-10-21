import { createStore, set } from 'idb-keyval'
import { beforeEach, describe, expect, it } from 'vitest'
import { type HistoryItem, historyStore, loadHistory, normalizeHistoryItem } from './history'

const base: HistoryItem = {
  id: 'a',
  url: 'https://youtu.be/a',
  title: 'T',
  platform: 'youtube',
  kind: 'video',
  option: 'video-best',
  fileName: 'one.mp4',
  fileNames: ['one.mp4'],
  createdAt: 1,
}

beforeEach(() => historyStore.set([]))

describe('history', () => {
  it('derives fileNames from fileName for old records', () => {
    const { fileNames: _drop, ...old } = base
    expect(normalizeHistoryItem(old as HistoryItem).fileNames).toEqual(['one.mp4'])
    expect(normalizeHistoryItem({ ...base, fileNames: [] }).fileNames).toEqual(['one.mp4'])
  })

  it('drops non-string file names and falls back when none are left', () => {
    const dirty = { ...base, fileNames: ['a.jpg', 3, null, '', 'b.jpg'] } as unknown as HistoryItem
    expect(normalizeHistoryItem(dirty).fileNames).toEqual(['a.jpg', 'b.jpg'])

    const junk = { ...base, fileNames: [null, 7] } as unknown as HistoryItem
    expect(normalizeHistoryItem(junk).fileNames).toEqual(['one.mp4'])

    const nothing = { ...base, fileName: undefined, fileNames: [] } as unknown as HistoryItem
    expect(normalizeHistoryItem(nothing).fileNames).toEqual(['T'])
  })

  it('keeps fileNames when present', () => {
    const item = { ...base, fileNames: ['a.jpg', 'b.jpg'] }
    expect(normalizeHistoryItem(item).fileNames).toEqual(['a.jpg', 'b.jpg'])
  })

  it('normalizes old records on load', async () => {
    const { fileNames: _drop, ...old } = base
    await set('old', { ...old, id: 'old' }, createStore('opentakez', 'history'))

    await loadHistory()

    expect(historyStore.get().find((i) => i.id === 'old')?.fileNames).toEqual(['one.mp4'])
  })
})
