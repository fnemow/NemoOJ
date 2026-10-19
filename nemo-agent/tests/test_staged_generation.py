import copy
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from langgraph.checkpoint.memory import InMemorySaver
from nemo_agent.analysis_graph import create_analysis_graph, MockStructuredModel
from nemo_agent.schemas import PrivateSpecificationResponse, PrivateImplementations, ReferencePatch, CanonicalGenerationResponse
from nemo_agent.generation_diagnostics import GenerationFailure
from nemo_agent.model_calls import active_recorder
from test_canonical_generation import proposal, context, Recorder, success


class StagedModel:
    def __init__(self, draft):
        self.draft = draft.model_dump()
        self.calls = []
        self.fail_implementation = False

    def with_structured_output(self, schema, **kwargs):
        self.schema = schema
        return self

    async def ainvoke(self, messages):
        self.calls.append(self.schema)
        if self.schema is PrivateSpecificationResponse:
            keys = ('title','tags','difficulty','contentMarkdown','signature','inputs','solutionStrategy','oracleStrategy')
            return {'parsed':{'specification':{k:self.draft[k] for k in keys}}, 'raw':None}
        if self.fail_implementation:
            raise ConnectionError('synthetic transport failure')
        if self.schema is ReferencePatch:
            return {'parsed':{'target':'java:FUNCTION','source':self.draft['javaSolution']+' // fixed'}, 'raw':None}
        return {'parsed':{k:self.draft[k] for k in ('pythonSolution','pythonOracle','javaSolution','cppSolution')}, 'raw':None}


@pytest.mark.asyncio
async def test_staged_free_generation_reuses_existing_full_publication_gate():
    model = StagedModel(await proposal())
    client = SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(side_effect=lambda *a:success(a[3])))
    token = active_recorder.set(Recorder())
    try:
        result = await create_analysis_graph(model,validation_client=client,staged_generation=True).ainvoke({'context':context()})
    finally: active_recorder.reset(token)
    assert model.calls == [PrivateSpecificationResponse, PrivateImplementations]
    assert len(result['private_problem']['referenceSolutions']) == 6
    assert client.validate_private.await_count == 1


@pytest.mark.asyncio
async def test_bad_input_contract_stops_before_generating_code_or_sandbox():
    model = StagedModel(await proposal())
    model.draft['inputs'][0] = '{"wrong":1}'
    client = SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock())
    token = active_recorder.set(Recorder())
    try:
        with pytest.raises(GenerationFailure,match='INPUT_CONTRACT'):
            await create_analysis_graph(model,validation_client=client,staged_generation=True).ainvoke({'context':context()})
    finally: active_recorder.reset(token)
    assert model.calls == [PrivateSpecificationResponse]
    client.validate_private.assert_not_awaited()


@pytest.mark.asyncio
async def test_surplus_inputs_are_normalized_without_another_model_call():
    model=StagedModel(await proposal())
    original=copy.deepcopy(model.draft['inputs'])
    model.draft['inputs'] += ['{"a":50,"b":51}']
    client=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(side_effect=lambda *a:success(a[3])))
    token=active_recorder.set(Recorder())
    try:
        result=await create_analysis_graph(model,validation_client=client,staged_generation=True).ainvoke({'context':context()})
    finally:active_recorder.reset(token)
    assert result['private_specification']['inputs']==original
    assert len(model.calls)==2 and len(result['private_problem']['judgeCases'])==20


@pytest.mark.asyncio
async def test_template_fallback_keeps_four_call_budget_including_one_repair():
    model=StagedModel(await proposal()); attempts=[]
    async def validate(*args):
        attempts.append(args)
        return {'status':'FAILED','result':{'passed':False,'category':'COMPILE','combination':'java:FUNCTION','diagnostic':str(len(attempts))}}
    client=SimpleNamespace(heartbeat=AsyncMock(),validate_private=validate)
    token=active_recorder.set(Recorder())
    try:
        with pytest.raises(ValueError,match='COMPILE'):
            await create_analysis_graph(model,validation_client=client,staged_generation=True).ainvoke({'context':context(),'template_checked':True})
    finally:active_recorder.reset(token)
    # Previous template selection + specification + implementation + one patch.
    assert len(model.calls)==3 and len(attempts)==2


