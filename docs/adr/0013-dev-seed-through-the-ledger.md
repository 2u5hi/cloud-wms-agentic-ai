# 0013. Dev seed data goes through the real inventory service, in one transaction

**Status:** accepted

## Context
The console and later the agent need a realistic warehouse to work against. Writing stock directly into `inventory_balance` would leave balances with no ledger history, breaking the reconciliation rule from [0004](0004-inventory-ledger-and-balances.md).

## Decision
- Insert master data (zones, locations, SKUs, slots) directly.
- Create all stock through `InventoryService`: receipts into reserve against ASN references, then moves into forward-pick.
- Run the whole seed in one transaction, and only under the `dev` profile.

[`wms-core/.../devdata/DevDataSeeder.java`](../../wms-core/src/main/java/com/cloudwms/core/devdata/DevDataSeeder.java)
```java
int skuCount = transaction.execute(status -> {   // every inventory.record(...) joins this transaction
    createLayout(forwardByZone, reserveByZone);
    List<SeedSku> skus = createSkus();
    slotAndStock(skus, forwardByZone, reserveByZone);
    return skus.size();
});
```

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

## Consequences
- **What it creates:** 1,224 locations and 300 SKUs. Fast movers get the slots nearest the start of the serpentine pick path. About 5% of forward slots start below their minimum, as replenishment candidates.
- **Speed:** one transaction took seeding from 31 s to about 6 s, since there's one commit instead of about 2,000.
- **All or nothing:** an interrupted seed rolls back completely. It's deterministic (random seed 42), and it's skipped if zone `A` already exists.
