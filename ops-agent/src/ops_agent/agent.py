from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any, Protocol

from .config import Settings
from .tools import REPORT_TOOL, TOOLS, run_tool
from .wms import WmsClient, WmsError

SYSTEM_PROMPT = """You help a warehouse supervisor understand why a wave of picking work is not \
finishing, and what to do about it.

The WMS has already worked out the blockers for you: the diagnosis in the first message is computed \
from the database, not guessed, and its `detail` lines are accurate. Start from it. Use the read tools \
only to fill gaps it leaves — most often list_workers, to find who could take a replenishment nobody \
available is certified for.

Rules:
- Every statement you make must come from the diagnosis or a tool result. Never invent task ids, worker \
codes, quantities or locations.
- Be brief and concrete. A supervisor reads this between other jobs.
- You cannot change anything yourself. If a reassignment would unblock the wave, include it as a proposal \
in your report; a human approves it and the WMS runs it.
- Only propose a worker who is certified for the equipment the task needs. A worker on BREAK or BUSY can \
be proposed (the supervisor decides whether to pull them off), but an uncertified one cannot.
- If nothing can be fixed by moving work between people - for example when stock simply does not exist - \
say so plainly and leave the proposal out.

Finish by calling the `report` tool exactly once."""


class AnthropicLike(Protocol):
    """Only the part of the SDK this uses, so tests can pass a stub instead of a real client."""

    @property
    def messages(self) -> Any: ...


@dataclass
class Usage:
    input_tokens: int = 0
    output_tokens: int = 0

    def add(self, usage: Any) -> None:
        self.input_tokens += getattr(usage, "input_tokens", 0) or 0
        self.output_tokens += getattr(usage, "output_tokens", 0) or 0

    def cost_usd(self, settings: Settings) -> float:
        return round(
            self.input_tokens / 1e6 * settings.input_usd_per_mtok
            + self.output_tokens / 1e6 * settings.output_usd_per_mtok,
            6,
        )


@dataclass
class Investigation:
    wave: int
    answer: str
    evidence: list[str]
    proposal: dict[str, Any] | None
    tool_calls: list[str] = field(default_factory=list)
    usage: dict[str, Any] = field(default_factory=dict)


class BudgetExceeded(RuntimeError):
    pass


class DailyBudget:
    """Spend since midnight UTC, in this process. Good enough for one demo instance; a shared counter
    belongs in the database when there is more than one."""

    def __init__(self, limit_usd: float) -> None:
        self._limit = limit_usd
        self._day = datetime.now(UTC).date()
        self._spent = 0.0

    @property
    def spent_usd(self) -> float:
        self._roll_over()
        return round(self._spent, 6)

    def check(self) -> None:
        self._roll_over()
        if self._spent >= self._limit:
            raise BudgetExceeded(
                f"The agent has spent ${self._spent:.2f} today, at its ${self._limit:.2f} limit."
            )

    def record(self, amount_usd: float) -> None:
        self._roll_over()
        self._spent += amount_usd

    def _roll_over(self) -> None:
        today = datetime.now(UTC).date()
        if today != self._day:
            self._day, self._spent = today, 0.0


class OpsAgent:
    def __init__(self, client: AnthropicLike, wms: WmsClient, settings: Settings, budget: DailyBudget):
        self._client = client
        self._wms = wms
        self._settings = settings
        self._budget = budget

    async def investigate(self, wave: int, question: str | None = None) -> Investigation:
        """
        One question, one answer. The diagnosis is fetched up front because it is deterministic and
        cheap (ADR 0022): the model spends its tokens explaining and deciding, not joining tables.
        """
        self._budget.check()
        diagnosis = await self._wms.diagnosis(wave)
        messages: list[dict[str, Any]] = [
            {
                "role": "user",
                "content": f"{question or f'Why is wave {wave} not finishing, and what should I do?'}\n\n"
                f"Diagnosis of wave {wave} from the WMS:\n{json.dumps(diagnosis, indent=2)}",
            }
        ]
        usage = Usage()
        called: list[str] = []

        for _ in range(self._settings.max_tool_calls + 1):
            response = await self._client.messages.create(
                model=self._settings.model,
                max_tokens=self._settings.max_output_tokens,
                system=SYSTEM_PROMPT,
                tools=TOOLS,
                messages=messages,
            )
            step = Usage()
            step.add(getattr(response, "usage", None))
            usage.input_tokens += step.input_tokens
            usage.output_tokens += step.output_tokens
            self._budget.record(step.cost_usd(self._settings))

            uses = [block for block in response.content if getattr(block, "type", None) == "tool_use"]
            report = next((use for use in uses if use.name == REPORT_TOOL), None)
            if report is not None:
                called.append(REPORT_TOOL)
                return await self._finish(wave, report.input, called, usage)
            if not uses:
                # The model answered in prose instead of reporting; take the text as the answer.
                text = " ".join(
                    block.text for block in response.content if getattr(block, "type", None) == "text"
                ).strip()
                return Investigation(
                    wave=wave,
                    answer=text or "The agent did not produce an answer.",
                    evidence=[],
                    proposal=None,
                    tool_calls=called,
                    usage=self._usage_view(usage),
                )

            results = []
            for use in uses:
                called.append(use.name)
                result = await run_tool(use.name, use.input or {}, self._wms)
                results.append(
                    {"type": "tool_result", "tool_use_id": use.id, "content": json.dumps(result)[:20_000]}
                )
            messages.append({"role": "assistant", "content": response.content})
            messages.append({"role": "user", "content": results})

        return Investigation(
            wave=wave,
            answer="The agent ran out of its tool-call budget before reaching a conclusion.",
            evidence=[],
            proposal=None,
            tool_calls=called,
            usage=self._usage_view(usage),
        )

    async def _finish(
        self, wave: int, report: dict[str, Any], called: list[str], usage: Usage
    ) -> Investigation:
        """Turns the model's report into a stored proposal. The WMS validates the payload, not the model."""
        proposed = report.get("proposal")
        stored: dict[str, Any] | None = None
        if proposed:
            body = {
                "kind": proposed.get("kind", "REASSIGN_TASK"),
                "wave": wave,
                "payload": {"task": proposed.get("task"), "worker": proposed.get("worker")},
                "rationale": proposed.get("rationale") or report["answer"],
                "evidence": report.get("evidence") or ["diagnosis"],
            }
            try:
                stored = await self._wms.create_proposal(body)
            except WmsError as error:
                stored = {"error": str(error), "code": error.code}
        return Investigation(
            wave=wave,
            answer=report["answer"],
            evidence=report.get("evidence", []),
            proposal=stored,
            tool_calls=called,
            usage=self._usage_view(usage),
        )

    def _usage_view(self, usage: Usage) -> dict[str, Any]:
        return {
            "inputTokens": usage.input_tokens,
            "outputTokens": usage.output_tokens,
            "costUsd": usage.cost_usd(self._settings),
            "model": self._settings.model,
            "spentTodayUsd": self._budget.spent_usd,
        }
