# ops-agent

Explains why a wave is blocked and proposes a fix a supervisor approves.

The WMS does the diagnosis ([ADR 0022](../docs/adr/0022-deterministic-wave-diagnosis.md)); this service
explains it, looks up what the diagnosis leaves open, and writes a proposal. It reads through the public
WMS API and has no write path of its own ([ADR 0024](../docs/adr/0024-agent-proposes-wms-executes.md)).

```
POST /investigate  {"wave": 7}  ->  {answer, evidence[], proposal, toolCalls[], usage}
GET  /health                    ->  model, whether a key is configured, spend today
```

## Run it

```bash
python -m venv .venv && .venv/Scripts/python -m pip install -e ".[dev]"
ANTHROPIC_API_KEY=... .venv/Scripts/python -m uvicorn ops_agent.api:app --app-dir src --port 8000
```

Without a key the service still answers `/health` and returns 503 from `/investigate`; the console keeps
working and shows the WMS diagnosis on its own.

## Configuration

| Variable | Default | |
|---|---|---|
| `ANTHROPIC_API_KEY` | — | Set in the deployment dashboard; never committed |
| `WMS_API_URL` | `http://localhost:8080` | |
| `AGENT_MODEL` | `claude-haiku-4-5-20251001` | |
| `AGENT_MAX_TOOL_CALLS` | 6 | Per investigation |
| `AGENT_DAILY_BUDGET_USD` | 2.0 | Refuses further runs once spent |
| `DEMO_PASSCODE` | — | Required header when set |

## Tests

`.venv/Scripts/python -m pytest` — the model and the WMS are both stubbed, so no key and no running
warehouse are needed.
