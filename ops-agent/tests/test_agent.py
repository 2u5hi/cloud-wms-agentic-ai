from __future__ import annotations

import json

import pytest

from conftest import BLOCKED_DIAGNOSIS, FakeAnthropic, FakeWms, text, tool_use
from ops_agent.agent import BudgetExceeded, DailyBudget, OpsAgent


def agent(client, wms, settings):
    return OpsAgent(client, wms, settings, DailyBudget(settings.daily_budget_usd))


async def test_the_diagnosis_is_fetched_before_the_model_is_asked_anything(settings):
    """The deterministic part is free of the model, so a simple question costs one call (ADR 0022)."""
    wms = FakeWms()
    client = FakeAnthropic([[tool_use("report", {"answer": "Blocked.", "evidence": ["diagnosis"]})]])

    result = await agent(client, wms, settings).investigate(7)

    assert wms.calls[0] == "diagnosis(7)"
    prompt = client.messages.requests[0]["messages"][0]["content"]
    assert json.dumps(BLOCKED_DIAGNOSIS, indent=2) in prompt
    assert result.answer == "Blocked."
    assert result.tool_calls == ["report"]


async def test_a_reassignment_is_stored_as_a_proposal_not_executed(settings):
    wms = FakeWms()
    client = FakeAnthropic(
        [
            [tool_use("list_workers", {})],
            [
                tool_use(
                    "report",
                    {
                        "answer": "Replenishment 5 needs a reach truck; only Ana is certified, on break.",
                        "evidence": [
                            "diagnosis: task 5 needs REACH_TRUCK",
                            "workers: W-014 certified, BREAK",
                        ],
                        "proposal": {
                            "kind": "REASSIGN_TASK",
                            "task": 5,
                            "worker": "W-014",
                            "rationale": "Ana is the only certified driver",
                        },
                    },
                )
            ],
        ]
    )

    result = await agent(client, wms, settings).investigate(7)

    assert result.tool_calls == ["list_workers", "report"]
    assert wms.proposals == [
        {
            "kind": "REASSIGN_TASK",
            "wave": 7,
            "payload": {"task": 5, "worker": "W-014"},
            "rationale": "Ana is the only certified driver",
            "evidence": ["diagnosis: task 5 needs REACH_TRUCK", "workers: W-014 certified, BREAK"],
        }
    ]
    assert result.proposal["status"] == "PROPOSED"
    # Nothing on the floor was touched: the only write was the proposal itself.
    assert [call for call in wms.calls if call.startswith("create") or "reassign" in call] == [
        "create_proposal"
    ]


async def test_a_report_without_a_proposal_is_an_explanation_only(settings):
    wms = FakeWms()
    client = FakeAnthropic(
        [[tool_use("report", {"answer": "No stock exists for SKU-10035.", "evidence": ["diagnosis"]})]]
    )

    result = await agent(client, wms, settings).investigate(7)

    assert result.proposal is None
    assert wms.proposals == []


async def test_tool_results_are_fed_back_to_the_model(settings):
    wms = FakeWms()
    client = FakeAnthropic(
        [
            [tool_use("list_workers", {}, id="tu_9")],
            [tool_use("report", {"answer": "Done.", "evidence": ["workers"]})],
        ]
    )

    await agent(client, wms, settings).investigate(7)

    follow_up = client.messages.requests[1]["messages"]
    assert follow_up[-1]["content"][0]["tool_use_id"] == "tu_9"
    assert "W-014" in follow_up[-1]["content"][0]["content"]


async def test_running_out_of_tool_calls_ends_the_investigation(settings):
    wms = FakeWms()
    client = FakeAnthropic([[tool_use("list_workers", {})]])

    result = await agent(client, wms, settings).investigate(7)

    assert len(client.messages.requests) == settings.max_tool_calls + 1
    assert "budget" in result.answer


async def test_prose_instead_of_a_report_is_still_returned(settings):
    wms = FakeWms()
    client = FakeAnthropic([[text("The wave is waiting on a reach truck.")]])

    result = await agent(client, wms, settings).investigate(7)

    assert result.answer == "The wave is waiting on a reach truck."
    assert result.evidence == []


async def test_usage_is_priced_and_counted_against_the_daily_budget(settings):
    wms = FakeWms()
    client = FakeAnthropic(
        [[tool_use("report", {"answer": "Blocked.", "evidence": ["diagnosis"]})]], usage=(1_000_000, 200_000)
    )
    budget = DailyBudget(settings.daily_budget_usd)

    result = await OpsAgent(client, wms, settings, budget).investigate(7)

    # 1M input at $1 + 200k output at $5.
    assert result.usage["costUsd"] == pytest.approx(2.0)
    assert budget.spent_usd == pytest.approx(2.0)


async def test_the_next_investigation_is_refused_once_the_budget_is_spent(settings):
    wms = FakeWms()
    client = FakeAnthropic(
        [[tool_use("report", {"answer": "Blocked.", "evidence": ["diagnosis"]})]], usage=(1_000_000, 200_000)
    )
    budget = DailyBudget(settings.daily_budget_usd)
    agent_under_test = OpsAgent(client, wms, settings, budget)
    await agent_under_test.investigate(7)

    with pytest.raises(BudgetExceeded):
        await agent_under_test.investigate(7)
