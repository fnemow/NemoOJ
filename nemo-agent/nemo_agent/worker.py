import asyncio
import json
import logging
import socket
import uuid

import aio_pika
from langgraph.types import Command

from nemo_agent.analysis_graph import create_analysis_graph, create_model, GoalUnsupported
from nemo_agent.checkpoint_retention import retention_loop
from nemo_agent.config import Settings
from nemo_agent.signed_client import SignedQuestionClient
from nemo_agent.signed_client import SignedServiceError, QuestionTransportError
from nemo_agent.model_calls import ModelCallRecorder, ReceiptDeliveryError, active_recorder
from nemo_agent.generation_diagnostics import GenerationFailure

log = logging.getLogger("nemo_agent")


class ResultDeliveryError(RuntimeError):
    pass


class Worker:
    def __init__(self, settings: Settings, client: SignedQuestionClient, graph):
        self.settings = settings
        self.client = client
        self.graph = graph
        self.worker_id = f"{socket.gethostname()}-{uuid.uuid4()}"

    async def handle(self, message: aio_pika.abc.AbstractIncomingMessage) -> None:
        lease_worker = self.worker_id[:80] + '-' + str(uuid.uuid4())
        async with message.process(requeue=True):
            claimed = False
            context_loaded = False
            heartbeat = None
            try:
                task_id = str(json.loads(message.body).get("taskId", ""))
                uuid.UUID(task_id)
                if not await self.client.claim(task_id, lease_worker):
                    return
                claimed = True
                context = await self.client.context(task_id)
                context_loaded = True
                if context.get("taskType") == "PRIVATE_PROBLEM":
                    context["deadlineEpoch"] = float(context.get("deadlineEpoch", 0))
                    async def renew():
                        while True:
                            await asyncio.sleep(20)
                            await self.client.heartbeat(task_id, lease_worker, "KEEP")
                    heartbeat = asyncio.create_task(renew())
                config = {"configurable": {"thread_id": task_id}}
                result = None
                resume = False
                interrupted = False
                if getattr(self.graph, "checkpointer", None) is not None:
                    checkpoint = await self.graph.aget_state(config)
                    values = checkpoint.values if checkpoint else {}
                    resume = bool(checkpoint and checkpoint.next)
                    interrupted = bool(checkpoint and any(getattr(t, 'interrupts', ()) for t in getattr(checkpoint, 'tasks', ())))
                    if checkpoint and not checkpoint.next and any(key in values for key in
                            ("report", "plan", "private_problem", "feedback", "maintenance_proposal", "site_help")):
                        result = values
                if result is None:
                    token = active_recorder.set(ModelCallRecorder(self.client, self.settings, task_id, lease_worker))
                    try:
                        result = await asyncio.wait_for(
                            self.graph.ainvoke(Command(resume=True) if interrupted else None if resume else {"context": context}, config=config),
                            timeout=max(1, context.get("deadlineEpoch", 0) - __import__('time').time())
                                if context.get("taskType") == "PRIVATE_PROBLEM" else self.settings.model_timeout_seconds + 15,
                        )
                    finally:
                        active_recorder.reset(token)
                if result.get('__interrupt__'):
                    waiting = result['__interrupt__'][0].value
                    await self.client.suspend_validation(task_id, lease_worker, waiting['validationId'])
                    # Ack only after the persisted suspension is acknowledged.
                    # A delivery failure preserves the checkpoint for lease recovery.
                    return
                payload = ({"report": result["report"]} if "report" in result else
                           {"result": result["plan"]} if "plan" in result else
                           {"siteHelp":result['site_help']} if 'site_help' in result else
                           {"feedback": result["feedback"]} if "feedback" in result else
                           {"maintenanceProposal": result["maintenance_proposal"]} if "maintenance_proposal" in result else
                           {"privateProblem": result["private_problem"]})
                # Retry idempotent result delivery, never repeat model inference here.
                for attempt in range(3):
                    try:
                        await self.client.complete(
                            task_id, payload, result.get("input_tokens", 0), result.get("output_tokens", 0),
                            result.get("input_tokens", 0) * self.settings.input_usd_per_million_tokens / 1_000_000
                            + result.get("output_tokens", 0) * self.settings.output_usd_per_million_tokens / 1_000_000,
                            lease_worker,
                        )
                        break
                    except Exception:
                        if attempt == 2:
                            raise ResultDeliveryError("AI result delivery unavailable") from None
                        await asyncio.sleep(attempt + 1)
            except (ResultDeliveryError, ReceiptDeliveryError):
                # Keep the checkpoint/reservation for recovery, not a model failure.
                raise
            except QuestionTransportError:
                # Let the persisted lease/reaper and RabbitMQ recover this task.
                # Do not label a polling disconnect as invalid generated content.
                await asyncio.sleep(5)
                raise
            except Exception as exc:
                if isinstance(exc, SignedServiceError) and exc.key == 'AI_LEASE_LOST':
                    return  # A newer delivery owns this task; never fail its result.
                if not claimed:
                    # Claim may not have reached the server; never mark an unowned
                    # task failed or release its budget reservation.
                    raise
                # Provider/Pydantic exception text can include full prompts, source or credentials.
                log.error("AI task failed task_id=%s exception=%s category=%s phase=%s",
                          task_id, type(exc).__name__, getattr(exc, 'category', 'OTHER'),
                          getattr(exc, 'phase', 'OTHER'))
                try:
                    if "task_id" in locals() and task_id:
                        code = "MODEL_TIMEOUT" if isinstance(exc, TimeoutError) else "INVALID_OUTPUT" if "validation" in str(exc).lower() else "MODEL_ERROR"
                        if not context_loaded:
                            code = "CONTEXT_UNAVAILABLE"
                        if isinstance(exc, SignedServiceError) and exc.key == "BUDGET_EXHAUSTED":
                            code = "BUDGET_EXHAUSTED"
                        if isinstance(exc, GenerationFailure):
                            code = 'INVALID_OUTPUT'
                        if isinstance(exc, GoalUnsupported):
                            await self.client.fail(task_id, "GOAL_UNSUPPORTED", lease_worker, str(exc)[:500])
                        else:
                            await self.client.fail(task_id, code, lease_worker)
                except Exception:
                    log.exception("Could not release failed AI task reservation")
                    # A transient question-service outage must not consume the
                    # only RabbitMQ copy of an unfinished task.
                    await asyncio.sleep(5)
                    raise
            finally:
                if heartbeat:
                    heartbeat.cancel()
                    try:
                        await heartbeat
                    except (asyncio.CancelledError, Exception):
                        pass


