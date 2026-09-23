# 0022. The engine diagnoses; the agent explains

**Status:** accepted

## Context
The demo question is "why is wave 27 blocked?". An agent could answer it by reading tasks, allocations, stock and workers and doing the joins and arithmetic itself — which is exactly where LLMs hallucinate, and where the token bill comes from. The reasoning is also deterministic: it's a set of rules, not a judgement call.

## Decision
`GET /api/v1/waves/{n}/diagnosis` computes the blockers in SQL and plain Java. The agent reads the result, explains it in operator language, and decides what to do about it.

The rules live in one pure class, [`WaveDiagnosis`](../../wms-core/src/main/java/com/cloudwms/core/waves/domain/WaveDiagnosis.java), with no database access:

| Blocker | Root cause | When |
|---|---|---|
| `WAITING_ON_REPLENISHMENT` | `NO_ELIGIBLE_WORKER_AVAILABLE` | Unclaimed, and nobody available is certified for the equipment it needs |
| | `NOT_PICKED_UP` | Unclaimed, but eligible workers are available |
| | `IN_PROGRESS` | Somebody is on it |
| `SHORT_ALLOCATED` | `NO_STOCK_AVAILABLE` | Ordered units no stock could cover |

```java
private static RootCause rootCause(PendingReplenishment replenishment, Map<String, Integer> availableByEquipment) {
    if (replenishment.assignedWorker() != null) return RootCause.IN_PROGRESS;
    int eligible = replenishment.requiredEquipment() == null ? availableByEquipment.getOrDefault("", 0)
            : availableByEquipment.getOrDefault(replenishment.requiredEquipment(), 0);
    return eligible == 0 ? RootCause.NO_ELIGIBLE_WORKER_AVAILABLE : RootCause.NOT_PICKED_UP;
}
```

Each blocker carries its own sentence, so an explanation can quote the engine rather than invent one:

> 1 picks wait on replenishment 42: 10 units of SKU-10035 from C-03-04-C to C-01-09-A, unclaimed for 3 min because no available worker is certified for REACH_TRUCK

**Availability is scoped to the wave's zones** ([`WaveQueries`](../../wms-core/src/main/java/com/cloudwms/core/waves/api/WaveQueries.java)): a worker counts only if they're available and assigned to one of the wave's zones, or to none. A reach-truck driver on the other side of the building doesn't make a replenishment "just unclaimed".

The response also carries pick counts by state and **orders at risk** (cutoff within N hours with work outstanding), so the agent gets the urgency without another call.

## Consequences
- An investigation costs a handful of tool calls over compact structured data, not dozens of row reads ([MVP_PLAN](../MVP_PLAN.md) §4 on cost).
- Blockers are sorted with the biggest first, so "the one thing to fix" is `blockers[0]`.
- The console shows the same diagnosis with no AI involved: the WMS is useful without a key.
- The rules are testable on their own ([`WaveDiagnosisTest`](../../wms-core/src/test/java/com/cloudwms/core/waves/domain/WaveDiagnosisTest.java)) and end to end against a real blocked wave ([`WaveDiagnosisApiTest`](../../wms-core/src/test/java/com/cloudwms/core/waves/WaveDiagnosisApiTest.java)).
- Not modelled yet: projected completion time from pick rates. "At risk" currently means cutoff-within-N-hours with work left.
