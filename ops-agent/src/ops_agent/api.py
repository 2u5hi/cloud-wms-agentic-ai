from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Annotated, Any

from anthropic import AsyncAnthropic
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from .agent import BudgetExceeded, DailyBudget, OpsAgent
from .config import settings
from .wms import WmsClient, WmsError

budget = DailyBudget(settings.daily_budget_usd)


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.wms = WmsClient(
        settings.wms_api_url,
        passcode=settings.demo_passcode,
        agent_id=settings.agent_id,
        timeout=settings.request_timeout_seconds,
    )
    app.state.anthropic = AsyncAnthropic(api_key=settings.anthropic_api_key) if settings.configured else None
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
    proposal: dict[str, Any] | None
    toolCalls: list[str]
    usage: dict[str, Any]


def authorize(x_demo_passcode: Annotated[str | None, Header()] = None) -> None:
    """The deployed demo is public; the passcode keeps the bill down, not secrets in."""
    if settings.demo_passcode and x_demo_passcode != settings.demo_passcode:
        raise HTTPException(status_code=401, detail="Wrong or missing demo passcode")


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "model": settings.model,
        "configured": settings.configured,
        "spentTodayUsd": budget.spent_usd,
        "dailyBudgetUsd": settings.daily_budget_usd,
    }


@app.post("/investigate", response_model=InvestigateResponse, dependencies=[Depends(authorize)])
async def investigate(request: InvestigateRequest) -> InvestigateResponse:
    if app.state.anthropic is None:
        raise HTTPException(
            status_code=503,
            detail="The agent has no API key configured; the console still shows the WMS diagnosis.",
        )
    agent = OpsAgent(app.state.anthropic, app.state.wms, settings, budget)
    try:
        result = await agent.investigate(request.wave, request.question)
    except BudgetExceeded as error:
        raise HTTPException(status_code=429, detail=str(error)) from error
    except WmsError as error:
        raise HTTPException(status_code=error.status or 502, detail=str(error)) from error
    return InvestigateResponse(
        wave=result.wave,
        answer=result.answer,
        evidence=result.evidence,
        proposal=result.proposal,
        toolCalls=result.tool_calls,
        usage=result.usage,
    )
