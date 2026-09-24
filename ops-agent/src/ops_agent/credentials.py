from __future__ import annotations

import logging
from collections.abc import Callable

from anthropic import AsyncAnthropic, WorkloadIdentityCredentials

from .config import Settings

log = logging.getLogger(__name__)

AUDIENCE = "https://api.anthropic.com"


def sts_identity_token(region: str) -> Callable[[], str]:
    """
    An AWS-signed identity token for whatever role this process runs as (ADR 0028). Called by the SDK on
    every exchange, so each exchange gets a fresh token: tokens are single-use, and a cached one is refused.
    """
    import boto3

    sts = boto3.client("sts", region_name=region)

    def fetch() -> str:
        response = sts.get_web_identity_token(
            Audience=[AUDIENCE], SigningAlgorithm="RS256", DurationSeconds=900
        )
        return response["WebIdentityToken"]

    return fetch


def anthropic_client(
    settings: Settings, identity_token: Callable[[], str] | None = None
) -> tuple[AsyncAnthropic | None, str]:
    """
    The Claude client and how it authenticates: `workload-identity` (no stored key; the preferred way, and
    the only one used when deployed), `api-key` (a local fallback), or `none` (the agent is switched off and
    the console shows the WMS's own diagnosis).

    Federation is chosen explicitly when configured. The SDK's own lookup would let a stray ANTHROPIC_API_KEY
    in the environment silently win, so a key that is also present is ignored and logged.
    """
    if settings.federated:
        if settings.anthropic_api_key:
            log.warning("ANTHROPIC_API_KEY is set but workload identity is configured; ignoring the key")
        credentials = WorkloadIdentityCredentials(
            identity_token_provider=identity_token or sts_identity_token(settings.aws_region),
            federation_rule_id=settings.federation_rule_id,
            organization_id=settings.organization_id,
            service_account_id=settings.service_account_id or None,
            workspace_id=settings.workspace_id or None,
        )
        return AsyncAnthropic(credentials=credentials), "workload-identity"
    if settings.anthropic_api_key:
        return AsyncAnthropic(api_key=settings.anthropic_api_key), "api-key"
    return None, "none"
