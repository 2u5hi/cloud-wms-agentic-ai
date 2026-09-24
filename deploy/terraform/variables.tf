variable "region" {
  type    = string
  default = "us-east-1"
}

variable "wms_core_image_tag" {
  description = "Tag of the wms-core image in ECR to run"
  type        = string
  default     = "latest"
}

variable "ops_agent_image_tag" {
  description = "Tag of the ops-agent image in ECR to run"
  type        = string
  default     = "latest"
}

# Workload identity federation (ADR 0028). IDs, not secrets: they name the Anthropic organization, the service
# account and the rule that trusts the ops-agent Lambda's role. The rule is created in the Claude Console.
variable "anthropic_organization_id" {
  type    = string
  default = "738a9974-7660-4335-9dc2-f1c69af42381"
}

variable "anthropic_service_account_id" {
  type    = string
  default = "svac_016AVQ5B7nnXwf7qjFfvTP5h"
}

variable "anthropic_federation_rule_id" {
  description = "The Claude Console rule that trusts the deployed agent's role (ops-agent-deployed)"
  type        = string
  default     = "fdrl_011ug4CqMeBzEaoUC34mDh1A"
}

variable "agent_daily_budget_usd" {
  type    = number
  default = 2
}

variable "github_repository" {
  description = "owner/name of the repository allowed to deploy through GitHub Actions"
  type        = string
  default     = "2u5hi/cloud-wms-agentic-ai"
}
