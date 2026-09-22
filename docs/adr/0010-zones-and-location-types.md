# 0010. Zones group locations; each location has its own type

**Status:** accepted (changes the original design, which gave zones a kind)

## Context
The original design gave each zone a kind (PICK, RESERVE, ...). In a real aisle, the floor-level forward-pick slots and the reserve racking above them share the same zone, so a zone-level kind can't describe that.

## Decision
- A zone is just a grouping (an aisle block, shipping).
- Each location has its own `type`, plus the equipment needed to reach it.

[`wms-core/.../db/migration/V1__inventory_schema.sql`](../../wms-core/src/main/resources/db/migration/V1__inventory_schema.sql)
```sql
CONSTRAINT ck_location_type CHECK (type IN ('FORWARD_PICK', 'RESERVE', 'STAGING', 'PACK', 'DOCK'))
required_equipment VARCHAR(30) NULL   -- e.g. REACH_TRUCK for high reserve
```

The dev layout ([`DevDataSeeder.java`](../../wms-core/src/main/java/com/cloudwms/core/devdata/DevDataSeeder.java)) follows this. Codes read `A-01-01-A`: zone, aisle, bay and level.

| Level | Type | Equipment |
|---|---|---|
| A (floor) | FORWARD_PICK | none |
| B | RESERVE | none |
| C, D | RESERVE | REACH_TRUCK |

## Consequences
- "Replenishment is stuck because no available worker is certified for a reach truck", the core demo scenario, can be expressed in the data.
- Availability can be broken down by location type ([`GET /skus/{code}/availability`](../../wms-core/src/main/java/com/cloudwms/core/inventory/api/InventoryController.java)), which is how the agent will see "short in forward-pick, 42 in reserve".
