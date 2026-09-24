from __future__ import annotations

import httpx
import pytest

from ops_agent.tools import run_tool
from ops_agent.wms import WmsClient, WmsError


def client_for(handler) -> WmsClient:
    wms = WmsClient("http://wms.test", token="agent-token-123")
    wms._client = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://wms.test")
    return wms


async def test_commands_carry_an_idempotency_key_and_the_agents_own_credential():
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(201, json={"id": 3, "status": "PROPOSED"})

    wms = client_for(handler)
    await wms.create_proposal({"kind": "REASSIGN_TASK"})

    assert seen[0].headers["Idempotency-Key"]
    # Who is proposing comes from the token wms-core checks, not from a header the agent fills in.
    assert seen[0].headers["Authorization"] == "Bearer agent-token-123"
    assert "X-Agent-Id" not in seen[0].headers
    await wms.aclose()


async def test_a_problem_response_becomes_a_wms_error_with_its_code():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            404,
            json={"detail": "Wave 99 does not exist", "code": "NOT_FOUND", "status": 404},
        )

    wms = client_for(handler)

    with pytest.raises(WmsError) as error:
        await wms.diagnosis(99)

    assert error.value.code == "NOT_FOUND"
    assert error.value.status == 404
    await wms.aclose()


async def test_a_failing_read_tool_returns_the_error_as_data_for_the_model():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, json={"detail": "Wave 99 does not exist", "code": "NOT_FOUND"})

    wms = client_for(handler)

    result = await run_tool("get_wave", {"wave": 99}, wms)

    assert result == {"error": "Wave 99 does not exist", "code": "NOT_FOUND"}
    await wms.aclose()


async def test_an_unknown_tool_is_reported_rather_than_raised():
    wms = client_for(lambda request: httpx.Response(200, json={}))

    assert "Unknown tool" in (await run_tool("delete_everything", {}, wms))["error"]
    await wms.aclose()
