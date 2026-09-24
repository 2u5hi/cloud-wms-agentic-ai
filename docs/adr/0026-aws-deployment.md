# 0026. Deploy on AWS, described in Terraform

**Status:** accepted. Supersedes the hosting choice in [ADR 0019](0019-mvp-first-deployment.md) (Railway +
Vercel, with Google Cloud as the later target).

## Context
ADR 0019 picked Railway for speed and kept Google Cloud as the target because it mirrors Manhattan's
published platform. Two things changed that:

- **The claim has to be exact.** "Cloud-based" is true on any of these, but the project's description has to
  match where it actually runs. Whichever platform is chosen, the README names it and nothing more.
- **Familiarity and cost.** Dhanush already works with AWS, so deploying, debugging and explaining it is
  faster, and there is an existing account to use. Google Cloud would mean learning a platform and a billing
  setup at the same time as shipping.

What this gives up: Cloud SQL, Pub/Sub and GKE as named matches for Manhattan's stack. The application keeps
Manhattan's technology families (Java/Spring, MySQL, REST, containers, event-driven messaging), which is where
the transferable skill is; the hosting underneath is AWS.

## Decision

| Concern | AWS | Replaces (from DESIGN.md) |
|---|---|---|
| `wms-core`, `ops-agent` | Lambda functions running the services' container images ([ADR 0029](0029-serverless-on-lambda.md); App Runner closed to new customers) | GKE / Cloud Run |
| Database | RDS for MySQL 8 | Cloud SQL for MySQL |
| Console | S3 + CloudFront | Vercel |
| Secrets | AWS-managed secrets for the demo passcode and agent token. No Anthropic key: the agent uses workload identity federation ([ADR 0028](0028-claude-via-workload-identity.md)) | Railway variables |
| Messaging (Phase 3) | AWS messaging with per-aggregate ordering and dead-letter queues, LocalStack locally; the exact service is decided in Phase 3 | Pub/Sub + emulator |
| Identity (Phase 5) | Cognito, with custom scopes for the agent's client-credentials access | Keycloak |
| Infrastructure | **Terraform**, applied by GitHub Actions using OIDC (no long-lived AWS keys in the repo) | Kustomize on kind/GKE |
| Kubernetes | Not used. EKS's control-plane cost buys nothing at this size; ECS is the orchestrator | kind → GKE |

Terraform over CDK: it is provider-neutral, so moving the same system to another cloud later is a
rewrite of the modules, not of the tooling, and it is what most client environments already use.

## Consequences
- `ops-agent` calls the Anthropic API directly, authenticated by its AWS role through workload identity
  federation, which gives it IAM-based access without moving to Bedrock. Region: us-east-1.
- Cost: the AWS account's free tier depends on when it was created; without it, the smallest managed MySQL
  plus two small containers is roughly $20–30/month. Sizes are chosen against current prices in the deploy
  commit, and the agent's daily budget caps model spend separately.
- DESIGN.md's platform tables now describe AWS; its Manhattan comparison says plainly which parts match and
  which do not.