@pytest.mark.asyncio
async def test_restart_does_not_repeat_completed_specification_call():
    model = StagedModel(await proposal()); model.fail_implementation = True
    client = SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(side_effect=lambda *a:success(a[3])))
    graph = create_analysis_graph(model,InMemorySaver(),client,staged_generation=True)
    cfg = {'configurable':{'thread_id':'staged-resume'}}
    token = active_recorder.set(Recorder())
    try:
        with pytest.raises(ConnectionError): await graph.ainvoke({'context':context()},cfg)
        checkpoint = await graph.aget_state(cfg)
        assert checkpoint.next == ('implement_private_problem',)
        spec = copy.deepcopy(checkpoint.values['private_specification'])
        model.fail_implementation = False
        result = await graph.ainvoke(None,cfg)
    finally: active_recorder.reset(token)
    assert model.calls.count(PrivateSpecificationResponse) == 1
    assert result['private_specification'] == spec


@pytest.mark.asyncio
async def test_repair_keeps_frozen_spec_and_does_not_repeat_initial_stages():
    model = StagedModel(await proposal()); drafts=[]
    async def validate(*args):
        drafts.append(copy.deepcopy(args[3]))
        if len(drafts)==1:
            return {'status':'FAILED','result':{'passed':False,'category':'COMPILE','combination':'java:FUNCTION','diagnostic':'missing semicolon'}}
        return success(args[3])
    client = SimpleNamespace(heartbeat=AsyncMock(),validate_private=validate)
    token = active_recorder.set(Recorder())
    try:
        result = await create_analysis_graph(model,validation_client=client,staged_generation=True).ainvoke({'context':context()})
    finally: active_recorder.reset(token)
    assert model.calls == [PrivateSpecificationResponse,PrivateImplementations,ReferencePatch]
    assert drafts[0]['problemSpec'] == drafts[1]['problemSpec']
    assert result['repair_count']==1


@pytest.mark.asyncio
async def test_schema_failure_is_classified_without_leaking_provider_text():
    model=StagedModel(await proposal()); model.draft['title']='x'
    client=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock())
    token=active_recorder.set(Recorder())
    try:
        with pytest.raises(GenerationFailure) as error:
            await create_analysis_graph(model,validation_client=client,staged_generation=True).ainvoke({'context':context()})
    finally:active_recorder.reset(token)
    assert error.value.category=='SCHEMA' and error.value.phase=='SPECIFICATION'
    assert 'title' not in str(error.value)


@pytest.mark.asyncio
async def test_development_mock_supports_enabled_staged_path():
    client=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(side_effect=lambda *a:success(a[3])))
    token=active_recorder.set(Recorder())
    try:
        result=await create_analysis_graph(MockStructuredModel(),validation_client=client,staged_generation=True).ainvoke({'context':context()})
    finally:active_recorder.reset(token)
    assert result['private_problem']['title'] and len(result['private_problem']['referenceSolutions'])==6


@pytest.mark.asyncio
async def test_compatible_provider_nested_json_is_decoded_then_strictly_validated():
    draft=(await proposal()).model_dump()
    keys=('title','tags','difficulty','contentMarkdown','signature','inputs','solutionStrategy','oracleStrategy')
    spec={k:draft[k] for k in keys}
    parsed=PrivateSpecificationResponse.model_validate({'specification':json.dumps(spec)})
    assert parsed.specification.signature.methodName=='solve'
    repaired=PrivateSpecificationResponse.model_validate({'specification':json.dumps(spec)[:-1]+',}'})
    assert repaired.specification.signature==parsed.specification.signature
    spec['inputs']=[]
    with pytest.raises(ValueError):PrivateSpecificationResponse.model_validate({'specification':json.dumps(spec)})
    with pytest.raises(ValueError):PrivateSpecificationResponse.model_validate({'specification':'[]'})
    parsed_draft=CanonicalGenerationResponse.model_validate({'draft':json.dumps(draft)})
    assert parsed_draft.draft.signature.methodName=='solve'
    draft['pythonOracle']=draft['pythonSolution']
    with pytest.raises(ValueError):CanonicalGenerationResponse.model_validate({'draft':json.dumps(draft)})
