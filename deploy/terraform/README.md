# Deployment

The demo on AWS us-east-1 ([ADR 0029](../../docs/adr/0029-serverless-on-lambda.md)): wms-core and ops-agent as
Lambda functions running their container images, RDS for MySQL 8.4, the console on S3, all behind one
CloudFront distribution.

| File | What |
|---|---|
| `main.tf` | Providers and the S3 state backend (versioned, encrypted, private) |
| `database.tf` | RDS, its security groups, parameter group |
| `services.tf` | ECR, the two functions and their roles, generated credentials |
| `web.tf` | Console bucket, CloudFront routing (`/api`, `/demo`, `/actuator` → wms-core, `/agent` → ops-agent) |
| `github.tf` | OIDC role that lets `deploy.yml` ship code, and nothing else |
| `warm.tf` | A ping every 5 minutes so the first visitor doesn't wait on a cold start |
| `budget.tf` | Email alerts at 50/80/100% of the monthly budget and on a forecast; at 100% AWS stops the database itself |

Code ships on every push to main through [`.github/workflows/deploy.yml`](../../.github/workflows/deploy.yml),
after all tests pass, as a deployment to the `demo` GitHub environment (the repository sidebar links to it).
Infrastructure changes are a deliberate `terraform apply` from here.

The URL is CloudFront's generated one. A readable one needs a domain (~$10–12/year); the certificate (ACM) and an
alias on the distribution would go in `web.tf`.

## Operating the demo

Commands assume `AWS_PROFILE=wmscb-dev` (PowerShell: `$env:AWS_PROFILE='wmscb-dev'`).

```bash
terraform output -raw supervisor_passcode   # sign in on the console with this
terraform output console_url
```

**Reset** the demo (rebuilds the warehouse and the blocked wave): sign in, then **Reset demo** in the header.

**Pause** the database between demos to spend ~$2/month instead of ~$14 (AWS restarts a stopped instance after
7 days on its own). Start it a few minutes before sharing the link:

```bash
aws rds stop-db-instance --db-instance-identifier cloud-wms
aws rds start-db-instance --db-instance-identifier cloud-wms
```

## What can cost money, and what stops it

| | Normal | Worst case, and the stop |
|---|---|---|
| RDS | ~$14/month, fixed | At 100% of the monthly budget, AWS stops the instance automatically (`budget.tf`) |
| Lambda, CloudFront, S3, scheduler | $0, inside the always-free allowances | Sustained abuse is capped by the account's 10 concurrent Lambdas (~$1.20/hour); alerts email you, and the kill switch below stops it |
| Claude API | ~$0.008 per investigation, supervisors only | API credits are prepaid; with auto-reload off in the Claude Console, the loaded balance is the most it can spend |
| ECR | ~$0.20/month | Last 3 images per service |

**Kill switch** — stops both services from running at all (requests get 429, "throttled") until you undo it. Tested:

```bash
aws lambda put-function-concurrency --function-name cloud-wms-wms-core --reserved-concurrent-executions 0
aws lambda put-function-concurrency --function-name cloud-wms-ops-agent --reserved-concurrent-executions 0
# undo:
aws lambda delete-function-concurrency --function-name cloud-wms-wms-core
aws lambda delete-function-concurrency --function-name cloud-wms-ops-agent
```

`terraform.tfvars` (not committed) holds `budget_alert_email` and `monthly_budget_usd`.
