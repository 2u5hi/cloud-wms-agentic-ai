# Phase 1 plan: the live MVP

**Goal:** a publicly reachable demo that runs the whole story end to end — orders arrive, a wave is planned, it blocks, the agent explains why and proposes a fix, a supervisor approves, the fix executes. It runs on AWS, with enough auth that the agent provably cannot approve its own proposals.

This is Phase 1 of [`PLAN.md`](PLAN.md), which describes the finished product and phases 2–6. Nothing built here is thrown away later.

---

## 1. Where the project stands

Done and on `main`:

| Area | State |
|---|---|
| Foundation | Docker Compose (MySQL 8.4), CI for `wms-core`, `web` and `ops-agent`, problem+json errors, OpenAPI contract with drift check, generated TypeScript client |
| Inventory | Schema with database-enforced invariants, append-only ledger, pure domain, property tests, ordered row locking, master data / balance / availability / command / ledger APIs, idempotency keys, dev seed (1,224 locations, 300 SKUs), inventory page |
| Orders | Order model (holds as a flag), host import with per-order results, order reads |
| Waves and tasks | Planning with allocation and replenishment, worker claims and task execution, deterministic wave diagnosis (commits 1-3) |
| Console | Orders, Waves, Wave detail with the diagnosis and Plan/Release/Cancel, Tasks (commit 4) |
| Agent | `ops-agent` (FastAPI + Haiku), read tools over the public API, proposals approved in the console (commit 5) |
| Demo scenario | A fresh `dev` database boots into a released wave blocked on a busy reach-truck driver (commit 6) |
| Platform | READ COMMITTED isolation, retryable 503 on lock conflicts, UTC timestamps end to end |
| Docs | 26 ADRs in [`docs/adr/`](adr/README.md), design doc in sync |
| Tests | 174 backend, 19 web, 14 agent |

**Not yet true, and fixed before anything is public:** there is no authentication (every caller is `HUMAN/unauthenticated`, and `X-Agent-Id` is trusted as sent), proposals are only checked for shape when created, the agent has never run against the real model, and nothing is deployed.

Remaining for Phase 1: commits 7 and 8 below.

---

## 2. Phase 1 scope

### In
1. **Wave planning** — select orders, allocate stock, create replenishment tasks when forward-pick is short, create pick tasks that depend on them. Rules are **code defaults**, not editable configuration.
2. **Light execution** — workers with equipment certifications; claim, complete, and reassign tasks.
3. **Wave diagnosis** — a deterministic endpoint that answers "why is this wave blocked?" with structured blockers.
4. **Console** — Orders, Waves (with diagnosis and the agent panel), Tasks, Inventory.
5. **Agent** — Python service, Claude Haiku by default: read tools, an explanation with evidence, a proposed fix, human approval, execution through the same API.
6. **Demo scenario** — the dev seed creates orders and a wave that blocks because the only reach-truck-certified worker is busy.
7. **Auth** — reads are public; the agent's service token can read and propose only; a supervisor passcode can run commands and approve. Proposals are checked against the warehouse when they are created.
8. **Deployment** — AWS, described in Terraform, deployed from GitHub Actions ([ADR 0026](adr/0026-aws-deployment.md)), with a demo reset.

### Later phases
Everything else is scheduled in [`PLAN.md`](PLAN.md): the full inbound/outbound workflow (Phase 2), events and integration (3), agent depth and evals (4), configuration and Cognito (5), simulators and the writeup (6).

### Explicitly out of the product, and said so in the README
Labor standards, billing, multi-warehouse, lots/serials, cartonization, carrier rating, returns.

---

## 3. Work plan — eight commits

Each is one commit, with tests, docs (ADR when a decision is made), and a short review summary. Acceptance criteria are what "done" means.

| # | Commit | Contents | Done when |
|---|---|---|---|
| 1 ✅ | `feat(waves): plan waves with allocation and replenishment` | V4 migration (`wave`, `wave_order`, `allocation`, `task`), pure allocation planner, `POST /api/v1/waves/plan` (with `preview=true`), `POST /waves/{n}/release`, `POST /waves/{n}/cancel`, `GET /waves`, `GET /waves/{n}` | A wave plans against seeded stock: pick tasks for available stock, replenishment tasks where forward-pick is short, picks marked `WAITING` on their replenishment; a concurrent-planning test shows no double allocation |
| 2 ✅ | `feat(tasks): worker assignment and task execution` | V5 (`worker`, `worker_equipment`), `POST /workers/{code}/next-task` (`SKIP LOCKED`), `POST /tasks/{id}/start` / `/complete` / `/reassign`, replenishment completion releases dependent picks | 50 concurrent claims never hand out the same task; completing a replenishment moves its picks to `READY` |
| 3 ✅ | `feat(waves): diagnose blocked waves` | `GET /waves/{n}/diagnosis` — counts by task state, blockers with root cause (`WAITING_ON_REPLENISHMENT`, `NO_ELIGIBLE_WORKER_AVAILABLE`, `SHORT_ALLOCATED`), affected orders and SKUs | The demo scenario returns the blocker with the reach-truck reason and the reserve quantity available |
| 4 ✅ | `feat(web): orders, waves, and tasks pages` | Three pages in the existing shell, wave detail showing the diagnosis, a Plan Wave action | A blocked wave is visible and explained in the browser |
| 5 ✅ | `feat(agent): investigate and propose fixes` | `ops-agent` (FastAPI + Anthropic SDK), read tools over the public API, grounded answer with evidence, `POST /proposals` in core, approve/reject, execution through the same commands; Haiku default, token and daily budget caps, passcode-protected | "Why is wave N blocked?" returns an explanation citing tool results plus a proposal; approving it reassigns the task, and the blocker clears |
| 6 ✅ | `feat(devdata): seed the demo scenario` | Orders on the seeded warehouse, one wave planned into the blocked state, workers where only one has `REACH_TRUCK` and is busy | A fresh database reaches the demo state automatically under the `dev` profile |
| 7 | `feat(auth): roles for the public, the agent, and supervisors` | Spring Security: anonymous read-only, `AGENT` role from a service token (read + create proposals), `SUPERVISOR` role from the demo passcode (all commands, approve/reject); `CurrentActor` from the credential instead of `X-Agent-Id`; proposal checks at creation (task exists, is open and belongs to the wave; worker exists and is certified); console passcode prompt; agent sends its token; a live run of the agent against the real model on the demo wave | The agent's token gets 403 on `/approve`; anonymous writes get 401; an invalid proposal is rejected at creation; a real Claude investigation of the demo wave proposes giving the replenishments to W-014 |
| 8 | `feat(deploy): containerize and deploy on AWS` | Dockerfiles for `wms-core` and `ops-agent`; Terraform for containers, RDS for MySQL, S3 + CloudFront for the console, secrets, CORS; GitHub Actions deploy via OIDC; the agent's daily budget moved to the database; seeding safe with more than one instance; a demo reset; README with the live URL and an architecture diagram that marks what is built | The public URL shows the console and the blocked demo wave; a supervisor can run the agent, approve its proposal and see the wave unblock; a reset restores the scenario |

