# 0008. Business codes in URLs, cursor pagination, problem+json error codes

**Status:** accepted

## Context
The API's clients are the console, host systems (ERPs), simulators and the AI agent. All of them think in business identifiers (`SKU-10001`, `A-01-01-A`), need stable pagination over large tables, and need to branch on errors reliably.

## Decision

**Resources are addressed by business code.** Internal IDs never leave the service.
```
GET /api/v1/skus/SKU-10001/availability
POST /api/v1/inventory/moves   {"sku": "SKU-10035", "fromLocation": "C-02-06-D", "toLocation": "C-01-09-A", "quantity": 15}
```

**Every list uses keyset (cursor) pagination.** `?limit=&cursor=` returns `{items, nextCursor}`. The cursor is the base64-encoded sort key of the last row, so page 100 costs the same as page 1. Composite keys are expanded so MySQL can range-scan the primary key:

[`wms-core/.../inventory/api/InventoryQueries.java`](../../wms-core/src/main/java/com/cloudwms/core/inventory/api/InventoryQueries.java)
```sql
WHERE (b.location_id > :afterLocation OR (b.location_id = :afterLocation AND b.sku_id > :afterSku))
ORDER BY b.location_id, b.sku_id
LIMIT :limit   -- limit + 1; the extra row only says whether another page exists
```

**Errors are RFC 9457 problem+json with a stable `code`**, plus structured context fields:
```json
{ "type": "urn:wms:problem:insufficient-inventory", "status": 409, "code": "INSUFFICIENT_INVENTORY",
  "detail": "Only 10 of 11 units available", "available": 10, "requested": 11 }
```
`ErrorCode` has no HTTP dependency, so domain code can use it. The HTTP status is mapped in one exhaustive switch, and adding a code without choosing its status won't compile:

[`wms-core/.../shared/error/ApiExceptionHandler.java`](../../wms-core/src/main/java/com/cloudwms/core/shared/error/ApiExceptionHandler.java)
```java
static HttpStatus statusOf(ErrorCode code) {
    return switch (code) {
        case VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
        case INVALID_STATE_TRANSITION, VERSION_CONFLICT, INSUFFICIENT_INVENTORY -> HttpStatus.CONFLICT;
        ...
    };
}
```

**Input is validated before the domain.** For example, a move to the same location or a zero adjustment returns a 400 naming the field. The domain would reject these too, but with `IllegalArgumentException`, which is reserved for programming bugs and surfaces as a 500.

## Consequences
- Lock conflicts return 503 `CONCURRENCY_CONFLICT` with `Retry-After` ([0017](0017-read-committed-and-lock-conflicts.md)).
- Every failure a client can cause has a machine-readable code. The agent will branch on these.
- Commands return 201 with a `Location` header pointing at the ledger entry they created.
