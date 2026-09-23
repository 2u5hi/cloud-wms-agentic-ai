import { describe, expect, it } from 'vitest'

import { formatAge, hoursUntil, plural } from './format'

describe('formatAge', () => {
  it('reads as minutes, hours, then days', () => {
    expect(formatAge(0)).toBe('0 min')
    expect(formatAge(59)).toBe('59 min')
    expect(formatAge(90)).toBe('1h 30m')
    expect(formatAge(60 * 26 + 30)).toBe('1d 2h')
  })
})

describe('hoursUntil', () => {
  const now = Date.parse('2026-09-23T12:00:00Z')

  it('is positive before the cutoff and negative after it', () => {
    expect(hoursUntil('2026-09-23T15:30:00Z', now)).toBe(3.5)
    expect(hoursUntil('2026-09-23T11:00:00Z', now)).toBe(-1)
  })
})

describe('plural', () => {
  it('only pluralises past one', () => {
    expect(plural(1, 'order')).toBe('1 order')
    expect(plural(0, 'order')).toBe('0 orders')
    expect(plural(2000, 'unit')).toBe('2,000 units')
  })
})
