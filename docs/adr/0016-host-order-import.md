# 0016. Host order import: each order succeeds or fails on its own, and resends are duplicates

**Status:** accepted

## Context
The host system (ERP / order management) sends orders in batches and resends them after timeouts or restarts. If one bad order failed the whole batch, one typo would block 499 good orders. If a resend updated the existing order, an order that's already planned into a wave could change under the warehouse.

## Decision
`POST /api/v1/integrations/host/orders` (up to 500 orders) returns 200 with one result per order, as long as the batch itself is well formed:

| Result | Meaning |
|---|---|
| `ACCEPTED` | Stored as a new `RECEIVED` order |
| `DUPLICATE` | An order with this external reference already exists (or appeared earlier in the batch). **It was not changed** |
| `REJECTED` | Invalid, with field errors such as `lines[0].sku: unknown SKU NO-SUCH-SKU`. Nothing was stored |

```json
{ "accepted": 2, "duplicates": 0, "rejected": 1,
  "results": [ { "externalRef": "SO-1", "result": "ACCEPTED", "errors": [] },
               { "externalRef": "SO-2", "result": "REJECTED",
                 "errors": [ { "field": "carrier", "message": "must not be blank" },
                             { "field": "lines[0].sku", "message": "unknown SKU NO-SUCH-SKU" } ] } ] }
```

- **Validation before writing.** Each order is fully checked before anything is stored: bean validation per order (not per batch), unique line numbers, and SKUs that exist (looked up in one query).
- **One savepoint per order.** `OrderService.receive` runs with `Propagation.NESTED`, as a savepoint inside the request's transaction. If a concurrent request imports the same order in the meantime, only that order's savepoint rolls back, and it's reported as a `DUPLICATE`:

  [`wms-core/.../orders/OrderService.java`](../../wms-core/src/main/java/com/cloudwms/core/orders/OrderService.java)
  ```java
  @Transactional(propagation = Propagation.NESTED)
  public long receive(Order order) {
      return repository.insert(order);
  }
  ```
- **Consistent insert order.** Orders are inserted sorted by external reference, the unique index's order, and results are still returned in batch order ([`HostOrderImporter.java`](../../wms-core/src/main/java/com/cloudwms/core/orders/api/HostOrderImporter.java)).

## Consequences
- Five concurrent imports of the same 20 orders accept each exactly once: 20 accepted and 80 duplicates in total ([`HostOrderImportTest`](../../wms-core/src/test/java/com/cloudwms/core/orders/HostOrderImportTest.java)). This needed [0017](0017-read-committed-and-lock-conflicts.md).
- Changing an existing order is a separate, explicit operation (hold, cancel, reprioritize), not a silent side effect of a resend.
- Raw inbound messages aren't stored yet. That's the integration-monitor work later.
