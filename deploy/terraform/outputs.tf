output "console_url" {
  description = "The public demo"
  value       = "https://${aws_cloudfront_distribution.console.domain_name}"
}

output "console_bucket" {
  value = aws_s3_bucket.console.bucket
}

output "cloudfront_distribution_id" {
  value = aws_cloudfront_distribution.console.id
}

output "ecr_repositories" {
  value = { for name, repo in aws_ecr_repository.service : name => repo.repository_url }
}

output "wms_core_url" {
  value = aws_lambda_function_url.wms_core.function_url
}

output "ops_agent_role_arn" {
  description = "The subject for the deployed agent's rule in the Claude Console (ADR 0028)"
  value       = aws_iam_role.ops_agent.arn
}

output "supervisor_passcode" {
  description = "Signs in as a supervisor on the demo; share it with whoever should be able to approve"
  value       = random_password.supervisor_passcode.result
  sensitive   = true
}
