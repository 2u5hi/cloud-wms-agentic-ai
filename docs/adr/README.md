# Architecture decision records

One file per decision: the context, what was decided, where it lives in the code, and the consequences.

| # | Decision | Area |
|---|---|---|
| [0001](0001-maven-and-pinned-versions.md) | Maven, with every version pinned and enforced | Build |
| [0002](0002-jdbcclient-over-jpa.md) | Spring `JdbcClient` with explicit SQL instead of JPA | Persistence |
| [0003](0003-database-enforced-invariants.md) | Invariants enforced by the database: CHECK constraints and an append-only ledger | Persistence |
| [0004](0004-inventory-ledger-and-balances.md) | Location-level balances plus an append-only ledger, written together | Inventory |
| [0005](0005-ordered-row-locking.md) | Lock balance rows in a fixed order to prevent deadlocks | Concurrency |
| [0006](0006-property-tests-without-a-library.md) | Property tests with a seeded `Random`, not jqwik | Testing |
| [0007](0007-idempotency-keys-in-the-command-transaction.md) | Idempotency keys recorded in the same transaction as the command | API |
| [0008](0008-api-conventions.md) | Business codes in URLs, cursor pagination, problem+json error codes | API |
| [0009](0009-contract-first-openapi.md) | Committed OpenAPI contract with a drift check and a generated TypeScript client | API |
| [0010](0010-zones-and-location-types.md) | Zones group locations; each location has its own type | Domain |
| [0011](0011-order-holds-as-a-flag.md) | Order holds are a flag, not a status; short allocation is derived | Domain |
| [0012](0012-actor-attribution.md) | Every change records who made it, through one `CurrentActor` seam | Audit |
| [0013](0013-dev-seed-through-the-ledger.md) | Dev seed data goes through the real inventory service, in one transaction | Tooling |
| [0014](0014-shared-integration-test-context.md) | One Spring context and one MySQL container for all integration tests | Testing |
| [0015](0015-console-state-in-the-url.md) | Console filters live in the URL; plain tables until client-side features are needed | Frontend |
| [0016](0016-host-order-import.md) | Host order import: each order succeeds or fails on its own; resends are duplicates | Integration |
| [0017](0017-read-committed-and-lock-conflicts.md) | READ COMMITTED isolation; deadlocks become a retryable 503 | Concurrency |
| [0018](0018-timestamps-in-utc.md) | Timestamps are UTC end to end; tests run outside UTC to catch time zone bugs | Persistence |
| [0019](0019-mvp-first-deployment.md) | Ship a deployed MVP first, then deepen; Railway + Vercel for now | Process |
| [0020](0020-wave-planning.md) | Wave planning: promise stock where it sits; serialize planners with a row lock | Domain |
| [0021](0021-claiming-and-completing-work.md) | Claiming work: ordered candidates with a conditional update; completion moves stock | Concurrency |
| [0022](0022-deterministic-wave-diagnosis.md) | The engine diagnoses blockers; the agent explains them | Agent |
| [0023](0023-api-views-own-nullability.md) | API views own the contract; domain records are never returned directly | API |
| [0024](0024-agent-proposes-wms-executes.md) | The agent reads the public API and proposes; approval runs the command in the WMS | Agent |
