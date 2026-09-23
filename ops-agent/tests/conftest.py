from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

import pytest

from ops_agent.config import Settings

BLOCKED_DIAGNOSIS = {
    "wave": 7,
    "status": "RELEASED",
    "orders": 3,
    "picks": {"total": 5, "waiting": 2, "ready": 3, "assigned": 0, "inProgress": 0, "completed": 0},
    "blockers": [
        {
            "kind": "WAITING_ON_REPLENISHMENT",
            "rootCause": "NO_ELIGIBLE_WORKER_AVAILABLE",
            "sku": "SKU-10035",
            "affectedPicks": 2,
            "affectedOrders": 2,
            "quantity": 30,
            "detail": "2 picks wait on replenishment 5: 30 units of SKU-10035 from C-03-04-C to "
            "C-01-09-A, unclaimed for 39 min because no available worker is certified for REACH_TRUCK",
            "replenishment": {
                "taskId": 5,
                "sku": "SKU-10035",
                "status": "READY",
                "fromLocation": "C-03-04-C",
                "toLocation": "C-01-09-A",
                "quantity": 30,
                "requiredEquipment": "REACH_TRUCK",
                "assignedWorker": None,
                "ageMinutes": 39,
                "waitingPicks": 2,
                "affectedOrders": 2,
            },
            "orders": [],
        }
    ],
    "atRiskOrders": [{"order": "SO-1001", "carrierCutoffAt": "2026-09-23T21:00:00Z", "remainingPicks": 3}],
}

WORKERS = {
    "items": [
        {"code": "W-014", "name": "Ana", "status": "BREAK", "homeZone": "C", "equipment": ["REACH_TRUCK"]},
        {"code": "W-020", "name": "Sam", "status": "AVAILABLE", "homeZone": "C", "equipment": []},
    ]
}


@dataclass
class FakeWms:
    """Stands in for wms-core. Records what the agent asked for and what it tried to write."""

    diagnosis_response: dict[str, Any] = field(default_factory=lambda: dict(BLOCKED_DIAGNOSIS))
    proposals: list[dict[str, Any]] = field(default_factory=list)
    calls: list[str] = field(default_factory=list)

    async def diagnosis(self, wave: int) -> dict[str, Any]:
        self.calls.append(f"diagnosis({wave})")
        return self.diagnosis_response

    async def wave(self, wave: int) -> dict[str, Any]:
        self.calls.append(f"wave({wave})")
        return {"number": wave, "status": "RELEASED", "orders": []}

    async def tasks(self, wave: int, status: str | None = None, type: str | None = None) -> dict[str, Any]:
        self.calls.append(f"tasks({wave},{status},{type})")
        return {"items": []}

    async def workers(self) -> dict[str, Any]:
        self.calls.append("workers()")
        return WORKERS

    async def sku_availability(self, sku: str) -> dict[str, Any]:
        self.calls.append(f"availability({sku})")
        return {"sku": sku, "total": {"onHand": 30, "allocated": 30, "available": 0}}

    async def create_proposal(self, body: dict[str, Any]) -> dict[str, Any]:
        self.calls.append("create_proposal")
        self.proposals.append(body)
        return {"id": 1, "status": "PROPOSED", **body}


class Block:
    def __init__(self, **fields: Any) -> None:
        self.__dict__.update(fields)


def tool_use(name: str, arguments: dict[str, Any], id: str = "tu_1") -> Block:
    return Block(type="tool_use", name=name, input=arguments, id=id)


def text(value: str) -> Block:
    return Block(type="text", text=value)


class FakeMessages:
    def __init__(self, responses: list[list[Block]], usage: tuple[int, int] = (1000, 200)) -> None:
        self._responses = responses
        self._usage = usage
        self.requests: list[dict[str, Any]] = []

    async def create(self, **kwargs: Any) -> Block:
        self.requests.append(kwargs)
        content = self._responses[min(len(self.requests) - 1, len(self._responses) - 1)]
        return Block(
            content=content,
            usage=Block(input_tokens=self._usage[0], output_tokens=self._usage[1]),
        )


class FakeAnthropic:
    def __init__(self, responses: list[list[Block]], usage: tuple[int, int] = (1000, 200)) -> None:
        self.messages = FakeMessages(responses, usage)


@pytest.fixture
def settings() -> Settings:
    return Settings(
        anthropic_api_key="test-key",
        wms_api_url="http://wms.test",
        max_tool_calls=3,
        daily_budget_usd=1.0,
    )
