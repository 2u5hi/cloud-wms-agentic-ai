# 0028. The agent reaches Claude with workload identity, not an API key

**Status:** accepted

## Context
The agent needs a credential for the Claude API. The obvious one is an `sk-ant-…` API key: long-lived, stored
in an environment file locally and in a secret store when deployed, and valid until someone remembers to
rotate it. It would be the one secret in the system that, if leaked, spends money on its own.

Anthropic's Workload Identity Federation (generally available June 2026) lets a workload present a
short-lived token signed by an identity provider it already has, and exchanges it for an Anthropic token that
expires in minutes. On AWS, the provider is STS: any process with an IAM role can call
`sts:GetWebIdentityToken` and get a JWT that names that role.

## Decision
The agent authenticates as an AWS role, never with a key:

```
IAM role ──sts:GetWebIdentityToken──► AWS-signed JWT (sub = role ARN, aud = https://api.anthropic.com)
    └──► Anthropic checks it against a federation rule ──► short-lived token (10 min) ──► Claude API
```

[`credentials.py`](../../ops-agent/src/ops_agent/credentials.py) builds the client. The SDK calls the token
function on every exchange, because the tokens are single-use:

```python
def fetch() -> str:
    response = sts.get_web_identity_token(
        Audience=[AUDIENCE], SigningAlgorithm="RS256", DurationSeconds=900
    )
    return response["WebIdentityToken"]
```

| Piece | Where | Value |
|---|---|---|
| Account setting | AWS IAM | Outbound web identity federation on; issuer `https://<id>.tokens.sts.global.api.aws` |
| Local role | AWS IAM | `cloud-wms-ops-agent-local`: assumable only by the `wmsai-dev` user, allowed only `sts:GetWebIdentityToken` |
| Deployed role | AWS IAM (Phase 1, commit 8) | the ops-agent task role, with its own rule |
| Issuer | Claude Console | the account's STS issuer, OIDC discovery |
| Service account | Claude Console | `ops-agent`, the lowest role that can call the API; not admin |
| Rule | Claude Console | exact role ARN (no `*`), audience `https://api.anthropic.com`, `workspace:developer`, 10 minutes |

The rule's IDs and the organization ID go in the agent's environment; none of them are secrets.

**Federation wins explicitly.** The SDK's own credential lookup puts `ANTHROPIC_API_KEY` above federation, so a
stray key would silently take over. When federation is configured, the agent builds the client itself and
ignores (and logs) any key it finds. An API key remains a local fallback only.

## Consequences
- There is no Anthropic secret to store, rotate or leak — locally or in AWS. Revoking access is deleting the
  rule; narrowing it is editing the role.
- Every Claude call is attributable to an IAM role and a service account in the Claude Console's
  authentication history.
- Local runs need an AWS login that can assume the local role; the console still works without the agent.
- API usage bills against the organization's API credits, which are separate from Claude app usage credits.
  The first live run found that out the hard way.
- The same pattern could replace `AGENT_TOKEN` between the agent and wms-core (wms-core validating the agent's
  AWS-signed JWT). That belongs with Cognito in Phase 5.
