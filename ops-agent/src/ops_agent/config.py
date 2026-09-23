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
    # Shared secret for the deployed demo; empty means the service is open (local development).
    demo_passcode: str = Field(default="", alias="DEMO_PASSCODE")
    agent_id: str = Field(default="ops-agent", alias="AGENT_ID")
    request_timeout_seconds: float = Field(default=30.0, alias="AGENT_REQUEST_TIMEOUT_SECONDS")

    @property
    def configured(self) -> bool:
        return bool(self.anthropic_api_key)


settings = Settings()
