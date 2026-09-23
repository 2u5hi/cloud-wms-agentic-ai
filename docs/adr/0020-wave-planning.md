# 0020. Wave planning: promise stock where it sits, and serialize planners with a row lock

**Status:** accepted

## Context
Planning a wave promises stock to orders and creates the work to fulfil it. Two things had to be decided: what happens when a forward-pick slot can't cover a line, and what happens when two people plan at the same moment.

## Decisions

**1. Stock is promised at the location that physically holds it.** When forward-pick is short, the planner takes the remainder from reserve and creates two tasks: a REPLENISH (reserve → the SKU's slot) and a PICK from the slot that `WAITING`s on it.

[`V4__waves_and_tasks.sql`](../../wms-core/src/main/resources/db/migration/V4__waves_and_tasks.sql) keeps the dependency as data:
```sql
depends_on_task_id  BIGINT NULL,
```
The alternative — promising stock at the slot it hasn't reached yet — would break `allocated <= on_hand` ([ADR 0003](0003-database-enforced-invariants.md)) for as long as the replenishment took. Completing the replenishment later moves the allocation along with the stock.

**2. Planning is serialized by a row lock.** Planning reads open orders and free stock, then writes. Two planners could hand the same order to two waves (and hit the `uq_wave_order_order` constraint as a 500).

[`WaveRepository.java`](../../wms-core/src/main/java/com/cloudwms/core/waves/WaveRepository.java)
```java
void lockForPlanning() {
    jdbc.sql("SELECT id FROM planning_lock WHERE id = 1 FOR UPDATE").query(Integer.class).single();
}
```
A row lock, not MySQL's `GET_LOCK`: the first attempt used an advisory lock released in a `finally` block, which runs **before** the transaction commits. The next planner then read stock the first had promised but not yet committed and failed with `INSUFFICIENT_INVENTORY`. A row lock is held until commit, exactly the window that needs protecting. Picking and execution stay fully concurrent.

**3. Preview writes nothing.** `POST /waves/plan?preview=true` runs the same planner over current data and reports what a real plan would do, without allocating or locking. It is advisory: the real plan recomputes under locks and can differ if stock moved. (Rolling back a real plan was the first idea; it would have fought the idempotency filter's transaction, which wraps the whole request — [ADR 0007](0007-idempotency-keys-in-the-command-transaction.md).)

**4. Planning rules are code defaults for now:** orders by priority then earliest cutoff; within a line, forward-pick in pick-path order, then reserve largest-quantity-first; replenish only what the line needs. They live in one pure class, [`WavePlanner`](../../wms-core/src/main/java/com/cloudwms/core/waves/domain/WavePlanner.java), so they become editable wave templates later without touching persistence.

## Consequences
- An order that gets nothing stays `RECEIVED` and out of the wave; one partly covered is `ALLOCATED` and reports its short quantity.
- Cancelling a planned wave releases the stock, cancels the work, and returns its orders to `RECEIVED`.
- Four concurrent planners over stock for 1.6 orders allocate exactly the 10 units available, and each order lands in exactly one wave ([`WavePlanningTest`](../../wms-core/src/test/java/com/cloudwms/core/waves/WavePlanningTest.java)).
- Planning is a batch operation, so serializing it costs little. If it ever becomes a bottleneck, the lock can be narrowed per carrier or zone.
