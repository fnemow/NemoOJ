import hashlib
import hmac
import json

import httpx
import pytest

from nemo_agent.config import Settings
from nemo_agent.signed_client import SignedQuestionClient, QuestionTransportError, SignedServiceError


@pytest.mark.asyncio
async def test_internal_request_uses_exact_raw_body_and_path_signature():
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["request"] = request
        return httpx.Response(200, json={"code": 0, "data": {"claimed": True}})

    settings = Settings(
        question_service_url="http://question-service:8103",
        internal_secret="s" * 40,
    )
    client = SignedQuestionClient(settings, httpx.MockTransport(handler))
    try:
        body = {"workerId": "worker-1"}
        await client.post("/api/question/internal/ai/tasks/00000000-0000-0000-0000-000000000001/claim", body)
        request = captured["request"]
        timestamp = request.headers["X-Agent-Timestamp"]
        digest = hashlib.sha256(json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
        message = f"{timestamp}\nPOST\n{request.url.raw_path.decode()}\n{digest}".encode()
        expected = hmac.new(b"s" * 40, message, hashlib.sha256).hexdigest()
        assert hmac.compare_digest(request.headers["X-Agent-Signature"], expected)
    finally:
        await client.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["disconnect", "timeout", "503", "429", "system_error"])
async def test_private_link_transient_errors_are_distinct_from_content_errors(failure):
    def handler(request):
        if failure == "disconnect":
            raise httpx.ConnectError("unavailable", request=request)
        if failure == "timeout":
            raise httpx.ReadTimeout("unavailable", request=request)
        if failure == "system_error":
            return httpx.Response(200, json={"code": 50000})
        return httpx.Response(int(failure))

    client = SignedQuestionClient(Settings(), httpx.MockTransport(handler))
    try:
        with pytest.raises(QuestionTransportError):
            await client.post("/api/question/internal/ai/tasks/test/context")
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_budget_denial_is_not_retried_as_transport_failure():
    client = SignedQuestionClient(Settings(), httpx.MockTransport(lambda r:
        httpx.Response(200, json={"code": 40000, "errorKey": "BUDGET_EXHAUSTED"})))
    try:
        with pytest.raises(SignedServiceError) as error:
            await client.post("/api/question/internal/ai/tasks/test/calls/test/start")
        assert error.value.key == "BUDGET_EXHAUSTED"
    finally:
        await client.close()
