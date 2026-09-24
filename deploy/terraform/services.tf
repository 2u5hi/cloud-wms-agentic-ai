# wms-core and ops-agent as Lambda functions running their normal container images through the Lambda Web
# Adapter (ADR 0029). Each has a function URL; CloudFront routes to them so the console sees one origin.

resource "aws_ecr_repository" "service" {
  for_each             = toset(["wms-core", "ops-agent"])
  name                 = "${local.name}/${each.key}"
  image_tag_mutability = "MUTABLE"
  force_delete         = true

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "service" {
  for_each   = aws_ecr_repository.service
  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 10 images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 10 }
      action       = { type = "expire" }
    }]
  })
}

# The two credentials of ADR 0027, generated here so no human ever types or pastes them. Read the passcode with
# `terraform output -raw supervisor_passcode`.
resource "random_password" "supervisor_passcode" {
  length  = 20
  special = false
}

resource "random_password" "agent_token" {
  length  = 40
  special = false
}

data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

# --- wms-core ---------------------------------------------------------------------------------------------------

resource "aws_iam_role" "wms_core" {
  name               = "${local.name}-wms-core"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

resource "aws_iam_role_policy_attachment" "wms_core_vpc" {
  role       = aws_iam_role.wms_core.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

resource "aws_lambda_function" "wms_core" {
  function_name = "${local.name}-wms-core"
  role          = aws_iam_role.wms_core.arn
  package_type  = "Image"
  image_uri     = "${aws_ecr_repository.service["wms-core"].repository_url}:${var.wms_core_image_tag}"
  architectures = ["x86_64"]
  memory_size   = 2048 # more memory is also more CPU, which is most of a JVM cold start
  timeout       = 60   # a demo reset rebuilds the whole warehouse

  vpc_config {
    subnet_ids         = data.aws_subnets.default.ids
    security_group_ids = [aws_security_group.wms_core.id]
  }

  environment {
    variables = {
      SPRING_PROFILES_ACTIVE = "demo"
      MYSQL_URL              = "jdbc:mysql://${aws_db_instance.wms.address}:3306/wms"
      MYSQL_USER             = aws_db_instance.wms.username
      MYSQL_PASSWORD         = random_password.database.result
      DEMO_PASSCODE          = random_password.supervisor_passcode.result
      AGENT_TOKEN            = random_password.agent_token.result
    }
  }

  # Which image runs is the deploy workflow's job (.github/workflows/deploy.yml); Terraform only sets the first
  # one, so an apply never rolls the code back.
  lifecycle {
    ignore_changes = [image_uri]
  }

  depends_on = [aws_iam_role_policy_attachment.wms_core_vpc]
}

resource "aws_lambda_function_url" "wms_core" {
  function_name      = aws_lambda_function.wms_core.function_name
  authorization_type = "NONE" # the application authenticates every command itself (ADR 0027)
}

# --- ops-agent --------------------------------------------------------------------------------------------------

# Its name is what the Claude Console rule matches (ADR 0028): arn:aws:iam::<account>:role/cloud-wms-ops-agent.
resource "aws_iam_role" "ops_agent" {
  name               = "${local.name}-ops-agent"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

resource "aws_iam_role_policy_attachment" "ops_agent_logs" {
  role       = aws_iam_role.ops_agent.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy" "ops_agent_identity_token" {
  name = "get-web-identity-token"
  role = aws_iam_role.ops_agent.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = "sts:GetWebIdentityToken", Resource = "*" }]
  })
}

resource "aws_lambda_function" "ops_agent" {
  function_name = "${local.name}-ops-agent"
  role          = aws_iam_role.ops_agent.arn
  package_type  = "Image"
  image_uri     = "${aws_ecr_repository.service["ops-agent"].repository_url}:${var.ops_agent_image_tag}"
  architectures = ["x86_64"]
  memory_size   = 512
  timeout       = 60

  # Not in the VPC: it needs the internet (Claude, STS) and reaches wms-core through its public URL, like any
  # other client. It has no database access at all (ADR 0024).
  environment {
    variables = {
      WMS_API_URL                  = aws_lambda_function_url.wms_core.function_url
      AGENT_TOKEN                  = random_password.agent_token.result
      DEMO_PASSCODE                = random_password.supervisor_passcode.result
      AGENT_DAILY_BUDGET_USD       = tostring(var.agent_daily_budget_usd)
      ANTHROPIC_ORGANIZATION_ID    = var.anthropic_organization_id
      ANTHROPIC_SERVICE_ACCOUNT_ID = var.anthropic_service_account_id
      ANTHROPIC_FEDERATION_RULE_ID = var.anthropic_federation_rule_id
    }
  }

  # Which image runs is the deploy workflow's job (.github/workflows/deploy.yml); Terraform only sets the first
  # one, so an apply never rolls the code back.
  lifecycle {
    ignore_changes = [image_uri]
  }

  depends_on = [aws_iam_role_policy_attachment.ops_agent_logs]
}

resource "aws_lambda_function_url" "ops_agent" {
  function_name      = aws_lambda_function.ops_agent.function_name
  authorization_type = "NONE" # /investigate checks the supervisor passcode itself
}

resource "aws_cloudwatch_log_group" "service" {
  for_each          = toset(["wms-core", "ops-agent"])
  name              = "/aws/lambda/${local.name}-${each.key}"
  retention_in_days = 14
}
