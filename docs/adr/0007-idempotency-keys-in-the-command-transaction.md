# 0007. Idempotency keys recorded in the same transaction as the command

**Status:** accepted

## Context
Host systems retry after timeouts. Without protection, a retried receipt receives the stock twice. The common implementation (store the key, then run the command) isn't atomic: a crash between the two steps loses the key or applies the command without recording it.

## Decision
Every `POST /api/**` requires an `Idempotency-Key`. The filter runs the whole request inside one transaction:
1. Insert the key row, which locks it.
2. Run the command. `InventoryService`'s `@Transactional` joins the outer transaction.
3. On a 2xx response, store the response and commit everything together. Otherwise, roll back everything.

[`wms-core/.../shared/idempotency/IdempotencyFilter.java`](../../wms-core/src/main/java/com/cloudwms/core/shared/idempotency/IdempotencyFilter.java) (condensed)
```java
Boolean executed = transaction.execute(status -> {
    if (!store.claim(principal, key, requestHash)) {   // blocks on a concurrent holder of the same key
        status.setRollbackOnly();
        return false;
    }
    chain.doFilter(new CachedBodyRequest(request, body), captured);
    if (2xx) store.complete(principal, key, response); else status.setRollbackOnly();
    return true;
});
```

| Situation | Result |
|---|---|
| Same key, same method, path and body | The stored response is replayed with `Idempotent-Replayed: true`; nothing runs |
| Same key, different request | 422 `IDEMPOTENCY_KEY_REUSED` |
| The first attempt failed (4xx or 5xx) | The key is unused and can be retried |

Keys are scoped per caller: `PRIMARY KEY (principal, idem_key)` in [`V2__idempotency_keys.sql`](../../wms-core/src/main/resources/db/migration/V2__idempotency_keys.sql).

## Consequences
- 10 concurrent requests with one key apply exactly once ([`IdempotencyTest`](../../wms-core/src/test/java/com/cloudwms/core/shared/idempotency/IdempotencyTest.java)). The naive two-transaction version failed two tests.
- A database connection is held for the whole request. That's fine for short commands.
- Request bodies are capped at 1 MB, since the body is hashed in memory.
- Old keys aren't purged yet. There's an index on `created_at`, ready for a cleanup job.
