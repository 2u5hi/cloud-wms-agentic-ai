from __future__ import annotations

import secrets
from contextlib import asynccontextmanager
from typing import Annotated, Any

import anthropic
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from .agent import BudgetExceeded, DailyBudget, OpsAgent
from .config import settings
from .credentials import anthropic_client
from .wms import WmsClient, WmsError

budget = DailyBudget(settings.daily_budget_usd)


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.wms = WmsClient(
        settings.wms_api_url,
        token=settings.agent_token,
        timeout=settings.request_timeout_seconds,
    )
    app.state.anthropic, app.state.auth = anthropic_client(settings)
    yield
    await app.state.wms.aclose()


app = FastAPI(
    title="WMS ops agent",
    summary="Explains why a wave is blocked and proposes a fix for a human to approve",
    lifespan=lifespan,
)


class InvestigateRequest(BaseModel):
    wave: int = Field(gt=0)
    question: str | None = Field(default=None, max_length=500)


class InvestigateResponse(BaseModel):
    wave: int
    answer: str
    evidence: list[str]
    proposals: list[dict[str, Any]]
    toolCalls: list[str]
    usage: dict[str, Any]


def authorize(authorization: Annotated[str | None, Header()] = None) -> None:
    """
    Only a supervisor can start an investigation: each one costs money. Same credential and header as
    wms-core, so the console sends one thing to both.
    """
    if not settings.demo_passcode:
        return
    presented = (authorization or "").removeprefix("Bearer ").strip()
    if not secrets.compare_digest(presented.encode(), settings.demo_passcode.encode()):
        raise HTTPException(
            status_code=401,
            detail="Sign in as a supervisor to ask the agent"
            if authorization is None
            else "Those credentials were not recognised",
        )


def provider_message(error: anthropic.APIStatusError) -> str:
    """The provider's own explanation, e.g. "Your credit balance is too low", without the request id noise."""
    body = error.body if isinstance(error.body, dict) else {}
    detail = body.get("error", {}) if isinstance(body.get("error"), dict) else {}
    return detail.get("message") or error.message


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "model": settings.model,
        "configured": settings.configured,
        # How it reaches Claude, never the credential itself.
        "auth": getattr(app.state, "auth", "none"),
        "spentTodayUsd": budget.spent_usd,
        "dailyBudgetUsd": settings.daily_budget_usd,
    }


@app.post("/investigate", response_model=InvestigateResponse, dependencies=[Depends(authorize)])
async def investigate(request: InvestigateRequest) -> InvestigateResponse:
    if app.state.anthropic is None:
        raise HTTPException(
            status_code=503,
            detail="The agent has no Claude credentials configured; "
            "the console still shows the WMS diagnosis.",
        )
    agent = OpsAgent(app.state.anthropic, app.state.wms, settings, budget)
    try:
        result = await agent.investigate(request.wave, request.question)
    except BudgetExceeded as error:
        raise HTTPException(status_code=429, detail=str(error)) from error
    except WmsError as error:
        raise HTTPException(status_code=error.status or 502, detail=str(error)) from error
    except anthropic.APIStatusError as error:
        # The model provider refused (billing, rate limit, overload): say why instead of a bare 500.
        raise HTTPException(
            status_code=502, detail=f"Claude refused the request: {provider_message(error)}"
        ) from error
    except anthropic.APIConnectionError as error:
        raise HTTPException(status_code=502, detail="Could not reach Claude; try again shortly") from error
    return InvestigateResponse(
        wave=result.wave,
        answer=result.answer,
        evidence=result.evidence,
        proposals=result.proposals,
        toolCalls=result.tool_calls,
        usage=result.usage,
    )
