# 0019. Ship a deployed MVP first, then deepen

**Status:** accepted (2026-09-23)

## Context
The original milestone order built the WMS thoroughly before anything was deployed: events, simulators, integrations and auth all came before the agent, and nothing was reachable on the internet until late. That's good engineering order but poor demo order — the project's value to a reader is a working link, and the agent is what makes it distinctive.

## Decision
Cut a vertical slice to a **deployed demo** first: wave planning → a blocked wave → agent explanation and proposal → approval → execution, with the warehouse data that already exists. Then deepen.

Deferred (not cancelled): Pub/Sub and the transactional outbox, simulators, Keycloak/OAuth scopes, editable configuration, MHE integration, agent evals, dry-run impact, Kubernetes. The full list and the ordering are in [`docs/MVP_PLAN.md`](../MVP_PLAN.md).

**Hosting:** Railway for `wms-core`, MySQL and `ops-agent`; Vercel for `web`. Chosen for speed — the alternative, Google Cloud (GKE + Cloud SQL + Pub/Sub), stays the target for the deepening phase, because it mirrors Manhattan's published platform.

**Process:** one commit per feature rather than per sub-step, tests run once per commit, browser checks once per UI commit, and always ask before pushing.

## What still makes this "cloud-based"
Railway is a container platform, so the cloud-native properties the design cares about hold on day one:

| Property | How it holds in the MVP |
|---|---|
| Containerized, stateless services | `wms-core` and `ops-agent` ship as Docker images; no local state, so instances can be replaced or scaled |
| Managed database | MySQL runs as a managed Railway service, not a hand-administered server |
| API-only access | Nothing but `wms-core` touches the database; the console and the agent go through REST |
| Config from the environment | Credentials and settings come from environment variables, never the image |
| Deploy on push | CI builds and deploys; releases are small and frequent |

What's deliberately missing until the deepening phase: Kubernetes orchestration, Pub/Sub messaging, managed observability, and IAM-based auth. Moving to GKE + Cloud SQL + Pub/Sub then is a deployment change, not a rewrite, because those properties are already in place.

## Consequences
- A shareable URL exists within a few sessions instead of at the end.
- The demo runs without Pub/Sub (events stay in-process) and without Keycloak (a demo passcode guards writes). Both are called out in the README so a reader isn't misled.
- The agent is real but small: read tools, a grounded explanation, a typed proposal, human approval. Evals and dry-run impact come later.
