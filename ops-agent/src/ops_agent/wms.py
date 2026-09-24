from __future__ import annotations

import uuid
from typing import Any

import httpx


class WmsError(RuntimeError):
    """A call to wms-core failed. The message is the problem+json detail when there is one."""

    def __init__(self, message: str, status: int | None = None, code: str | None = None) -> None:
        super().__init__(message)
        self.status = status
        self.code = code


class WmsClient:
    """
    The agent's only view of the warehouse: the same public API the console uses. No database access,
    no private endpoints, and exactly one write path (create_proposal), which needs human approval. It
    authenticates with its own token, which wms-core only lets read and propose (ADR 0027).
    """

    def __init__(self, base_url: str, *, token: str, timeout: float = 30.0):
        self._client = httpx.AsyncClient(base_url=base_url.rstrip("/"), timeout=timeout)
        self._token = token

    async def aclose(self) -> None:
        await self._client.aclose()

    async def diagnosis(self, wave: int) -> dict[str, Any]:
        return await self._get(f"/api/v1/waves/{wave}/diagnosis")

    async def wave(self, wave: int) -> dict[str, Any]:
        return await self._get(f"/api/v1/waves/{wave}")

    async def tasks(self, wave: int, status: str | None = None, type: str | None = None) -> dict[str, Any]:
        params = {"wave": wave, "limit": 50}
        if status:
            params["status"] = status
        if type:
            params["type"] = type
        return await self._get("/api/v1/tasks", params=params)

    async def workers(self) -> dict[str, Any]:
        return await self._get("/api/v1/workers", params={"limit": 100})

    async def sku_availability(self, sku: str) -> dict[str, Any]:
        return await self._get(f"/api/v1/skus/{sku}/availability")

    async def open_proposals(self, wave: int) -> list[dict[str, Any]]:
        """Proposals for this wave still waiting on a supervisor, in compact form."""
        page = await self._get("/api/v1/proposals", params={"wave": wave, "status": "PROPOSED", "limit": 20})
        return [
            {"id": item["id"], "kind": item["kind"], "payload": item["payload"]}
            for item in page.get("items", [])
        ]

    async def create_proposal(self, body: dict[str, Any]) -> dict[str, Any]:
        return await self._post("/api/v1/proposals", body)

    async def _get(self, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        return self._result(await self._client.get(path, params=params, headers=self._headers()))

    async def _post(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        headers = self._headers() | {
            # Every command needs a unique key (ADR 0007); a retry replays rather than repeats.
            "Idempotency-Key": str(uuid.uuid4()),
        }
        return self._result(await self._client.post(path, json=body, headers=headers))

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._token}"}

    @staticmethod
    def _result(response: httpx.Response) -> dict[str, Any]:
        if response.is_success:
            return response.json() if response.content else {}
        try:
            problem = response.json()
            raise WmsError(problem.get("detail", response.text), response.status_code, problem.get("code"))
        except ValueError as exc:
            raise WmsError(response.text or f"HTTP {response.status_code}", response.status_code) from exc
