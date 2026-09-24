from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """
    Everything the agent needs, from the environment. Names match the deployment table in
    docs/MVP_PLAN.md. The API key is set in the Railway dashboard, never committed and never returned
    by any endpoint.
    """

    model_config = SettingsConfigDict(env_file=".env", extra="ignore", populate_by_name=True)

    wms_api_url: str = Field(default="http://localhost:8080", alias="WMS_API_URL")
    # How the agent reaches Claude (ADR 0028). Workload identity federation is preferred: the process's
    # AWS role gets a signed identity token from STS and Anthropic swaps it for a short-lived access
    # token, so there is no key to store. The IDs below are not secrets. An API key is a local fallback.
    federation_rule_id: str = Field(default="", alias="ANTHROPIC_FEDERATION_RULE_ID")
    organization_id: str = Field(default="", alias="ANTHROPIC_ORGANIZATION_ID")
    service_account_id: str = Field(default="", alias="ANTHROPIC_SERVICE_ACCOUNT_ID")
    workspace_id: str = Field(default="", alias="ANTHROPIC_WORKSPACE_ID")
    aws_region: str = Field(default="us-east-1", alias="AWS_REGION")
    anthropic_api_key: str = Field(default="", alias="ANTHROPIC_API_KEY")
    model: str = Field(default="claude-haiku-4-5-20251001", alias="AGENT_MODEL")
    # Caps. An investigation reads structured summaries (ADR 0022), so a handful of calls is plenty;
    # the limits exist so a loop or a bad prompt cannot run up a bill.
    max_tool_calls: int = Field(default=6, alias="AGENT_MAX_TOOL_CALLS")
    max_output_tokens: int = Field(default=1200, alias="AGENT_MAX_OUTPUT_TOKENS")
    daily_budget_usd: float = Field(default=2.0, alias="AGENT_DAILY_BUDGET_USD")
    # Haiku 4.5 list price, USD per million tokens. Used only to enforce the daily cap.
    input_usd_per_mtok: float = Field(default=1.0, alias="AGENT_INPUT_USD_PER_MTOK")
    output_usd_per_mtok: float = Field(default=5.0, alias="AGENT_OUTPUT_USD_PER_MTOK")
    # The agent's own credential for wms-core: read and propose, never execute (ADR 0027).
    agent_token: str = Field(default="dev-agent-token", alias="AGENT_TOKEN")
    # The supervisor passcode, required to start an investigation because each one costs money. Empty means
    # open, which is only for local development.
    demo_passcode: str = Field(default="", alias="DEMO_PASSCODE")
    request_timeout_seconds: float = Field(default=30.0, alias="AGENT_REQUEST_TIMEOUT_SECONDS")

    @property
    def federated(self) -> bool:
        return bool(self.federation_rule_id and self.organization_id)

    @property
    def configured(self) -> bool:
        return self.federated or bool(self.anthropic_api_key)


settings = Settings()
