from __future__ import annotations

from typing import Any

from .wms import WmsClient, WmsError

REPORT_TOOL = "report"

# Read tools only. Anything that changes the warehouse goes through a proposal a human approves,
# which is why `report` carries the suggested fix instead of executing it.
TOOLS: list[dict[str, Any]] = [
    {
        "name": "get_wave",
        "description": "The wave with its orders, their cutoffs and how much of each is allocated.",
        "input_schema": {
            "type": "object",
            "properties": {"wave": {"type": "integer"}},
            "required": ["wave"],
        },
    },
    {
        "name": "list_tasks",
        "description": "Tasks in a wave: type, status, locations, quantity and who is assigned.",
        "input_schema": {
            "type": "object",
            "properties": {
                "wave": {"type": "integer"},
                "status": {
                    "type": "string",
                    "enum": ["WAITING", "READY", "ASSIGNED", "IN_PROGRESS", "COMPLETED"],
                },
                "type": {"type": "string", "enum": ["PICK", "REPLENISH", "COUNT"]},
            },
            "required": ["wave"],
        },
    },
    {
        "name": "list_workers",
        "description": "Workers with their status (AVAILABLE, BUSY, BREAK, OFFLINE), home zone and "
        "equipment certifications. Use this to find who could do a blocked replenishment.",
        "input_schema": {"type": "object", "properties": {}},
    },
    {
        "name": "get_sku_availability",
        "description": "Where a SKU's stock sits: on hand, allocated and available by location type.",
        "input_schema": {
            "type": "object",
            "properties": {"sku": {"type": "string"}},
            "required": ["sku"],
        },
    },
    {
        "name": REPORT_TOOL,
        "description": "Give the supervisor the answer. Call this exactly once, at the end.",
        "input_schema": {
            "type": "object",
            "properties": {
                "answer": {
                    "type": "string",
                    "description": "Two or three sentences in operator language: what is blocking the "
                    "wave, how bad it is, and what should happen.",
                },
                "evidence": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "One line per fact you used, each naming where it came from, "
                    "e.g. 'diagnosis: task 5 needs REACH_TRUCK' or 'workers: W-014 is certified, on break'.",
                },
                "proposal": {
                    "type": "object",
                    "description": "Omit when nothing can be fixed by reassigning work.",
                    "properties": {
                        "kind": {"type": "string", "enum": ["REASSIGN_TASK"]},
                        "task": {"type": "integer", "description": "The blocked task to hand over"},
                        "worker": {
                            "type": "string",
                            "description": "Worker code, who must be certified for the task's equipment",
                        },
                        "rationale": {"type": "string", "description": "Why this worker, in one sentence"},
                    },
                    "required": ["kind", "task", "worker", "rationale"],
                },
            },
            "required": ["answer", "evidence"],
        },
    },
]


async def run_tool(name: str, arguments: dict[str, Any], wms: WmsClient) -> dict[str, Any]:
    """Runs one read tool. A WMS error comes back as data so the model can react instead of failing."""
    try:
        match name:
            case "get_wave":
                return await wms.wave(int(arguments["wave"]))
            case "list_tasks":
                return await wms.tasks(
                    int(arguments["wave"]), status=arguments.get("status"), type=arguments.get("type")
                )
            case "list_workers":
                return await wms.workers()
            case "get_sku_availability":
                return await wms.sku_availability(str(arguments["sku"]))
            case _:
                return {"error": f"Unknown tool {name}"}
    except WmsError as error:
        return {"error": str(error), "code": error.code}
    except (KeyError, ValueError) as error:
        return {"error": f"Bad arguments for {name}: {error}"}
