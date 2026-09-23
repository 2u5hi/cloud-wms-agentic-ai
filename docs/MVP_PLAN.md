# MVP plan: get a deployed demo, then deepen

**Goal:** a publicly reachable demo that runs the whole story end to end — orders arrive, a wave is planned, it blocks, the agent explains why and proposes a fix, a supervisor approves, the fix executes. Everything else waits.

This plan supersedes the milestone order in [DESIGN.md §10](DESIGN.md) until the MVP is live. Nothing already built is thrown away; the deferred items come back in the deepening phase.

---

## 1. Where the project stands

Done and on `main`:

| Area | State |
|---|---|
| Foundation | Docker Compose (MySQL 8.4, Pub/Sub emulator), CI for `wms-core` and `web`, problem+json errors, OpenAPI contract with drift check, generated TypeScript client |
| Inventory | Schema with database-enforced invariants, append-only ledger, pure domain, property tests, ordered row locking, master data / balance / availability / command / ledger APIs, idempotency keys, dev seed (1,224 locations, 300 SKUs), inventory page |
| Orders | Order model (holds as a flag), host import with per-order results, order reads |
| Waves and tasks | Planning with allocation and replenishment, worker claims and task execution, deterministic wave diagnosis (MVP commits 1-3) |
| Platform | READ COMMITTED isolation, retryable 503 on lock conflicts, UTC timestamps end to end |
| Docs | 22 ADRs in [`docs/adr/`](adr/README.md), design doc in sync |
| Tests | 165 backend, 12 web |

Remaining for the MVP: three console pages, the agent, the demo scenario, and deployment (commits 4-7 below).

---

## 2. MVP scope

### In
1. **Wave planning** — select orders, allocate stock, create replenishment tasks when forward-pick is short, create pick tasks that depend on them. Rules are **code defaults**, not editable configuration.
2. **Light execution** — workers with equipment certifications; claim, complete, and reassign tasks.
3. **Wave diagnosis** — a deterministic endpoint that answers "why is this wave blocked?" with structured blockers.
4. **Console** — Orders, Waves (with diagnosis and the agent panel), Tasks; Inventory already exists.
5. **Agent** — Python service, Claude Haiku by default: read tools, an explanation with evidence, a proposed fix, human approval, execution through the same API.
6. **Demo scenario** — the dev seed also creates orders and a wave that blocks because the only reach-truck-certified worker is busy.
7. **Deployment** — `wms-core` + MySQL + `ops-agent` on Railway, `web` on Vercel, with a demo passcode on write endpoints.

### Deferred to the deepening phase
Pub/Sub and the transactional outbox (events stay in-process), floor/host/sorter simulators, Keycloak and OAuth scopes, editable configuration, the MHE/sortation integration, agent eval suite, dry-run proposal impact, Kubernetes, BigQuery, order streaming.

### Explicitly out of the MVP demo, and said so in the README
Labour standards, billing, multi-warehouse, lots/serials, cartonization, carrier rating, returns, receiving/putaway.

---

## 3. Work plan — seven commits

Each is one commit, with tests, docs (ADR when a decision is made), and a short review summary. Acceptance criteria are what "done" means.

| # | Commit | Contents | Done when |
|---|---|---|---|
| 1 ✅ | `feat(waves): plan waves with allocation and replenishment` | V4 migration (`wave`, `wave_order`, `allocation`, `task`), pure allocation planner, `POST /api/v1/waves/plan` (with `preview=true`), `POST /waves/{n}/release`, `POST /waves/{n}/cancel`, `GET /waves`, `GET /waves/{n}` | A wave plans against seeded stock: pick tasks for available stock, replenishment tasks where forward-pick is short, picks marked `WAITING` on their replenishment; a concurrent-planning test shows no double allocation |
| 2 ✅ | `feat(tasks): worker assignment and task execution` | V5 (`worker`, `worker_equipment`), `POST /workers/{code}/next-task` (`SKIP LOCKED`), `POST /tasks/{id}/start` / `/complete` / `/reassign`, replenishment completion releases dependent picks | 50 concurrent claims never hand out the same task; completing a replenishment moves its picks to `READY` |
| 3 ✅ | `feat(waves): diagnose blocked waves` | `GET /waves/{n}/diagnosis` — counts by task state, blockers with root cause (`WAITING_ON_REPLENISHMENT`, `NO_ELIGIBLE_WORKER_AVAILABLE`, `SHORT_ALLOCATED`), affected orders and SKUs | The demo scenario returns the blocker with the reach-truck reason and the reserve quantity available |
| 4 | `feat(web): orders, waves, and tasks pages` | Three pages in the existing shell, wave detail showing the diagnosis, a Plan Wave action | A blocked wave is visible and explained in the browser |
| 5 | `feat(agent): investigate and propose fixes` | `ops-agent` (FastAPI + Anthropic SDK), read tools over the public API, grounded answer with evidence, `POST /proposals` in core, approve/reject, execution through the same commands; Haiku default, token and daily budget caps, passcode-protected | "Why is wave N blocked?" returns an explanation citing tool results plus a proposal; approving it creates the replenishment or reassigns the task, and the wave unblocks |
| 6 | `feat(devdata): seed the demo scenario` | Orders on the seeded warehouse, one wave planned into the blocked state, workers where only one has `REACH_TRUCK` and is busy | A fresh database reaches the demo state automatically under the `dev` profile |
| 7 | `feat(deploy): containerize and deploy` | Dockerfiles for `wms-core` and `ops-agent`, Railway config, Vercel config and API rewrites, CORS, demo passcode, README with the live URL | The public URL shows the console, the demo scenario, and a working agent investigation |

