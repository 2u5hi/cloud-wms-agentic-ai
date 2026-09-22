# 0002. Spring `JdbcClient` with explicit SQL instead of JPA

**Status:** accepted

## Context
Warehouse execution is concurrency-heavy. Correctness depends on which rows are locked, in what order, and exactly when each write happens. JPA hides all of this behind lazy loading, dirty checking and flush timing.

## Decision
Use Spring's `JdbcClient` with hand-written SQL for all persistence. Every query, lock and write is visible where it happens.

[`wms-core/.../inventory/InventoryRepository.java`](../../wms-core/src/main/java/com/cloudwms/core/inventory/InventoryRepository.java)
```java
return jdbc.sql("""
        SELECT on_hand, allocated FROM inventory_balance
        WHERE location_id = ? AND sku_id = ?
        FOR UPDATE""")
    .params(key.locationId(), key.skuId())
    .query((rs, row) -> new InventoryBalance(key, rs.getInt("on_hand"), rs.getInt("allocated")))
    .single();
```

## Consequences
- Row locks (`FOR UPDATE`, and later `SKIP LOCKED`) are explicit and reviewable, and the concurrency tests can target them precisely.
- The domain model stays plain Java records, with no entity annotations or proxies.
- Mapping rows is manual. That's more code than JPA, but it's simple code.
- Less practice with JPA, which many Spring shops use. This was a deliberate trade-off.
