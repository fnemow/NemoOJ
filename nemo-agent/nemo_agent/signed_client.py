import hashlib
import hmac
import json
import time
from urllib.parse import urljoin

import httpx

from nemo_agent.config import Settings


class SignedServiceError(RuntimeError):
    def __init__(self, key):
        super().__init__("question service rejected signed agent request")
        self.key = key


class QuestionTransportError(RuntimeError):
    """Transient private-link failure; retain task ownership and checkpoints."""


class SignedQuestionClient:
    def __init__(self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None):
        self.settings = settings
        self.client = httpx.AsyncClient(
            base_url=settings.question_service_url.rstrip("/"),
            timeout=20,
            transport=transport,
        )

    async def close(self) -> None:
        await self.client.aclose()

    async def post(self, path: str, body: dict | None = None, timeout: float | None = None) -> dict:
        payload = json.dumps(body or {}, separators=(",", ":"), ensure_ascii=False).encode()
        timestamp = str(int(time.time()))
        uri = urljoin(self.settings.question_service_url.rstrip("/") + "/", path.lstrip("/"))
        request_path = httpx.URL(uri).raw_path.decode("ascii")
        digest = hashlib.sha256(payload).hexdigest()
        message = f"{timestamp}\nPOST\n{request_path}\n{digest}".encode()
        signature = hmac.new(
            self.settings.internal_secret.get_secret_value().encode(), message, hashlib.sha256
        ).hexdigest()
        try:
            response = await self.client.post(
                path,
                content=payload,
                timeout=timeout if timeout is not None else 20,
                headers={
                    "Content-Type": "application/json",
                    "X-Agent-Timestamp": timestamp,
                    "X-Agent-Signature": signature,
                },
            )
        except httpx.TransportError:
            raise QuestionTransportError("Question service private link unavailable") from None
        if response.status_code == 429 or response.status_code >= 500:
            raise QuestionTransportError("Question service temporarily unavailable")
        response.raise_for_status()
        envelope = response.json()
        if envelope.get("code") == 50000:
            raise QuestionTransportError("Question service temporarily unavailable")
        if envelope.get("code") != 0:
            raise SignedServiceError(envelope.get("errorKey"))
        return envelope["data"]

    async def claim(self, task_id: str, worker_id: str) -> bool:
        result = await self.post(f"/api/question/internal/ai/tasks/{task_id}/claim", {"workerId": worker_id})
        return bool(result.get("claimed"))

    async def context(self, task_id: str) -> dict:
        return await self.post(f"/api/question/internal/ai/tasks/{task_id}/context")

    async def heartbeat(self, task_id: str, worker_id: str, stage: str, repair_count: int = 0) -> None:
        await self.post(f"/api/question/internal/ai/tasks/{task_id}/heartbeat", {"workerId": worker_id, "stage": stage, "repairCount": repair_count})

    async def validate_private(self, task_id: str, worker_id: str, revision: int, draft: dict) -> dict:
        return await self.post(f"/api/question/internal/ai/tasks/{task_id}/validations", {
            "workerId": worker_id, "revision": revision, "privateProblem": draft})

    async def validation_status(self, task_id: str, worker_id: str, validation_id: str) -> dict:
        return await self.post(f"/api/question/internal/ai/tasks/{task_id}/validations/{validation_id}", {"workerId": worker_id})

    async def suspend_validation(self, task_id: str, worker_id: str, validation_id: str) -> None:
        await self.post(f"/api/question/internal/ai/tasks/{task_id}/heartbeat", {
            "workerId": worker_id, "stage": "WAIT_VALIDATION", "validationId": validation_id})

    async def complete(self, task_id: str, result: dict, input_tokens: int, output_tokens: int,
                       estimated_cost_usd: float, worker_id: str = "") -> None:
        await self.post(f"/api/question/internal/ai/tasks/{task_id}/complete", {
            **result,
            "inputTokens": max(0, input_tokens),
            "outputTokens": max(0, output_tokens),
            "estimatedCostUsd": max(0.0, estimated_cost_usd),
            "workerId": worker_id,
        }, timeout=self.settings.completion_timeout_seconds)

    async def fail(self, task_id: str, code: str, worker_id: str = "", message: str = "") -> None:
        await self.post(f"/api/question/internal/ai/tasks/{task_id}/fail", {"errorCode": code, "workerId": worker_id, "message": message})

    async def start_call(self, task_id, call_id, worker_id):
        await self.post(f"/api/question/internal/ai/tasks/{task_id}/calls/{call_id}/start", {"workerId": worker_id})

    async def finish_call(self, task_id, call_id, worker_id, state, input_tokens, output_tokens, cost):
        await self.post(f"/api/question/internal/ai/tasks/{task_id}/calls/{call_id}/finish", {
            "workerId": worker_id, "state": state, "inputTokens": input_tokens,
            "outputTokens": output_tokens, "estimatedCostUsd": cost,
        })
