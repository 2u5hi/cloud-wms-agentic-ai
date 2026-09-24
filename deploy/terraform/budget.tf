# A tripwire on spend. Counts cost before credits, so it tracks how fast the account's credits are being used,
# not the $0 the card sees while they last. The first two AWS budgets are free.

variable "budget_alert_email" {
  description = "Where budget alerts go; empty means no budget (set it in terraform.tfvars, which is not committed)"
  type        = string
  default     = ""
}

variable "monthly_budget_usd" {
  type    = number
  default = 20
}

resource "aws_budgets_budget" "monthly" {
  count        = var.budget_alert_email == "" ? 0 : 1
  name         = "${local.name}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  cost_types {
    include_credit = false
    include_refund = false
  }

  dynamic "notification" {
    for_each = [50, 80, 100]
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value
      threshold_type             = "PERCENTAGE"
      notification_type          = "ACTUAL"
      subscriber_email_addresses = [var.budget_alert_email]
    }
  }

  # A forecast over the limit is the early warning: it fires before the money is spent.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.budget_alert_email]
  }
}

# The hard stop: when the month's actual cost reaches the budget, AWS stops the database on its own. That is
# the only always-on cost; a stopped instance bills storage only, and the demo is down until someone starts it
# again (deploy/terraform/README.md).
resource "aws_iam_role" "budget_action" {
  count = var.budget_alert_email == "" ? 0 : 1
  name  = "${local.name}-budget-action"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "budgets.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = { StringEquals = { "aws:SourceAccount" = local.account_id } }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "budget_action" {
  count      = var.budget_alert_email == "" ? 0 : 1
  role       = aws_iam_role.budget_action[0].name
  policy_arn = "arn:aws:iam::aws:policy/AWSBudgetsActions_RolePolicyForResourceAdministrationWithSSM"
}

resource "aws_budgets_budget_action" "stop_database" {
  count              = var.budget_alert_email == "" ? 0 : 1
  budget_name        = aws_budgets_budget.monthly[0].name
  action_type        = "RUN_SSM_DOCUMENTS"
  approval_model     = "AUTOMATIC"
  notification_type  = "ACTUAL"
  execution_role_arn = aws_iam_role.budget_action[0].arn

  action_threshold {
    action_threshold_type  = "PERCENTAGE"
    action_threshold_value = 100
  }

  definition {
    ssm_action_definition {
      action_sub_type = "STOP_RDS_INSTANCES"
      region          = var.region
      instance_ids    = [aws_db_instance.wms.identifier]
    }
  }

  subscriber {
    address           = var.budget_alert_email
    subscription_type = "EMAIL"
  }

  depends_on = [aws_iam_role_policy_attachment.budget_action]
}
