# 0011. Order holds are a flag, not a status; short allocation is derived

**Status:** accepted (changes the original design, which had `ON_HOLD` and `SHORT` states)

## Context
An order can be put on hold while it's received, allocated, released or being picked. As a status, `ON_HOLD` would have to remember which status to return to when the hold is released. Likewise, "short" isn't a stage of fulfilment. A partially allocated order is still allocated.

## Decision
- `on_hold` and `hold_reason` are separate from `status`, and the database requires them to agree.
- A short allocation is calculated from the lines (`ordered − allocated`), not stored as a status.

[`wms-core/.../db/migration/V3__orders.sql`](../../wms-core/src/main/resources/db/migration/V3__orders.sql)
```sql
CONSTRAINT ck_orders_hold CHECK ((on_hold AND hold_reason IS NOT NULL) OR (NOT on_hold AND hold_reason IS NULL))
```

[`wms-core/.../orders/domain/OrderStatus.java`](../../wms-core/src/main/java/com/cloudwms/core/orders/domain/OrderStatus.java)
```
RECEIVED ──► ALLOCATED ──► RELEASED ──► PICKING ──► PICKED ──► PACKED ──► SHIPPED
   │  ▲          │
   │  └──────────┘ (wave cancelled)
   └──────────┴──► CANCELLED
```

[`wms-core/.../orders/domain/Order.java`](../../wms-core/src/main/java/com/cloudwms/core/orders/domain/Order.java)
```java
public boolean canBePlanned() {
    return status == OrderStatus.RECEIVED && !onHold;
}
```

## Consequences
- Releasing a hold never changes the status.
- A held order can't be planned or released, but it can still be cancelled (for example after a fraud review).
- A released order can't be cancelled. Work is in progress on the floor; stopping it means putting it on hold.
- An order where nothing can be allocated stays `RECEIVED` and isn't planned into a wave.
