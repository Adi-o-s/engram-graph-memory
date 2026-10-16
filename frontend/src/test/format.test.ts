import { describe, expect, it } from 'vitest'
import { ms, shortId, timeAgo, titleCase } from '../lib/format'

describe('format helpers', () => {
  it('formats relative times', () => {
    const now = Date.parse('2026-10-16T12:00:00Z')
    expect(timeAgo('2026-10-16T11:59:58Z', now)).toBe('just now')
    expect(timeAgo('2026-10-16T11:59:30Z', now)).toBe('30s ago')
    expect(timeAgo('2026-10-16T11:30:00Z', now)).toBe('30m ago')
    expect(timeAgo('2026-10-14T12:00:00Z', now)).toBe('2d ago')
    expect(timeAgo(undefined, now)).toBe('—')
  })

  it('formats durations, ids and enum names', () => {
    expect(ms(3.456)).toBe('3.46 ms')
    expect(ms(250)).toBe('250 ms')
    expect(ms(1500)).toBe('1.50 s')
    expect(shortId('0123456789abcdef')).toBe('01234567')
    expect(titleCase('IN_PROGRESS')).toBe('In Progress')
  })
})