---

## 4. Deployment (commit 8)

### Topology
```
CloudFront + S3 (console) ──/api/*──► wms-core (container) ──► RDS for MySQL 8
                          ──/agent/*──► ops-agent (container) ──► Claude API
                                             └── reads/proposes via wms-core, with its service token
```
Compute (ECS Fargate or App Runner) and instance sizes are chosen against current AWS pricing at the start of the commit.

### Configuration
| Service | Variable | Notes |
|---|---|---|
| wms-core | `MYSQL_URL`, `MYSQL_USER`, `MYSQL_PASSWORD` | From RDS, via AWS-managed secrets |
| wms-core | `SPRING_PROFILES_ACTIVE=dev` | Seeds the demo warehouse on first boot |
| wms-core | `DEMO_PASSCODE`, `AGENT_TOKEN` | The supervisor credential and the agent's service token |
| ops-agent | `ANTHROPIC_API_KEY` | **Entered by Dhanush into AWS.** Never committed, never pasted into chat, never read by Claude |
| ops-agent | `WMS_API_URL`, `AGENT_TOKEN`, `DEMO_PASSCODE` | |
| ops-agent | `AGENT_MODEL`, `AGENT_DAILY_BUDGET_USD`, `AGENT_MAX_TOOL_CALLS` | Cost caps |

### Who does what
- **Claude** writes the Dockerfiles, Terraform and workflow, runs `terraform` and the `aws` CLI, deploys, and smoke-tests the live URL.
- **Dhanush** signs in to the AWS CLI (`aws configure sso` or `aws login`, which opens a browser), approves the first `terraform apply`, and enters the Anthropic key into AWS. Claude never handles the key.

### Cost
Depends on the account's free tier (see the Free Tier page in the AWS billing console). Without one, the smallest RDS instance plus two small containers is roughly $20–30/month. The agent costs a few cents per investigation on Haiku 4.5, capped by a daily budget.

---

## 5. Streamlined process

| Rule | |
|---|---|
| Commit size | **One commit per feature** (the eight above), not per sub-step |
| Tests | Run the suite once per commit; report only failures. Mutation checks for concurrency-critical and security-critical code |
| Browser checks | Once per UI commit, and once after deploy — not per change |
| Docs | ADR when a real decision is made; one line in the index. DESIGN.md updated when the implementation diverges |
| Summaries | **5 lines**: what changed, what was verified, what's next |
| Pushing | **Always ask before pushing.** Commit locally, summarize, wait for "push" |
| Deferred work | Anything cut goes into [`PLAN.md`](PLAN.md), not into the commit |

---

## 6. Resuming in a new conversation

Say: *"Continue the WMS — read docs/MVP_PLAN.md."* Then:

1. Read this file for Phase 1 scope and the commit list, and [`PLAN.md`](PLAN.md) for the phases after it.
2. Read [`docs/adr/README.md`](adr/README.md) for decisions already made; don't relitigate them.
3. `git log --oneline -15` to see where the work stopped.
4. Start the next unchecked commit in §3.

**Local setup reminders**
- JDK 21 is at `C:\Users\dhanu\.jdk\jdk-21.0.12.1+1`; `JAVA_HOME` still points at 17, so set it per command.
- MySQL runs on **port 3307** (the local MySQL service owns 3306).
- Start infrastructure: `docker compose -f deploy/compose/docker-compose.yml up -d --wait`
- Run the backend with demo data: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`. The local `wms` schema holds leftovers from manual testing; for a clean demo, point `MYSQL_URL` at a fresh schema (e.g. `jdbc:mysql://localhost:3307/wms_demo`).
- Tests need Docker running (Testcontainers).
- The agent: `cd ops-agent`, `.venv/Scripts/python -m uvicorn ops_agent.api:app --app-dir src --port 8000`; the key lives in `ops-agent/.env` (git-ignored), which Claude does not open.
