# ops-agent

Explains why a wave is blocked and proposes a fix a supervisor approves.

The WMS does the diagnosis ([ADR 0022](../docs/adr/0022-deterministic-wave-diagnosis.md)); this service
explains it, looks up what the diagnosis leaves open, and writes a proposal. It reads through the public
WMS API and has no write path of its own ([ADR 0024](../docs/adr/0024-agent-proposes-wms-executes.md)).

```
POST /investigate  {"wave": 7}  ->  {answer, evidence[], proposals[], toolCalls[], usage}
GET  /health                    ->  model, whether a key is configured, spend today
```

## Run it

```bash
python -m venv .venv && .venv/Scripts/python -m pip install -e ".[dev]"
AWS_PROFILE=wms-ops-agent .venv/Scripts/python -m uvicorn ops_agent.api:app --app-dir src --port 8000
```

It reaches Claude with **workload identity federation**, not an API key
([ADR 0028](../docs/adr/0028-claude-via-workload-identity.md)): its AWS role gets a signed token from STS and
Anthropic exchanges it for a short-lived one. Locally that role is `cloud-wms-ops-agent-local`, assumed through
the `wms-ops-agent` AWS profile. Without Claude credentials the service still answers `/health` and returns
503 from `/investigate`; the console keeps working and shows the WMS diagnosis on its own.

## Configuration

| Variable | Default | |
|---|---|---|
| `ANTHROPIC_FEDERATION_RULE_ID`, `ANTHROPIC_ORGANIZATION_ID`, `ANTHROPIC_SERVICE_ACCOUNT_ID` | — | Workload identity federation; IDs, not secrets |
| `AWS_PROFILE`, `AWS_REGION` | —, `us-east-1` | The AWS role the agent runs as |
| `ANTHROPIC_API_KEY` | — | Local fallback only; ignored when federation is configured |
| `WMS_API_URL` | `http://localhost:8080` | |
| `AGENT_MODEL` | `claude-haiku-4-5-20251001` | |
| `AGENT_MAX_TOOL_CALLS` | 6 | Per investigation |
| `AGENT_DAILY_BUDGET_USD` | 2.0 | Refuses further runs once spent |
| `AGENT_TOKEN` | `dev-agent-token` | The agent's own credential for wms-core: read and propose only ([ADR 0027](../docs/adr/0027-roles-for-public-agent-supervisor.md)) |
| `DEMO_PASSCODE` | — | The supervisor passcode; when set, `/investigate` requires it as `Authorization: Bearer …` |

## Tests

`.venv/Scripts/python -m pytest` — the model and the WMS are both stubbed, so no key and no running
warehouse are needed.
