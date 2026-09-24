# 0025. The demo scenario is built by the real planner, not by inserting its end state

**Status:** accepted

## Context
The demo needs a wave that is blocked for a specific, explainable reason: the pick face is empty, the stock
to refill it is on a high shelf, and the one worker certified for a reach truck is busy. The quick way is to
insert `wave`, `task` and `allocation` rows in that state. But then the demo proves nothing: a hand-written
blocker would show up in the diagnosis whether or not the planner and the diagnosis work.

## Decision
[`DemoScenario`](../../wms-core/src/main/java/com/cloudwms/core/devdata/DemoScenario.java) sets up the
*conditions* and lets the WMS produce the blocked wave, following [ADR 0013](0013-dev-seed-through-the-ledger.md):

| Step | How |
|---|---|
| Workers: two pickers available, the reach-truck driver `BUSY` | Master data, inserted directly |
| Eight orders: six for SKUs whose pick face holds enough, two needing more than it holds | `OrderService.receive` |
| The wave, its allocations, the replenishments and the waiting picks | `WavePlanningService.plan`, then `release` |

The two stuck SKUs are chosen by query from the deterministic seed: some stock at the pick face, and *every*
reserve unit on a shelf that needs a reach truck, so the planner has no floor-level source to pick instead.

```sql
HAVING SUM(rl.required_equipment IS NULL) = 0 AND MAX(rb.on_hand - rb.allocated) >= 12
   AND forward BETWEEN 1 AND 20
```

Cutoffs are relative to boot time — two and two and a half hours out for the stuck orders — so they land in
the diagnosis's four-hour at-risk window with time left to act. It runs once, under the `dev` profile, and
skips itself if any `SO-DEMO-` order exists.

## Consequences
- The whole story is one test ([`DevDataSeederTest`](../../wms-core/src/test/java/com/cloudwms/core/devdata/DevDataSeederTest.java)):
  seed, see two `NO_ELIGIBLE_WORKER_AVAILABLE` blockers and two at-risk orders, approve the reassignment the
  agent would propose, see both move to `IN_PROGRESS`. Making the driver `AVAILABLE` fails it, as it should.
- If the planner's source order changes, the demo changes with it, and the test says so.
- The scenario is single-shot. On a public demo the first visitor who approves the fix "solves" it for
  everyone, and cutoffs age into "missed". A reset is part of the deployment commit.
