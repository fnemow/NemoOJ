import copy
from types import SimpleNamespace
from unittest.mock import AsyncMock
import pytest
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command
from nemo_agent.analysis_graph import create_analysis_graph
from nemo_agent.model_calls import active_recorder
from test_canonical_generation import proposal, Recorder, success, context
from test_staged_generation import StagedModel as Model


@pytest.mark.asyncio
async def test_validation_wait_is_checkpointed_and_resume_does_not_generate_again():
    model = Model(await proposal())
    terminal = False
    drafts = []
    async def submit(*args):
        draft = args[3]
        drafts.append(copy.deepcopy(draft))
        return dict(success(draft), validationId='job') if terminal else {'status':'RUNNING','validationId':'job'}
    api = SimpleNamespace(heartbeat=AsyncMock(), validate_private=AsyncMock(side_effect=submit),
                          validation_status=AsyncMock(side_effect=lambda *args: success(drafts[-1])))
    graph = create_analysis_graph(model, InMemorySaver(), api, staged_generation=True,
                                  event_driven_validation=True)
    config = {'configurable':{'thread_id':'event-resume'}}
    token = active_recorder.set(Recorder())
    try:
        first = await graph.ainvoke({'context':context()}, config)
        assert first['__interrupt__'][0].value == {'validationId':'job'}
        assert len(model.calls) == 2
        terminal = True
        second = await graph.ainvoke(Command(resume=True), config)
        assert second['validation']['passed'] and len(model.calls) == 2
        assert drafts[0] == drafts[1]
        assert second['validation_revision'] == 1
    finally:
        active_recorder.reset(token)


@pytest.mark.asyncio
async def test_worker_acknowledges_wait_without_complete_or_failure():
    from nemo_agent.worker import Worker
    from nemo_agent.config import Settings
    from test_worker_faults_unittest import Message
    from langgraph.types import Interrupt
    import time
    api = SimpleNamespace(claim=AsyncMock(return_value=True),
        context=AsyncMock(return_value={'taskType':'PRIVATE_PROBLEM','deadlineEpoch':time.time()+900}),
        heartbeat=AsyncMock(), suspend_validation=AsyncMock(), complete=AsyncMock(), fail=AsyncMock())
    graph = SimpleNamespace(checkpointer=None, ainvoke=AsyncMock(return_value={
        '__interrupt__':(Interrupt(value={'validationId':'job'}),)}))
    await Worker(Settings(environment='test'), api, graph).handle(Message())
    api.suspend_validation.assert_awaited_once()
    api.complete.assert_not_awaited()
    api.fail.assert_not_awaited()


@pytest.mark.asyncio
async def test_recovered_interrupt_uses_resume_and_a_new_delivery_lease():
    from nemo_agent.worker import Worker
    from nemo_agent.config import Settings
    from test_worker_faults_unittest import Message
    api=SimpleNamespace(claim=AsyncMock(return_value=True),context=AsyncMock(return_value={}),
                        complete=AsyncMock(),fail=AsyncMock())
    graph=SimpleNamespace(checkpointer=object(),aget_state=AsyncMock(return_value=SimpleNamespace(
        values={},next=('validate_private_problem',),tasks=(SimpleNamespace(interrupts=(object(),)),))),
        ainvoke=AsyncMock(return_value={'report':{}}))
    worker=Worker(Settings(environment='test'),api,graph)
    await worker.handle(Message());await worker.handle(Message())
    assert isinstance(graph.ainvoke.await_args.args[0],Command)
    assert api.claim.await_args_list[0].args[1]!=api.claim.await_args_list[1].args[1]
    api.fail.assert_not_awaited()
