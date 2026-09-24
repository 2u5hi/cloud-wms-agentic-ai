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
| `budget.tf` | Email alerts at 50/80/100% of the monthly budget, and on a forecast over it |

Code ships on every push to main through [`.github/workflows/deploy.yml`](../../.github/workflows/deploy.yml),
after all tests pass. Infrastructure changes are a deliberate `terraform apply` from here.

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

`terraform.tfvars` (not committed) holds `budget_alert_email` and `monthly_budget_usd`.
