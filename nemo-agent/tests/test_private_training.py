import copy
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest
from langgraph.checkpoint.memory import InMemorySaver
from nemo_agent.analysis_graph import create_analysis_graph as _create_analysis_graph, MockStructuredModel, normalize_plan_items, GoalUnsupported
from nemo_agent.schemas import PrivateGenerationResponse
from nemo_agent.model_calls import active_recorder


def create_analysis_graph(*args, **kwargs):
    # Legacy checkpoint compatibility remains supported alongside the new pipeline.
    return _create_analysis_graph(*args, **kwargs, canonical_generation=False)


def context():
    return {"taskType": "PRIVATE_PROBLEM", "deadlineEpoch": time.time()+900,
            "request": {"goal": "练习两数相加", "difficulty": "EASY"}}


class Recorder:
    task_id = "task"
    worker_id = "worker"
    def __init__(self): self.calls = 0
    async def invoke(self, model, messages):
        self.calls += 1
        if self.calls > 4: raise ValueError("CALL_LIMIT")
        return await model.ainvoke(messages)


@pytest.mark.asyncio
@pytest.mark.parametrize("category", ["COMPILE", "RUNTIME", "WRONG_ANSWER", "SCHEMA"])
async def test_sandbox_failure_repairs_same_candidate_and_revalidates(category):
    client=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(side_effect=[
        {"status":"FAILED","result":{"passed":False,"category":category,"diagnostic":"fix code"}},
        {"status":"SUCCEEDED","result":{"passed":True,"combinations":["java:ACM","java:FUNCTION","python:ACM","python:FUNCTION","cpp:ACM","cpp:FUNCTION"]}}]))
    graph=create_analysis_graph(MockStructuredModel(),validation_client=client)
    recorder=Recorder();token=active_recorder.set(recorder)
    try:
        result=await graph.ainvoke({"context":context()})
    finally: active_recorder.reset(token)
    assert result["repair_count"] == 1
    assert result["validation_revision"] == 2
    assert recorder.calls == 2
    assert client.validate_private.call_args_list[0].args[3] == client.validate_private.call_args_list[1].args[3]


@pytest.mark.asyncio
async def test_infrastructure_failure_never_asks_model_to_change_problem():
    client=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(return_value={"status":"FAILED","result":{"passed":False,"category":"INFRASTRUCTURE"}}))
    token=active_recorder.set(Recorder())
    try:
        with pytest.raises(ValueError,match="INFRASTRUCTURE"):
            await create_analysis_graph(MockStructuredModel(),validation_client=client).ainvoke({"context":context()})
        assert active_recorder.get().calls == 1
    finally:active_recorder.reset(token)


@pytest.mark.asyncio
async def test_content_failures_stop_after_two_business_repairs():
    client=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(return_value={"status":"FAILED","result":{"passed":False,"category":"COMPILE"}}))
    recorder=Recorder();token=active_recorder.set(recorder)
    try:
        with pytest.raises(ValueError,match="PRIVATE_VALIDATION_FAILED"):
            await create_analysis_graph(MockStructuredModel(),validation_client=client).ainvoke({"context":context()})
    finally:active_recorder.reset(token)
    assert recorder.calls == 3
    assert [c.args[2] for c in client.validate_private.call_args_list] == [1,2,3]


@pytest.mark.asyncio
async def test_polling_restores_same_job_after_crash_without_regeneration():
    client=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(return_value={"validationId":"job","status":"RUNNING"}),validation_status=AsyncMock(side_effect=ConnectionError()))
    recorder=Recorder();token=active_recorder.set(recorder)
    graph=create_analysis_graph(MockStructuredModel(),InMemorySaver(),client)
    config={"configurable":{"thread_id":"private-restart"}}
    try:
        with patch("nemo_agent.analysis_graph.asyncio.sleep",new=AsyncMock()):
            with pytest.raises(ConnectionError):await graph.ainvoke({"context":context()},config)
            client.validation_status.side_effect=None
            client.validation_status.return_value={"status":"SUCCEEDED","result":{"passed":True}}
            result=await graph.ainvoke(None,config)
    finally:active_recorder.reset(token)
    assert recorder.calls == 1
    assert result["validation_revision"] == 1
    assert client.validate_private.call_args_list[0] == client.validate_private.call_args_list[1]


def test_plan_replacements_remain_in_snapshot_and_cover_every_selected_topic():
    catalog=[{"problemNo":i,"tags":["链表"]} for i in range(1,8)]+[{"problemNo":8,"tags":["树"]}]
    plan={"items":[{"dayNumber":i,"orderNumber":1,"problemNo":999,"reason":"不合法题号"} for i in range(1,8)]}
    normalized=normalize_plan_items(copy.deepcopy(plan),{"request":{"days":7,"tags":["链表","树"],"inputVersion":"CATALOG_V2"},"catalog":catalog})
    numbers=[i["problemNo"] for i in normalized["items"]]
    assert set(numbers)==set(range(1,9)) and len(numbers)==len(set(numbers))


def test_short_catalog_never_uses_unrelated_questions():
    with pytest.raises(ValueError,match="PLAN_CATALOG_EXHAUSTED"):
        normalize_plan_items({"items":[]},{"request":{"days":7},"catalog":[{"problemNo":1,"tags":["链表"]}]})


@pytest.mark.asyncio
async def test_unsupported_goal_returns_explicit_clarification_without_repair_or_sandbox():
    class RefusalModel:
        def with_structured_output(self, *args, **kwargs):return self
        async def ainvoke(self, messages):return {"parsed":PrivateGenerationResponse(cannotGenerateReason="请说明要训练的操作和输入输出，当前协议不支持在线多人交互。"),"raw":None}
    client=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock())
    recorder=Recorder();token=active_recorder.set(recorder)
    try:
        with pytest.raises(GoalUnsupported,match="输入输出"):
            await create_analysis_graph(RefusalModel(),validation_client=client).ainvoke({"context":context()})
    finally:active_recorder.reset(token)
    assert recorder.calls==1
    client.validate_private.assert_not_awaited()


def test_adjustment_retains_done_topic_coverage_without_repeating_done_question():
    c={"request":{"inputVersion":"CATALOG_V2","days":2,"tags":["树","链表"]},"catalog":[{"problemNo":2,"tags":["链表"]}],"completedProblemNos":[1],"currentItems":[{"dayNumber":1,"orderNumber":1,"problemNo":1,"status":"DONE","tags":["树"]}]}
    result=normalize_plan_items({"items":[]},c)
    assert [i["problemNo"] for i in result["items"]]==[2]


@pytest.mark.asyncio
async def test_refusal_during_repair_uses_remaining_round_without_exposing_internal_cases():
    class RepairRefusalModel(MockStructuredModel):
        def with_structured_output(self, schema, **kwargs):
            self.output_schema=schema
            return self
        async def ainvoke(self, messages):
            if any("repairRound" in str(message.content) for message in messages):
                return {"parsed":PrivateGenerationResponse(cannotGenerateReason="内部失败细节"),"raw":None}
            return await super().ainvoke(messages)
    client=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(return_value={"status":"FAILED","result":{"passed":False,"category":"WRONG_ANSWER"}}))
    recorder=Recorder();token=active_recorder.set(recorder)
    try:
        with pytest.raises(ValueError,match="PRIVATE_VALIDATION_FAILED:SCHEMA"):
            await create_analysis_graph(RepairRefusalModel(),validation_client=client).ainvoke({"context":context()})
    finally:active_recorder.reset(token)
    assert recorder.calls==3
    assert client.validate_private.await_count==1