async def run(settings: Settings) -> None:
    if not settings.model_enabled:
        log.warning("No AI provider is configured; consumer is not started")
        await asyncio.Event().wait()
        return
    if not settings.internal_secret.get_secret_value() or len(settings.internal_secret.get_secret_value()) < 32:
        raise RuntimeError("AI_INTERNAL_SECRET must be at least 32 characters")
    if settings.environment.lower() == "production" and not settings.postgres_url.get_secret_value():
        raise RuntimeError("AI_POSTGRES_URL is required in production for LangGraph checkpoints")

    checkpoint_cm = None
    checkpointer = None
    retention_task = None
    if settings.postgres_url.get_secret_value():
        from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

        checkpoint_cm = AsyncPostgresSaver.from_conn_string(settings.postgres_url.get_secret_value())
        checkpointer = await checkpoint_cm.__aenter__()
        await checkpointer.setup()
        retention_task = asyncio.create_task(retention_loop(
            checkpointer, settings.postgres_url.get_secret_value(), settings.retention_days))
    model = create_model(settings)
    client = SignedQuestionClient(settings)
    import nemo_agent.model_calls as model_calls
    model_calls.model_slots = asyncio.Semaphore(max(1, min(settings.model_concurrency, 2)))
    graph = create_analysis_graph(model, checkpointer, client, settings.auto_repair_enabled, settings.canonical_generation_enabled, settings.trusted_templates_enabled, settings.staged_generation_enabled, getattr(settings,'python_preflight_enabled',False), getattr(settings,'event_driven_validation_enabled',False))
    # Compose dependency ordering does not mean RabbitMQ has opened its AMQP
    # listener. Keep retrying after a cold start instead of exiting the worker.
    while True:
        try:
            connection = await aio_pika.connect_robust(
                host=settings.rabbitmq_host,
                port=settings.rabbitmq_port,
                login=settings.rabbitmq_user,
                password=settings.rabbitmq_password.get_secret_value(),
                virtualhost="/",
            )
            break
        except Exception as exc:
            log.warning("RabbitMQ is not ready (%s); retrying in 5 seconds", type(exc).__name__)
            await asyncio.sleep(5)
    try:
        worker = Worker(settings, client, graph)
        # Separate slots prevent slow validation polling from occupying the analysis consumer.
        for name in (settings.queue, "nemo.ai.analysis", "nemo.ai.plans", "nemo.ai.private", "nemo.ai.private"):
            channel = await connection.channel()
            await channel.set_qos(prefetch_count=1)
            queue = await channel.declare_queue(name, durable=True)
            await queue.consume(worker.handle)
            log.info("NemoOJ AI worker is consuming %s, prefetch=1", name)
        await asyncio.Future()
    finally:
        if retention_task:
            retention_task.cancel()
            try:
                await retention_task
            except asyncio.CancelledError:
                pass
        await client.close()
        await connection.close()
        if checkpoint_cm:
            await checkpoint_cm.__aexit__(None, None, None)
