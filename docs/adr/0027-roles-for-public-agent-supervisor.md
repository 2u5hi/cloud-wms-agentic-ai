# 0027. Three kinds of caller, enforced by the server

**Status:** accepted

## Context
[ADR 0024](0024-agent-proposes-wms-executes.md) says the agent proposes and a human approves. Until now that was
true only because the agent's client code had no approve method: wms-core had no authentication, every
caller was recorded as `HUMAN/unauthenticated`, and the agent's identity was an `X-Agent-Id` header anyone
could send. Nothing stopped the agent service — or anyone on the internet, once deployed — from calling
`/approve`. The proposal endpoint also only checked a suggestion's shape, so the model could propose a task
from another wave or an uncertified worker and it would be presented to a supervisor as a valid fix.

Real accounts (Cognito) are Phase 5 of [PLAN.md](../PLAN.md). The boundary has to hold before anything is
public, which is Phase 1.

## Decision
**Three kinds of caller**, told apart by one header, `Authorization: Bearer <credential>`:

| Caller | Credential | Role | May |
|---|---|---|---|
| Anyone | none | — | Read (`GET /api/**`, health, API docs) |
| ops-agent | `AGENT_TOKEN` | `AGENT` | Read, and `POST /api/v1/proposals` |
| Supervisor | `DEMO_PASSCODE` | `SUPERVISOR` | Everything, including approve and reject |

The rules live in one place, [`SecurityConfig`](../../wms-core/src/main/java/com/cloudwms/core/shared/security/SecurityConfig.java),
ordered so the agent's one allowed command comes before the supervisor-only catch-all:

```java
.requestMatchers(HttpMethod.GET, "/api/**", ...).permitAll()
.requestMatchers(HttpMethod.POST, "/api/v1/proposals").hasAnyRole("AGENT", "SUPERVISOR")
.requestMatchers(HttpMethod.POST, "/api/**").hasRole("SUPERVISOR")
.anyRequest().denyAll()
```

**Identity comes from the credential.** [`CurrentActor`](../../wms-core/src/main/java/com/cloudwms/core/shared/actor/CurrentActor.java)
reads the authenticated principal, so the audit trail records `AGENT/ops-agent` or `HUMAN/demo-supervisor`
because the server checked it, not because the caller said so. `X-Agent-Id` is gone. Idempotency keys were
already scoped per principal ([ADR 0007](0007-idempotency-keys-in-the-command-transaction.md)); they now scope to a
real one.

**A wrong credential is refused, even on a read.** A mistyped passcode gets 401 "not recognised" instead of
quietly dropping to read-only and failing later on the first command.

**Proposals are checked when they are made.** `REASSIGN_TASK` runs the same checks `reassign` will — task
open, worker certified — plus that the task belongs to the proposal's wave
([`TaskExecutionService.checkReassign`](../../wms-core/src/main/java/com/cloudwms/core/tasks/TaskExecutionService.java)).
The model's output is data from outside the system, so the WMS decides whether it is valid. Approval checks
again, because the floor may have changed in between.

**Fail closed.** Both credentials are required at startup, must differ, and must be at least eight characters;
otherwise the service does not start. The `dev` profile supplies local defaults; the deployed `demo` profile
has none, so a missing secret stops the deploy instead of opening writes.

The console signs in by checking a passcode against `GET /api/v1/me`, keeps it in `sessionStorage` for the
tab, and sends it on every request. ops-agent sends its own token to wms-core and asks for the supervisor
passcode before starting an investigation, since each one costs money.

## Consequences
- The claim in ADR 0024 is now enforced: the agent's token gets 403 on approve, reject, and every other
  command ([`ProposalApiTest`](../../wms-core/src/test/java/com/cloudwms/core/proposals/ProposalApiTest.java),
  [`SecurityApiTest`](../../wms-core/src/test/java/com/cloudwms/core/shared/security/SecurityApiTest.java)).
  Widening the agent's rule to `/api/v1/proposals/**` fails the tests.
- This is not user management. One shared supervisor passcode means the audit trail says "a supervisor",
  not which one. Cognito (Phase 5) replaces the two shared secrets with accounts and OAuth scopes; the
  endpoints, roles and `CurrentActor` seam stay the same.
- Prompt injection is contained, not prevented: text the model reads can still mislead its explanation, but
  it cannot make the WMS do anything a supervisor does not approve, and it cannot propose something the WMS
  would refuse to run.
