from __future__ import annotations

from ops_agent.config import Settings


def test_environment_names_match_the_deployment_table(monkeypatch):
    """docs/MVP_PLAN.md promises these names to whoever sets them in the Railway dashboard."""
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-test")
    monkeypatch.setenv("WMS_API_URL", "https://wms.example")
    monkeypatch.setenv("DEMO_PASSCODE", "let-me-in")
    monkeypatch.setenv("AGENT_TOKEN", "agent-token-123")
    monkeypatch.setenv("AGENT_MODEL", "claude-haiku-4-5-20251001")
    monkeypatch.setenv("AGENT_DAILY_BUDGET_USD", "0.5")

    settings = Settings(_env_file=None)

    assert settings.configured
    assert settings.wms_api_url == "https://wms.example"
    assert settings.demo_passcode == "let-me-in"
    assert settings.agent_token == "agent-token-123"
    assert settings.daily_budget_usd == 0.5


def test_without_a_key_the_agent_reports_itself_unconfigured(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)

    assert not Settings(_env_file=None).configured
