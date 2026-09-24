# 0024. The agent proposes; the WMS executes

**Status:** accepted

## Context
The demo needs an agent that does something real — not a chatbot that describes the warehouse, and not
an autonomous process that moves stock because a model decided to. A model that can call write APIs
directly is one bad tool call away from reassigning the wrong work, and no auditor would accept "the
model thought it was a good idea" as the reason a task changed hands.

## Decision
Three rules, enforced by structure rather than by prompting.

**1. The agent reads through the same public API as the console.** No database access, no private
endpoints ([`WmsClient`](../../ops-agent/src/ops_agent/wms.py)). Whatever a supervisor can see, it can
see; nothing more.

**2. Its only write is a proposal.** `POST /api/v1/proposals` stores what it wants done, why, and what
it read. Nothing on the floor changes. Approving is what runs the command, in the approver's
transaction ([`ProposalService`](../../wms-core/src/main/java/com/cloudwms/core/proposals/ProposalService.java)):

```java
@Transactional
public ProposalRow approve(long id, Actor actor, String note) {
    ProposalRow proposal = open(id);
    execute(proposal);                                  // the same service the console calls
    repository.decide(id, "EXECUTED", actor, note);
    return repository.find(id).orElseThrow();
}
```

If the command fails — the worker is not certified, the task is already done — the transaction rolls
back: no change, and the proposal stays `PROPOSED` for somebody to try something else.

**3. The set of things it can ask for is a closed enum.** `ProposalKind` has one member today,
`REASSIGN_TASK`, and the database repeats it in `ck_proposal_kind` (V6). Adding a kind means adding an
executor and a migration — a code review, not a prompt change. The payload is validated by the WMS when
the proposal is made, so a malformed suggestion is rejected before anyone sees it.

The row is the audit trail: kind, payload, rationale, evidence, who proposed (`AGENT`/`ops-agent`), who
decided, when, and their note.

## Cost and the model's job
The deterministic diagnosis ([ADR 0022](0022-deterministic-wave-diagnosis.md)) is fetched before the
model is asked anything and passed in the first message, so the model spends tokens on explaining and
deciding, not on joining tables. A typical investigation is one or two calls on Haiku 4.5, a fraction of
a cent. Caps in [`config.py`](../../ops-agent/src/ops_agent/config.py): tool calls per run, output
tokens, and a daily USD budget that refuses further runs once spent.

## Consequences
- The console is useful with no API key at all: blockers still show, the agent panel says it is not
  configured.
- Proposals are reviewable after the fact, including the evidence the agent claimed to be using.
- The agent cannot be prompted into doing something the API does not already allow a human to do.
- The agent's identity and its propose-only limit are enforced by the server since
  [ADR 0027](0027-roles-for-public-agent-supervisor.md), which replaced the trusted `X-Agent-Id` header.
- One proposal kind is thin. It covers the demo blocker (`NO_ELIGIBLE_WORKER_AVAILABLE`); re-sourcing a
  replenishment from a location that needs no equipment is the obvious second, and needs a new command
  in core first.
