# 0004. Location-level balances plus an append-only ledger, written together

**Status:** accepted

## Context
Warehouse problems happen at the location level ("forward-pick is empty, but reserve has 42"), so a single on-hand number per SKU isn't enough. Every change also needs an audit trail that can be checked against the current state.

## Decision
- `inventory_balance` holds the current on-hand and allocated quantity per (location, SKU).
- `inventory_txn` records every change. Quantities are always positive; stock leaves `from_location` and arrives at `to_location`.
- Both are written in the same transaction, so for any location, **on-hand = arrivals − departures**.

[`wms-core/.../inventory/domain/InventoryMovement.java`](../../wms-core/src/main/java/com/cloudwms/core/inventory/domain/InventoryMovement.java)
```java
/** How this movement changes on-hand at each location it touches. */
public List<StockDelta> effects() {
    if (fromLocationId != null) effects.add(new StockDelta(new StockKey(fromLocationId, skuId), -quantity));
    if (toLocationId != null)   effects.add(new StockDelta(new StockKey(toLocationId, skuId), quantity));
    ...
}
```

`applyTo` computes every affected balance before returning any of them, so a move whose source is short fails completely:
```java
public List<InventoryBalance> applyTo(Function<StockKey, InventoryBalance> current) {
    return effects().stream().map(change -> current.apply(change.key()).apply(change)).toList();
}
```

Only **available** stock (on-hand − allocated) can be removed. Stock promised to orders must be deallocated first ([`InventoryBalance.java`](../../wms-core/src/main/java/com/cloudwms/core/inventory/domain/InventoryBalance.java)).

## Consequences
- A reconciliation query can check the whole database. [`DevDataSeederTest`](../../wms-core/src/test/java/com/cloudwms/core/devdata/DevDataSeederTest.java) runs it across every balance row.
- Corrections (`ADJUST`, `COUNT_VARIANCE`) always need a reason, so the audit trail always says why stock changed.
