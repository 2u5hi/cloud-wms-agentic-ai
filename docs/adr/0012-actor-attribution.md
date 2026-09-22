# 0012. Every change records who made it, through one `CurrentActor` seam

**Status:** accepted (authentication comes later)

## Context
The audit trail has to tell people, the AI agent, automation and host integrations apart. That's the basis for "the supervisor approved this on behalf of agent run 142". Authentication (OAuth through Keycloak) isn't built yet.

## Decision
- Every ledger row records `actor_type` (`HUMAN`, `AGENT`, `SYSTEM`, `INTEGRATION`) and `actor_id`.
- A single component decides who the current caller is. Until authentication exists, it reports the truth: an unauthenticated human.

[`wms-core/.../shared/actor/CurrentActor.java`](../../wms-core/src/main/java/com/cloudwms/core/shared/actor/CurrentActor.java)
```java
public static final Actor UNAUTHENTICATED = new Actor(ActorType.HUMAN, "unauthenticated");

public Actor get() {
    return UNAUTHENTICATED;   // will read the caller from the OAuth token
}
```

`Actor` lives in `shared` rather than `inventory`, because tasks, proposals and the agent all need it.

## Consequences
- Adding authentication changes one method, and every write is attributed correctly from then on.
- Idempotency keys are already scoped by `CurrentActor`, so different callers can reuse the same key string safely.
- Internal writers use explicit system actors, for example `SYSTEM / dev-seed`.
