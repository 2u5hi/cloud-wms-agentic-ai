# 0006. Property tests with a seeded `Random`, not jqwik

**Status:** accepted

## Context
Example-based tests can't cover the space of operation sequences, which is where ledger and allocation bugs hide. jqwik, the usual Java property-testing library, is built for JUnit Platform 1.x. Spring Boot 4 uses JUnit Platform 6, so that pairing is unsupported and could break on any upgrade.

## Decision
Write property tests in plain JUnit, with a seeded `java.util.Random`. Each seed produces a deterministic sequence, and a failure names the seed and step to replay.

[`wms-core/.../inventory/domain/InventoryInvariantsPropertyTest.java`](../../wms-core/src/test/java/com/cloudwms/core/inventory/domain/InventoryInvariantsPropertyTest.java)
```java
static final int SEEDS = 1_000;
static final int STEPS_PER_SEED = 200;   // 200,000 random operations, about 1 second
...
check(step, operation, operation.exceedsAvailable(before), "rejected although enough stock was available");
check(step, operation, balances.equals(before), "a rejected operation changed balances");
```

To replay a single seed:
```bash
./mvnw test -Dtest=InventoryInvariantsPropertyTest -Dinventory.seed=42
```

## Consequences
- There's no automatic shrinking of failing sequences, but a seed and a step are enough to reproduce one.
- The test caught four planted bugs, including an off-by-one and a move that forgets its destination.
- Its limit: a bug that corrupts the balance and the ledger *in the same way* stays consistent and passes. Unit tests cover those cases.
