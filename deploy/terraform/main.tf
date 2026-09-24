# The deployed demo on AWS (ADR 0026, ADR 0029): two Lambda container images, RDS for MySQL, and the console on
# S3 behind CloudFront, which also routes /api, /demo and /agent to the services so the browser sees one origin.

terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # State lives in a versioned, encrypted, private bucket created once by hand; S3's own lock file prevents two
  # applies at once. The state holds the generated passwords, which is why the bucket is locked down.
  backend "s3" {
    bucket       = "cloud-wms-tfstate-077905368383"
    key          = "demo/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "cloud-wms"
      ManagedBy = "terraform"
    }
  }
}

data "aws_caller_identity" "current" {}

locals {
  name       = "cloud-wms"
  account_id = data.aws_caller_identity.current.account_id
}
