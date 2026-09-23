# 0021. Claiming work: ordered candidates with a conditional update

**Status:** accepted

## Context
Workers pull work rather than being pushed it: a handheld asks for the next task. Many workers ask at once, so no two may get the same task, and the order matters — a replenishment that unblocks ten picks should go out before those picks.

## Decisions

**1. Read candidates, then claim with a conditional update.**

[`TaskRepository.java`](../../wms-core/src/main/java/com/cloudwms/core/tasks/TaskRepository.java)
```java
// ordered candidates, read WITHOUT locking
ORDER BY t.priority, (t.type = 'REPLENISH') DESC, (t.zone_id <=> :homeZone) DESC, t.sequence, t.id
LIMIT :candidates
// then, per candidate, until one wins:
UPDATE task SET status = 'ASSIGNED', assigned_worker_id = ?, version = version + 1
WHERE id = ? AND status = 'READY'
```
The obvious `ORDER BY … LIMIT 1 FOR UPDATE SKIP LOCKED` is wrong here, and the concurrency test proved it: **MySQL locks every row it scans before sorting**, so the first worker locked the entire queue and the other nine were told there was nothing to do. (An earlier attempt, `FOR UPDATE OF t`, stopped the join from locking the wave row but not the scan itself.)

**2. Ordering rules:** priority, then **replenishments before picks** (they unblock waiting work), then the worker's own zone, then pick-path sequence. A claim can be scoped with `?zone=`, because workers work an area.

**3. Equipment gates the claim.** A task inherits the equipment of the location it reaches, so only certified workers can claim it:
```sql
AND (t.required_equipment IS NULL OR EXISTS (
      SELECT 1 FROM worker_equipment we
      WHERE we.worker_id = :workerId AND we.equipment = t.required_equipment))
```
This is what makes "the replenishment is stalled because nobody available drives a reach truck" a real, diagnosable state rather than a story. Reassigning to an uncertified worker returns 409 `NOT_ELIGIBLE`.

**4. Completing a task moves real stock** ([`TaskExecutionService`](../../wms-core/src/main/java/com/cloudwms/core/tasks/TaskExecutionService.java)):
- **REPLENISH** → the promise travels with the stock: released at reserve, moved, re-promised at the slot, in one transaction ([`InventoryService.moveAllocated`](../../wms-core/src/main/java/com/cloudwms/core/inventory/InventoryService.java)). Then the picks waiting on it become `READY` and their allocations point at the slot.
- **PICK** → the promise is consumed and the stock leaves the shelf (`pickAllocated`), the line's picked quantity rises, and the order advances `RELEASED → PICKING → PICKED`.
- A wave becomes `IN_PROGRESS` on the first completion and `COMPLETED` when no work is left.

## Consequences
- Ten workers claiming at once get ten different tasks ([`TaskExecutionTest`](../../wms-core/src/test/java/com/cloudwms/core/tasks/TaskExecutionTest.java)).
- Picking a wave through end to end leaves the order `PICKED`, the wave `COMPLETED`, stock at zero, nothing still promised, and every unit accounted for in the ledger.
- Claims cost one small ordered read plus one update. Under heavy contention a worker may try a few candidates; 20 are fetched before giving up.
- Workers are created through the API for now; a labour system would own them.
