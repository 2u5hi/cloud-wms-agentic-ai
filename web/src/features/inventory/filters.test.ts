import { describe, expect, it } from 'vitest'

import { filtersFromParams, filtersToParams } from './filters'

describe('inventory filters in the URL', () => {
  it('reads filters from search params', () => {
    expect(filtersFromParams(new URLSearchParams('sku=SKU-10001&zone=A&type=RESERVE'))).toEqual({
      sku: 'SKU-10001',
      zone: 'A',
      type: 'RESERVE',
    })
  })

  it('ignores blank values and unknown location types', () => {
    expect(filtersFromParams(new URLSearchParams('sku=%20%20&type=ROOF'))).toEqual({
      sku: undefined,
      zone: undefined,
      type: undefined,
    })
  })

  it('writes only the filters that are set, trimmed', () => {
    expect(filtersToParams({ sku: ' SKU-10001 ', type: 'FORWARD_PICK' }).toString()).toBe(
      'sku=SKU-10001&type=FORWARD_PICK',
    )
    expect(filtersToParams({}).toString()).toBe('')
  })

  it('round-trips', () => {
    const filters = { sku: 'SKU-10035', zone: 'C', type: 'FORWARD_PICK' as const }
    expect(filtersFromParams(filtersToParams(filters))).toEqual(filters)
  })
})
