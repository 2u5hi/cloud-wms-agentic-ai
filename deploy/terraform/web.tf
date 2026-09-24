# The console: static files in a private bucket, served by CloudFront. CloudFront also routes /api, /demo and
# /actuator to wms-core and /agent to ops-agent, so the browser talks to one origin and needs no CORS.

resource "aws_s3_bucket" "console" {
  bucket        = "${local.name}-console-${local.account_id}"
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "console" {
  bucket                  = aws_s3_bucket.console.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

resource "aws_cloudfront_origin_access_control" "console" {
  name                              = "${local.name}-console"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_s3_bucket_policy" "console" {
  bucket = aws_s3_bucket.console.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "CloudFrontReadsTheConsole"
      Effect    = "Allow"
      Principal = { Service = "cloudfront.amazonaws.com" }
      Action    = "s3:GetObject"
      Resource  = "${aws_s3_bucket.console.arn}/*"
      Condition = { StringEquals = { "AWS:SourceArn" = aws_cloudfront_distribution.console.arn } }
    }]
  })
}

# Client-side routes (/waves/1) are the console's, not files: serve index.html for anything without an extension.
resource "aws_cloudfront_function" "spa" {
  name    = "${local.name}-spa"
  runtime = "cloudfront-js-2.0"
  code    = <<-JS
    function handler(event) {
      var request = event.request;
      if (request.uri.indexOf('.') === -1) {
        request.uri = '/index.html';
      }
      return request;
    }
  JS
}

# ops-agent serves /investigate; the console calls /agent/investigate, as it does through the Vite proxy locally.
resource "aws_cloudfront_function" "agent_prefix" {
  name    = "${local.name}-agent-prefix"
  runtime = "cloudfront-js-2.0"
  code    = <<-JS
    function handler(event) {
      var request = event.request;
      request.uri = request.uri.replace(/^\/agent/, '') || '/';
      return request;
    }
  JS
}

# API responses are never cached (Spring Security sends no-store). The Authorization header has to be part of
# the cache key for CloudFront to forward it at all, and that needs a maximum TTL above zero.
resource "aws_cloudfront_cache_policy" "api" {
  name        = "${local.name}-api"
  min_ttl     = 0
  default_ttl = 0
  max_ttl     = 1

  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_gzip   = true
    enable_accept_encoding_brotli = true

    headers_config {
      header_behavior = "whitelist"
      headers {
        items = ["Authorization"]
      }
    }
    query_strings_config {
      query_string_behavior = "all"
    }
    cookies_config {
      cookie_behavior = "none"
    }
  }
}

# Everything else the services need, except Host: a Lambda function URL only answers to its own host name.
resource "aws_cloudfront_origin_request_policy" "api" {
  name = "${local.name}-api"

  headers_config {
    header_behavior = "whitelist"
    headers {
      items = ["Idempotency-Key", "Content-Type", "Accept"]
    }
  }
  query_strings_config {
    query_string_behavior = "all"
  }
  cookies_config {
    cookie_behavior = "none"
  }
}

locals {
  wms_core_host  = split("/", aws_lambda_function_url.wms_core.function_url)[2]
  ops_agent_host = split("/", aws_lambda_function_url.ops_agent.function_url)[2]
  # Managed policies: CachingOptimized for the static console.
  caching_optimized = "658327ea-f89d-4fab-a63d-7e88639e58f6"
}

resource "aws_cloudfront_distribution" "console" {
  enabled             = true
  comment             = "Cloud WMS console and API"
  default_root_object = "index.html"
  price_class         = "PriceClass_100"
  http_version        = "http2and3"

  origin {
    origin_id                = "console"
    domain_name              = aws_s3_bucket.console.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.console.id
  }

  origin {
    origin_id   = "wms-core"
    domain_name = local.wms_core_host
    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
      origin_read_timeout    = 60 # a demo reset rebuilds the warehouse
    }
  }

  origin {
    origin_id   = "ops-agent"
    domain_name = local.ops_agent_host
    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
      origin_read_timeout    = 60 # an investigation is a few model calls
    }
  }

  default_cache_behavior {
    target_origin_id       = "console"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    cache_policy_id        = local.caching_optimized
    compress               = true

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa.arn
    }
  }

  dynamic "ordered_cache_behavior" {
    for_each = ["/api/*", "/demo/*", "/actuator/*"]
    content {
      path_pattern             = ordered_cache_behavior.value
      target_origin_id         = "wms-core"
      viewer_protocol_policy   = "https-only"
      allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
      cached_methods           = ["GET", "HEAD"]
      cache_policy_id          = aws_cloudfront_cache_policy.api.id
      origin_request_policy_id = aws_cloudfront_origin_request_policy.api.id
      compress                 = true
    }
  }

  ordered_cache_behavior {
    path_pattern             = "/agent/*"
    target_origin_id         = "ops-agent"
    viewer_protocol_policy   = "https-only"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    cache_policy_id          = aws_cloudfront_cache_policy.api.id
    origin_request_policy_id = aws_cloudfront_origin_request_policy.api.id
    compress                 = true

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.agent_prefix.arn
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }
}
