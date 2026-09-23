import type { components } from '@/api/schema'

export type Blocker = components['schemas']['BlockerView']
export type RootCause = Blocker['rootCause']

export type BlockerAdvice = {
  /** Whether anyone needs to act: `stuck` needs a decision, `moving` is already being worked. */
  severity: 'stuck' | 'waiting' | 'moving'
  headline: string
  /** What a supervisor would do about it. The agent proposes the same actions in commit 5. */
  action: string
}

/**
 * How each root cause reads on the floor. The engine decides *what* is wrong (ADR 0022); this only
 * decides how to say it, so it stays a pure lookup the console and the agent panel can share.
 */
export function advise(blocker: Blocker): BlockerAdvice {
  const equipment = blocker.replenishment?.requiredEquipment
  switch (blocker.rootCause) {
    case 'NO_ELIGIBLE_WORKER_AVAILABLE':
      return {
        severity: 'stuck',
        headline: equipment ? `Waiting on a ${equipment} driver` : 'Waiting on an available worker',
        action: equipment
          ? `Free up or certify a ${equipment} driver, or move the stock with equipment someone has`
          : 'Make a worker available in this zone',
      }
    case 'NOT_PICKED_UP':
      return {
        severity: 'waiting',
        headline: 'Nobody has picked this replenishment up yet',
        action: 'Assign it to an available worker so the picks can start',
      }
    case 'IN_PROGRESS':
      return {
        severity: 'moving',
        headline: blocker.replenishment?.assignedWorker
          ? `${blocker.replenishment.assignedWorker} is on it`
          : 'Somebody is on it',
        action: 'No action needed; the picks release when it completes',
      }
    case 'NO_STOCK_AVAILABLE':
      return {
        severity: 'stuck',
        headline: `No stock anywhere covers ${blocker.quantity} units of ${blocker.sku}`,
        action: 'Short the line, substitute the SKU, or wait for a receipt',
      }
  }
}
