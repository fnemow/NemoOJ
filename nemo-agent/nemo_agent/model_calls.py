"""Meter each provider invocation; code/prompt text never enters the billing ledger."""
import asyncio
from contextvars import ContextVar
from decimal import Decimal
import uuid
import logging
import time

active_recorder = ContextVar("model_call_recorder", default=None)
model_slots = None


class ReceiptDeliveryError(RuntimeError):
    pass


class ModelCallRecorder:
    def __init__(self, client, settings, task_id, worker_id):
        self.client, self.settings = client, settings
        self.task_id, self.worker_id = task_id, worker_id

    async def deliver(self, call_id, state, input_tokens=0, output_tokens=0):
        cost = (Decimal(str(self.settings.input_usd_per_million_tokens)) * input_tokens
                + Decimal(str(self.settings.output_usd_per_million_tokens)) * output_tokens) / 1_000_000
        for attempt in range(3):
            try:
                await self.client.finish_call(self.task_id, call_id, self.worker_id, state,
                                              input_tokens, output_tokens, str(cost))
                return
            except Exception:
                if attempt == 2:
                    raise ReceiptDeliveryError("Model usage receipt unavailable") from None
                await asyncio.sleep(attempt + 1)

    async def invoke(self, model, messages):
        call_id = str(uuid.uuid4())
        # No model network call is allowed until reservation acknowledgement.
        await self.client.start_call(self.task_id, call_id, self.worker_id)
        started = time.monotonic()
        try:
            if model_slots is None:
                answer = await model.ainvoke(messages)
            else:
                async with model_slots:
                    answer = await model.ainvoke(messages)
        except BaseException:
            # Cancellation, timeout, or transport errors cannot prove no charge.
            # STARTED already retains its allowance if receipt delivery also fails.
            await self.deliver(call_id, "UNKNOWN")
            logging.getLogger('nemo_agent.generation').info(
                'model_call task_id=%s outcome=UNKNOWN elapsed_ms=%d', self.task_id,
                round((time.monotonic()-started)*1000))
            raise
        raw = answer.get("raw") if isinstance(answer, dict) else answer
        usage = getattr(raw, "usage_metadata", None)
        if usage and "input_tokens" in usage and "output_tokens" in usage:
            await self.deliver(call_id, "KNOWN", int(usage["input_tokens"]), int(usage["output_tokens"]))
        elif self.settings.mock_enabled and self.settings.environment.lower() in {"test", "development"}:
            await self.deliver(call_id, "KNOWN")
        else:
            await self.deliver(call_id, "UNKNOWN")
        logging.getLogger('nemo_agent.generation').info(
            'model_call task_id=%s outcome=COMPLETE elapsed_ms=%d input_tokens=%d output_tokens=%d',
            self.task_id, round((time.monotonic()-started)*1000),
            int((usage or {}).get('input_tokens', 0)), int((usage or {}).get('output_tokens', 0)))
        return answer


async def invoke_model(model, messages):
    recorder = active_recorder.get()
    return await (recorder.invoke(model, messages) if recorder else model.ainvoke(messages))
