# 0003. Invariants enforced by the database: CHECK constraints and an append-only ledger

**Status:** accepted

## Context
The domain code checks every rule, but a bug, a manual SQL fix or a future service could still write bad data. Inventory is the system of record, so the database should refuse impossible states on its own.

## Decision
- Express the core rules as MySQL `CHECK` constraints.
- Make the inventory ledger append-only, with triggers that reject `UPDATE` and `DELETE`.

[`wms-core/.../db/migration/V1__inventory_schema.sql`](../../wms-core/src/main/resources/db/migration/V1__inventory_schema.sql)
```sql
CONSTRAINT ck_balance_on_hand CHECK (on_hand >= 0),
CONSTRAINT ck_balance_allocated CHECK (allocated >= 0 AND allocated <= on_hand)

CREATE TRIGGER trg_inventory_txn_no_update BEFORE UPDATE ON inventory_txn FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'inventory_txn is append-only';
```

MySQL 8.4 has binary logging on by default, and in that mode only a SUPER user can create triggers unless `log_bin_trust_function_creators` is enabled. Cloud SQL needs the same flag. It's set in both [`docker-compose.yml`](../../deploy/compose/docker-compose.yml) and [`TestcontainersConfiguration.java`](../../wms-core/src/test/java/com/cloudwms/core/TestcontainersConfiguration.java):
```yaml
command: ["--default-time-zone=+00:00", "--log-bin-trust-function-creators=1"]
```

Order lines use the same approach ([`V3__orders.sql`](../../wms-core/src/main/resources/db/migration/V3__orders.sql)): `0 <= shipped <= picked <= allocated <= ordered`.

## Consequences
- Corrections are new ledger rows, never edits, so the history can't be rewritten.
- Every constraint is proven by a schema test ([`InventorySchemaTest`](../../wms-core/src/test/java/com/cloudwms/core/inventory/InventorySchemaTest.java), [`OrderSchemaTest`](../../wms-core/src/test/java/com/cloudwms/core/orders/OrderSchemaTest.java)).
- `ck_balance_on_hand` is logically implied by `ck_balance_allocated`. It's kept anyway as explicit documentation of intent.
