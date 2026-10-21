import { describe, expect, it } from 'vitest'
import { formatDuration, formatPercent, formatSpeed } from './format'

describe('format', () => {
  it.each([
    [19, '0:19'],
    [61.4, '1:01'],
    [3725, '1:02:05'],
    [null, ''],
    [Number.NaN, ''],
  ])('formatDuration(%s) = %s', (input, expected) => {
    expect(formatDuration(input)).toBe(expected)
  })

  it.each([
    [512, '512 B/s'],
    [1536, '1.5 KB/s'],
    [5 * 1024 * 1024, '5.0 MB/s'],
    [20 * 1024 * 1024, '20 MB/s'],
    [null, ''],
  ])('formatSpeed(%s) = %s', (input, expected) => {
    expect(formatSpeed(input)).toBe(expected)
  })

  it('formatPercent floors so 100 % only shows when complete', () => {
    expect(formatPercent(0.999)).toBe('99 %')
    expect(formatPercent(1)).toBe('100 %')
    expect(formatPercent(null)).toBe('')
  })
})
