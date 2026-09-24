from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from ops_agent import api


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setattr(api.settings, "demo_passcode", "supervisor-passcode")
    monkeypatch.setattr(api.settings, "anthropic_api_key", "")
    with TestClient(api.app) as test_client:
        yield test_client


def test_starting_an_investigation_needs_the_supervisor_credential(client):
    response = client.post("/investigate", json={"wave": 1})

    assert response.status_code == 401
    assert response.json()["detail"] == "Sign in as a supervisor to ask the agent"


def test_a_wrong_credential_is_refused(client):
    response = client.post(
        "/investigate", json={"wave": 1}, headers={"Authorization": "Bearer agent-token-guess"}
    )

    assert response.status_code == 401
    assert response.json()["detail"] == "Those credentials were not recognised"


def test_the_right_credential_gets_through_to_the_agent(client):
    # No API key in this test, so getting past auth lands on "not configured" rather than on the model.
    response = client.post(
        "/investigate", json={"wave": 1}, headers={"Authorization": "Bearer supervisor-passcode"}
    )

    assert response.status_code == 503


def test_health_is_public_and_never_shows_credentials(client):
    body = client.get("/health").json()

    assert body["status"] == "ok"
    assert "supervisor-passcode" not in str(body)


def test_a_provider_refusal_reaches_the_console_with_its_reason(client, monkeypatch):
    import anthropic
    import httpx

    request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    refusal = anthropic.BadRequestError(
        "Error code: 400",
        response=httpx.Response(400, request=request),
        body={"error": {"type": "invalid_request_error", "message": "Your credit balance is too low"}},
    )

    async def refuse(self, wave, question=None):
        raise refusal

    monkeypatch.setattr(api.OpsAgent, "investigate", refuse)
    monkeypatch.setattr(api.app.state, "anthropic", object())

    response = client.post(
        "/investigate", json={"wave": 1}, headers={"Authorization": "Bearer supervisor-passcode"}
    )

    assert response.status_code == 502
    assert response.json()["detail"] == "Claude refused the request: Your credit balance is too low"
