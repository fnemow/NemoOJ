import copy
from types import SimpleNamespace
from unittest.mock import AsyncMock
import pytest
from langgraph.checkpoint.memory import InMemorySaver
from nemo_agent.analysis_graph import create_analysis_graph, MockStructuredModel
from nemo_agent.schemas import PythonSpecificationResponse, CrossLanguageImplementations, ReferencePatch, TemplateSelection
from nemo_agent.model_calls import active_recorder
from test_canonical_generation import proposal, Recorder, success, context


class Model:
    def __init__(self,draft): self.draft=draft.model_dump();self.calls=[];self.schema=None;self.fail_cross=False
    def with_structured_output(self,schema,**kw):self.schema=schema;return self
    async def ainvoke(self,messages):
        self.calls.append(self.schema)
        if self.schema is TemplateSelection:return {'parsed':{'templateId':None,'reason':'fixed unmatched contract'},'raw':None}
        if self.schema is PythonSpecificationResponse:
            return {'parsed':{'specification':{k:v for k,v in self.draft.items() if k not in ('javaSolution','cppSolution')}},'raw':None}
        if self.schema is ReferencePatch:
            return {'parsed':{'target':'python:FUNCTION','source':self.draft['pythonSolution']+'\n# repaired'},'raw':None}
        if self.fail_cross:raise ConnectionError('fixed cross-language interruption')
        return {'parsed':{k:self.draft[k] for k in ('javaSolution','cppSolution')},'raw':None}


def client(fail_preflight=False):
    calls=[]
    async def validate(*args):
        revision,draft=args[2:];calls.append(copy.deepcopy(draft))
        if draft['generation']['protocol']=='CANONICAL_PYTHON_PREFLIGHT_V1':
            result={'passed':False,'category':'WRONG_ANSWER','combination':'python:FUNCTION','diagnostic':'fixed mismatch'} if fail_preflight and revision==1 else {'passed':True,'pythonPreflightValidated':True,'oracleValidated':True,'runtimeValidated':False}
            return {'status':'FAILED' if not result['passed'] else 'SUCCEEDED','result':result}
        return success(draft)
    return SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(side_effect=validate)),calls


@pytest.mark.asyncio
async def test_preflight_precedes_other_languages_without_extra_model_calls():
    model=Model(await proposal());api,calls=client();token=active_recorder.set(Recorder())
    try:r=await create_analysis_graph(model,validation_client=api,staged_generation=True,python_preflight=True,trusted_templates=True).ainvoke({'context':context()})
    finally:active_recorder.reset(token)
    assert model.calls==[TemplateSelection,PythonSpecificationResponse,CrossLanguageImplementations]
    assert len(calls)==2 and 'deferred' in calls[0]['referenceSolutions']['java:FUNCTION']
    assert calls[1]['generation']['protocol']=='CANONICAL_LINES_V1'
    assert r['private_problem']['referenceSolutions'].keys()=={'java:FUNCTION','python:FUNCTION','cpp:FUNCTION','java:ACM','python:ACM','cpp:ACM'}


@pytest.mark.asyncio
async def test_failed_python_can_be_repaired_before_spending_on_other_languages():
    model=Model(await proposal());api,calls=client(True);token=active_recorder.set(Recorder())
    try:r=await create_analysis_graph(model,validation_client=api,staged_generation=True,python_preflight=True,trusted_templates=True).ainvoke({'context':context()})
    finally:active_recorder.reset(token)
    assert model.calls==[TemplateSelection,PythonSpecificationResponse,ReferencePatch,CrossLanguageImplementations]
    assert len(calls)==3 and r['repair_count']==1 and r['validation_revision']==3
    assert calls[0]['problemSpec']==calls[1]['problemSpec'] and calls[0]['judgeCases']==calls[1]['judgeCases']


@pytest.mark.asyncio
async def test_bad_preflight_proof_cannot_trigger_cross_language_or_publication():
    model=Model(await proposal());api=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(return_value={'status':'SUCCEEDED','result':{'passed':True,'runtimeValidated':True}}));token=active_recorder.set(Recorder())
    try:
        with pytest.raises(ValueError,match='PREFLIGHT_PROOF'):await create_analysis_graph(model,validation_client=api,staged_generation=True,python_preflight=True).ainvoke({'context':context()})
    finally:active_recorder.reset(token)
    assert model.calls==[PythonSpecificationResponse]


@pytest.mark.asyncio
async def test_recovery_keeps_passed_python_node_and_retries_only_cross_language():
    model=Model(await proposal());model.fail_cross=True;api,calls=client();token=active_recorder.set(Recorder());graph=create_analysis_graph(model,InMemorySaver(),api,staged_generation=True,python_preflight=True)
    config={'configurable':{'thread_id':'preflight-recovery'}}
    try:
        with pytest.raises(ConnectionError):await graph.ainvoke({'context':context()},config)
        model.fail_cross=False;r=await graph.ainvoke(None,config)
    finally:active_recorder.reset(token)
    assert model.calls==[PythonSpecificationResponse,CrossLanguageImplementations,CrossLanguageImplementations]
    assert len(calls)==2 and r['validation']['passed']


@pytest.mark.asyncio
async def test_fixed_mock_supports_new_pipeline():
    api,calls=client();token=active_recorder.set(Recorder())
    try:r=await create_analysis_graph(MockStructuredModel(),validation_client=api,staged_generation=True,python_preflight=True).ainvoke({'context':context()})
    finally:active_recorder.reset(token)
    assert r['validation']['passed'] and len(calls)==2
