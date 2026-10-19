import asyncio
import hashlib
import json
import time
import uuid
from decimal import Decimal
from pathlib import Path

from nemo_agent.analysis_graph import MockStructuredModel, create_analysis_graph, create_model
from nemo_agent.config import Settings
from nemo_agent.model_calls import active_recorder
from .data import OUTPUT_KEYS
from nemo_agent.schemas import FeedbackDecision


class EvalMockModel(MockStructuredModel):
    """Schema-only mock, intentionally makes no claim about correct classification."""
    def with_structured_output(self, schema, **kwargs):
        return EvalMockModel(schema)

    async def ainvoke(self, messages):
        if self.output_schema is FeedbackDecision:
            return {'parsed': FeedbackDecision(canResolve=False, escalationReason='固定 Mock 不判断实际反馈质量。'), 'raw': None}
        return await super().ainvoke(messages)


class BudgetExceeded(RuntimeError):
    pass


class InputIsolationError(RuntimeError):
    pass


class EvalRecorder:
    """Evaluation-only ledger; unknown provider outcomes retain their reservation."""
    worker_id = 'local-evaluation'

    def __init__(self, *, live=False, max_calls=0, max_cost='0', input_price='0', output_price='0', max_output=4096):
        self.live, self.max_calls, self.max_cost = live, max_calls, Decimal(max_cost)
        self.input_price, self.output_price = Decimal(input_price), Decimal(output_price)
        self.max_output = max_output
        self.calls = self.unknown = self.input_tokens = self.output_tokens = 0
        self.known_cost = self.reserved = Decimal(0)
        self.task_id = str(uuid.uuid4())
        self.forbidden_text = []

    def snapshot(self):
        return self.calls, self.unknown, self.input_tokens, self.output_tokens, self.known_cost, self.reserved

    def usage_since(self, before):
        now = self.snapshot(); values = [a - b for a, b in zip(now, before)]
        calls, unknown, input_tokens, output_tokens, cost, reserved = values
        return {'calls': calls, 'unknownCalls': unknown, 'inputTokens': input_tokens, 'outputTokens': output_tokens,
                'knownCostUsd': str(cost), 'costUsd': None if unknown else str(cost), 'reservedCostUsd': str(reserved)}

    async def invoke(self, model, messages):
        if any(marker in str(message.content) for marker in self.forbidden_text for message in messages):
            raise InputIsolationError('private evaluation canary entered a model input')
        # Conservative input allowance includes tools/schema/protocol overhead.
        input_bound = sum(len(str(m.content).encode()) for m in messages) + 65536
        estimate = (self.input_price * input_bound + self.output_price * self.max_output) / 1_000_000
        if self.live and (self.calls >= self.max_calls or self.reserved + estimate > self.max_cost):
            raise BudgetExceeded('evaluation budget exhausted before request')
        self.calls += 1; self.reserved += estimate
        try:
            answer = await model.ainvoke(messages)
        except BaseException:
            self.unknown += int(self.live)
            raise
        raw = answer.get('raw') if isinstance(answer, dict) else answer
        usage = getattr(raw, 'usage_metadata', None) or {}
        if not self.live:
            self.reserved -= estimate
        elif all(type(usage.get(k)) is int and usage[k] >= 0 for k in ('input_tokens', 'output_tokens')):
            i, o = usage['input_tokens'], usage['output_tokens']
            cost = (self.input_price * i + self.output_price * o) / 1_000_000
            self.input_tokens += i; self.output_tokens += o; self.known_cost += cost
            # Never free unknown reservations. Over-reservation receipts stop subsequent calls.
            self.reserved += cost - estimate
        else:
            self.unknown += 1
        return answer


def model_for_run(args):
    if args.mode == 'mock':
        return EvalMockModel()
    import os
    from urllib.parse import urlsplit
    key = os.environ.get('NEMO_EVAL_API_KEY', '')
    if not args.allow_model_calls or not key or not args.model or not args.base_url:
        raise ValueError('live mode requires --allow-model-calls, model/base URL and NEMO_EVAL_API_KEY')
    if urlsplit(args.base_url).scheme != 'https':
        raise ValueError('live provider endpoint must use HTTPS')
    amounts = [Decimal(v) for v in (args.max_cost_usd, args.input_price, args.output_price)]
    if args.max_calls < 1 or any(not v.is_finite() or v <= 0 for v in amounts):
        raise ValueError('live mode requires positive call/cost limits and both USD-per-million prices')
    return create_model(Settings(environment='test', provider='openai-compatible', model_id=args.model,
                                 api_key=key, base_url=args.base_url, max_output_tokens=args.max_output_tokens,
                                 model_timeout_seconds=args.call_timeout, mock_enabled=False))


async def run_cases(cases, args, out: Path, model=None):
    model = model if model is not None else model_for_run(args)
    recorder = EvalRecorder(live=args.mode == 'live', max_calls=args.max_calls, max_cost=args.max_cost_usd,
                            input_price=args.input_price, output_price=args.output_price, max_output=args.max_output_tokens)
    records = []
    # No production queues, database, signed clients or synthetic sandbox-success stubs.
    graph = create_analysis_graph(model, canonical_generation=False, auto_repair=False)
    with (out / 'responses.jsonl').open('w', encoding='utf-8') as file:
        for case in cases:
            for repetition in range(1, args.repetitions + 1):
                before = recorder.snapshot(); started = time.perf_counter(); trace = []; state = {}
                recorder.forbidden_text = case.expected.forbidden_text
                row = {'caseId': case.id, 'repetition': repetition, 'cacheState': 'not_applicable', 'status': 'completed'}
                token = active_recorder.set(recorder)
                try:
                    async with asyncio.timeout(args.case_timeout):
                        async for update in graph.astream({'context': case.context}, stream_mode='updates'):
                            for node, values in update.items():
                                trace.append(node)
                                if isinstance(values, dict): state.update(values)
                    row['output'] = {k: v for k, v in state.items() if k in OUTPUT_KEYS.values()}
                except BudgetExceeded:
                    row.update(status='skipped' if recorder.calls == before[0] else 'error', reason='budget_exhausted')
                except TimeoutError:
                    row.update(status='error', reason='timeout')
                except Exception as error:
                    # Exception messages can contain provider headers or source; store the type only.
                    row.update(status='error', reason=type(error).__name__)
                finally:
                    active_recorder.reset(token)
                row.update(trace=trace, elapsedMs=round((time.perf_counter() - started) * 1000, 3), usage=recorder.usage_since(before))
                records.append(row); file.write(json.dumps(row, ensure_ascii=False) + '\n'); file.flush()
                print(json.dumps({'caseId': case.id, 'repetition': repetition, 'status': row['status'], 'elapsedMs': row['elapsedMs']}), flush=True)
    return records


def graph_fingerprint():
    root = Path(__file__).resolve().parents[1]
    hasher = hashlib.sha256()
    for source in sorted(root.glob('*.py')):
        hasher.update(source.name.encode()); hasher.update(source.read_bytes())
    return hasher.hexdigest()