**Rough size:** 2–3 working sessions.

---

## 4. Deployment

### Topology
```
Vercel (web, static)  ──/api/*──►  Railway: wms-core (Spring Boot, Docker)
                                        │
                                        ├── Railway: MySQL 8
                                        └──◄── Railway: ops-agent (FastAPI) ── Claude API
```

### Environment variables
| Service | Variable | Notes |
|---|---|---|
| wms-core | `MYSQL_URL`, `MYSQL_USER`, `MYSQL_PASSWORD` | From Railway's MySQL plugin |
| wms-core | `SPRING_PROFILES_ACTIVE=dev` | Seeds the demo warehouse on first boot |
| wms-core | `DEMO_PASSCODE` | Required header on write endpoints |
| ops-agent | `ANTHROPIC_API_KEY` | **Set by Dhanush in the Railway dashboard.** Never committed, never pasted into chat |
| ops-agent | `WMS_API_URL`, `DEMO_PASSCODE` | |
| ops-agent | `AGENT_MODEL=claude-haiku-4-5`, `AGENT_DAILY_BUDGET_USD` | Cost caps |
| web | `VITE_API_BASE` | Or a Vercel rewrite to the Railway URL |

### Who does what
- **Claude runs the CLIs** (`railway`, `vercel`), writes the Dockerfiles and config, deploys, and smoke-tests the live URL.
- **Dhanush does the browser logins** (`railway login`, `vercel login` open a browser) and sets `ANTHROPIC_API_KEY` in the Railway dashboard. Claude never handles the key.

### Cost
Railway hobby is about $5/month. The agent runs about 2–3 cents per investigation on Haiku 4.5 ($1/$5 per million input/output tokens), so a demo session costs a few cents. Caps: per-run token limit, daily budget, passcode.

---

## 5. Streamlined process

| Rule | |
|---|---|
| Commit size | **One commit per feature** (the seven above), not per sub-step |
| Tests | Run the suite once per commit; report only failures. Mutation checks only for concurrency-critical code |
| Browser checks | Once per UI commit, and once after deploy — not per change |
| Docs | ADR only when a real decision is made; one line in the index. DESIGN.md updated when the implementation diverges |
| Summaries | **5 lines**: what changed, what was verified, what's next |
| Pushing | **Always ask before pushing.** Commit locally, summarize, wait for "push" |
| Deferred work | Anything cut goes in §2 of this file, not into the commit |

---

## 6. Resuming in a new conversation

Say: *"Continue the WMS MVP — read docs/MVP_PLAN.md."* Then:

1. Read [`docs/MVP_PLAN.md`](MVP_PLAN.md) (this file) for scope and the commit list.
2. Read [`docs/adr/README.md`](adr/README.md) for decisions already made; don't relitigate them.
3. `git log --oneline -15` to see where the work stopped.
4. Start the next unchecked commit in §3.

**Local setup reminders**
- JDK 21 is at `C:\Users\dhanu\.jdk\jdk-21.0.12.1+1`; `JAVA_HOME` still points at 17, so set it per command.
- MySQL runs on **port 3307** (the local MySQL service owns 3306).
- Start infrastructure: `docker compose -f deploy/compose/docker-compose.yml up -d --wait`
- Run the backend with demo data: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
- Tests need Docker running (Testcontainers).

---

## 7. After the MVP — the deepening phase

In rough order of value for the portfolio story:
1. Transactional outbox → Pub/Sub, rules engine, alerts, live updates (the event-driven architecture story)
2. Floor and host simulators, so the dashboard shows a warehouse actually running
3. Agent eval suite graded by the WMS itself, with a cost/latency table per model
4. Dry-run proposals: predicted impact computed by the real domain logic in a rolled-back transaction
5. Keycloak with OAuth scopes; the agent becomes a least-privilege API client
6. Editable configuration (wave templates, allocation strategies) in the admin UI
7. MHE/sortation integration with an anti-corruption layer
8. Kubernetes manifests on kind, and optionally GKE on trial credit
9. The technical writeup, the consultant-style solution design document, and the demo video
