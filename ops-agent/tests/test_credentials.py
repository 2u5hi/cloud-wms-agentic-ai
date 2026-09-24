from __future__ import annotations

import logging

from ops_agent.config import Settings
from ops_agent.credentials import anthropic_client


def settings(**values) -> Settings:
    return Settings(_env_file=None, **values)


FEDERATION = {
    "federation_rule_id": "fdrl_test",
    "organization_id": "00000000-0000-0000-0000-000000000000",
    "service_account_id": "svac_test",
}


def test_workload_identity_is_used_when_configured():
    client, auth = anthropic_client(settings(**FEDERATION), identity_token=lambda: "aws-signed-jwt")

    assert auth == "workload-identity"
    assert client is not None


def test_a_stray_api_key_never_shadows_federation(caplog):
    """The SDK's own lookup puts ANTHROPIC_API_KEY first; ours does not, and says so."""
    with caplog.at_level(logging.WARNING):
        _, auth = anthropic_client(
            settings(**FEDERATION, anthropic_api_key="sk-ant-leftover"), identity_token=lambda: "jwt"
        )

    assert auth == "workload-identity"
    assert "ignoring the key" in caplog.text
    assert "sk-ant-leftover" not in caplog.text


def test_an_api_key_is_the_local_fallback():
    _, auth = anthropic_client(settings(anthropic_api_key="sk-ant-local"))

    assert auth == "api-key"


def test_with_neither_the_agent_is_off():
    client, auth = anthropic_client(settings())

    assert client is None
    assert auth == "none"


def test_federation_needs_at_least_the_rule_and_the_organization():
    assert not settings(federation_rule_id="fdrl_test").federated
    assert settings(**FEDERATION).federated
