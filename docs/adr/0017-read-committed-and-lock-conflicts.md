# 0017. READ COMMITTED isolation, and deadlocks as a retryable 503

**Status:** accepted

## Context
Under MySQL's default isolation level, REPEATABLE READ, InnoDB takes **gap locks** during duplicate-key checks and index inserts. Five concurrent host imports of the same orders deadlocked on every run, even with a consistent insert order. MySQL also discards open savepoints when it kills a deadlocked transaction. Spring's rollback to the nested transaction's savepoint then failed with *"SAVEPOINT SAVEPOINT_3 does not exist"*, which **hid the deadlock** and produced a generic 500.

## Decision
**Use READ COMMITTED for every pooled connection.** The application's correctness relies on explicit row locks (`SELECT ... FOR UPDATE`, [0005](0005-ordered-row-locking.md)), which behave the same under both levels. It never relies on gap locks or repeatable snapshots.

[`wms-core/src/main/resources/application.yml`](../../wms-core/src/main/resources/application.yml)
```yaml
hikari:
  transaction-isolation: TRANSACTION_READ_COMMITTED
```

**Treat lock conflicts as retryable.** A deadlock or lock-wait timeout returns 503 `CONCURRENCY_CONFLICT` with `Retry-After: 1`. The whole transaction rolled back, so retrying with the same `Idempotency-Key` is safe. The handler walks the cause chain, including the application exception Spring keeps inside a `TransactionSystemException`, so a deadlock hidden behind a failed savepoint rollback is still recognized:

[`wms-core/.../shared/error/ApiExceptionHandler.java`](../../wms-core/src/main/java/com/cloudwms/core/shared/error/ApiExceptionHandler.java)
```java
static boolean isLockConflict(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
        if (current instanceof PessimisticLockingFailureException) return true;
        if (current instanceof TransactionSystemException tx && tx.getApplicationException() != null
                && isLockConflict(tx.getApplicationException())) return true;
        ...
    }
    return false;
}
```

## Consequences
- The import deadlock test went from failing every run to passing 5 of 5, and every inventory concurrency test still passes.
- [`DatabaseSettingsTest`](../../wms-core/src/test/java/com/cloudwms/core/DatabaseSettingsTest.java) asserts that pooled connections really run at `READ-COMMITTED`, so a config change can't silently undo this.
- Under READ COMMITTED, two reads in one transaction can see different committed data. Any code that needs a stable view must lock what it reads, which is the existing rule anyway.
- Clients (the host sim and the agent) should retry on 503 `CONCURRENCY_CONFLICT`. Automatic server-side retry is possible later if it proves necessary.
