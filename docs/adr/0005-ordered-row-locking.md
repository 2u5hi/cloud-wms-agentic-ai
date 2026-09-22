# 0005. Lock balance rows in a fixed order to prevent deadlocks

**Status:** accepted

## Context
A move touches two balance rows. Two concurrent moves in opposite directions (reserve → forward and forward → reserve) that lock "source, then destination" can each hold one row while waiting for the other. That's the textbook deadlock.

## Decision
Every transaction locks balance rows in (location_id, sku_id) order, whatever the movement's direction.

[`wms-core/.../inventory/InventoryService.java`](../../wms-core/src/main/java/com/cloudwms/core/inventory/InventoryService.java)
```java
static final Comparator<StockKey> LOCK_ORDER = Comparator.comparingLong(StockKey::locationId)
    .thenComparingLong(StockKey::skuId);

movement.effects().stream()
    .map(StockDelta::key)
    .sorted(LOCK_ORDER)
    .forEach(key -> locked.put(key, repository.lockBalance(key)));
```

A missing balance row is created with `ON DUPLICATE KEY UPDATE` rather than `INSERT IGNORE`, which would also silently swallow foreign-key errors.

## Consequences
Proven by [`InventoryServiceTest`](../../wms-core/src/test/java/com/cloudwms/core/inventory/InventoryServiceTest.java):
- 50 concurrent picks of 3 units from 100: exactly 33 succeed, and nothing is oversold.
- 60 concurrent moves in opposite directions: no deadlocks, and stock is conserved.

Planted-bug check: removing `.sorted(LOCK_ORDER)` makes MySQL report *"Deadlock found when trying to get lock"*, and removing the row locks makes the pick test oversell stock.
