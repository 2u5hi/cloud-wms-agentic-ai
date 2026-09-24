# Product plan

What the finished system is, and the order it gets built in. Each phase ends in something deployed and
demonstrable. Phase 1's commit-by-commit plan is in [`MVP_PLAN.md`](MVP_PLAN.md); later phases get the same
treatment when they start. Decisions along the way are in [`adr/`](adr/README.md).

## The finished product

A cloud-based warehouse management system on AWS, covering a traditional WMS workflow end to end, with an
operations agent that investigates problems and proposes fixes a supervisor approves.

| Pillar | What it means here |
|---|---|
| **Traditional WMS workflow** | Receive → putaway → replenish → wave → pick → pack → ship, plus cycle counts, with a ledger behind every unit |
| **AI** | An agent that reads the WMS through its public API, explains blockers with evidence, and proposes typed fixes with predicted impact; graded by an eval suite |
| **Auth** | Real accounts and roles (Cognito); the agent is a least-privilege client that can propose and never execute |
| **Cloud-based** | Containers on AWS with a managed MySQL, infrastructure as code, deployed from CI; event-driven integration through AWS messaging |

Out of scope, and the README says so: labor standards, billing, multi-warehouse, lots/serials/expiry,
cartonization, carrier rating, returns, yard management.

## Phases

| # | Phase | Delivers | Done when |
|---|---|---|---|
| 1 | **Live MVP** | Waves, tasks, diagnosis, console, agent, demo scenario (done) · roles-based auth · proposal checks at creation · a live model test · AWS deployment with Terraform and deploy-on-push · demo reset | A public URL: anyone can browse, the supervisor passcode approves, the agent's token is refused on approve, a real Claude investigation of the demo wave proposes the right fix, and a reset restores the scenario |
| 2 | **Full workflow** | ASN receipt, putaway tasks into reserve, pack confirmation, ship confirmation, cycle-count tasks with variance adjustments | One order can be followed in the console from the receipt of its stock to its ship confirmation, and the ledger still reconciles |
| 3 | **Events + integration** | Transactional outbox → AWS messaging (LocalStack locally), idempotent consumers, dead-lettering and an integration monitor, alert rules, the agent triggered by alerts, live console updates, host ship-confirms out, a simulated sorter behind an MHE adapter | Injecting a sorter jam raises an alert, the agent investigates on its own, and the failed messages are visible and retryable in the monitor |
| 4 | **Agent depth** | More proposal kinds (re-source a replenishment, reprioritize, hold an order), preconditions and stale detection, dry-run predicted impact, an eval suite graded by the WMS with cost and latency per model | Approving a proposal shows predicted vs. actual impact, and the eval results table is in the docs |
| 5 | **Configuration + identity** | Versioned wave templates, allocation and replenishment rules edited in the console (draft → activate); Cognito accounts and scopes replacing the Phase 1 tokens | Changing the allocation strategy changes a wave preview; writes need a login; the agent's scopes come from Cognito |
| 6 | **Simulators + writeup** | Floor and host simulators with an accelerated clock and fault injection, the solution design document, the technical writeup, the demo video | The demo video's script runs against the live system |

## Where it runs

AWS ([ADR 0026](adr/0026-aws-deployment.md)): the two services as containers, RDS for MySQL, the console on
S3 + CloudFront, secrets in AWS, everything described in Terraform and deployed by GitHub Actions. Exact
compute and database sizes are chosen against current pricing in the deploy commit.

The application keeps the technology families of Manhattan's published platform — Java/Spring, MySQL, REST,
containers, event-driven messaging — and runs them on AWS. It does not claim to run on Manhattan's cloud.
