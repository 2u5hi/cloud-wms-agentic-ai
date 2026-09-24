# Keeps one instance of each service warm, so whoever opens the demo link doesn't wait on a JVM cold start
# (ADR 0029). A scheduled invoke every 5 minutes: well inside EventBridge Scheduler's and Lambda's free tiers.
# The Web Adapter hands non-HTTP events to the app as a POST it refuses; the point is only that an instance ran.

resource "aws_iam_role" "warmer" {
  name = "${local.name}-warmer"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "scheduler.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = { StringEquals = { "aws:SourceAccount" = local.account_id } }
    }]
  })
}

resource "aws_iam_role_policy" "warmer" {
  name = "invoke-services"
  role = aws_iam_role.warmer.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "lambda:InvokeFunction"
      Resource = [aws_lambda_function.wms_core.arn, aws_lambda_function.ops_agent.arn]
    }]
  })
}

resource "aws_scheduler_schedule" "warm" {
  for_each = {
    wms-core  = aws_lambda_function.wms_core.arn
    ops-agent = aws_lambda_function.ops_agent.arn
  }
  name                = "${local.name}-warm-${each.key}"
  schedule_expression = "rate(5 minutes)"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = each.value
    role_arn = aws_iam_role.warmer.arn
    input    = jsonencode({ warm = true })

    retry_policy {
      maximum_retry_attempts = 0
    }
  }
}
