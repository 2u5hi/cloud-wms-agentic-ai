import { describe, expect, it } from 'vitest'

import { advise, type Blocker } from './blockers'

function blocker(overrides: Partial<Blocker>): Blocker {
  return {
    kind: 'WAITING_ON_REPLENISHMENT',
    rootCause: 'NOT_PICKED_UP',
    sku: 'SKU-10035',
    affectedPicks: 1,
    affectedOrders: 1,
    quantity: 10,
    detail: 'engine detail',
    orders: [],
    ...overrides,
  }
}

describe('advise', () => {
  it('names the equipment nobody is certified for', () => {
    const advice = advise(
      blocker({
        rootCause: 'NO_ELIGIBLE_WORKER_AVAILABLE',
        replenishment: { requiredEquipment: 'REACH_TRUCK' } as Blocker['replenishment'],
      }),
    )

    expect(advice.severity).toBe('stuck')
    expect(advice.headline).toContain('REACH_TRUCK')
    expect(advice.action).toContain('REACH_TRUCK')
  })

  it('falls back when the replenishment needs no equipment', () => {
    const advice = advise(blocker({ rootCause: 'NO_ELIGIBLE_WORKER_AVAILABLE', replenishment: null }))

    expect(advice.headline).toBe('Waiting on an available worker')
  })

  it('credits the worker already on it and asks for nothing', () => {
    const advice = advise(
      blocker({
        rootCause: 'IN_PROGRESS',
        replenishment: { assignedWorker: 'W-DRIVER' } as Blocker['replenishment'],
      }),
    )

    expect(advice.severity).toBe('moving')
    expect(advice.headline).toBe('W-DRIVER is on it')
  })

  it('reports short lines with the quantity nothing could cover', () => {
    const advice = advise(blocker({ kind: 'SHORT_ALLOCATED', rootCause: 'NO_STOCK_AVAILABLE', quantity: 5 }))

    expect(advice.severity).toBe('stuck')
    expect(advice.headline).toContain('5 units of SKU-10035')
  })
})
