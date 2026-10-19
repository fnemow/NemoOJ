import copy
import json
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from langgraph.checkpoint.memory import InMemorySaver
from nemo_agent.analysis_graph import create_analysis_graph, MockStructuredModel
from nemo_agent.schemas import CanonicalGenerationResponse, ReferencePatch
from nemo_agent.private_generation import prepare_candidate, plain_value, apply_patch
from nemo_agent.model_calls import active_recorder


async def proposal():
    return (await MockStructuredModel(CanonicalGenerationResponse).ainvoke([SimpleNamespace(content='generate\n{"taskType":"PRIVATE_PROBLEM","difficulty":"EASY"}')]))['parsed'].draft


class CaptureModel(MockStructuredModel):
    def __init__(self):super().__init__();self.calls=[]
    def with_structured_output(self,schema,**kwargs):self.output_schema=schema;return self
    async def ainvoke(self,messages):
        self.calls.append((self.output_schema,[m.content for m in messages]))
        if self.output_schema is ReferencePatch:
            payload=json.loads(messages[-1].content.split('\n',1)[1])
            return {'parsed':ReferencePatch(target=payload['allowedTargets'][0],source=payload['sources'][payload['allowedTargets'][0]]+'\n# repaired\n'),'raw':None}
        return await super().ainvoke(messages)


class Recorder:
    task_id='task';worker_id='worker'
    async def invoke(self,model,messages):return await model.ainvoke(messages)


def success(candidate):
    d=copy.deepcopy(candidate);d.pop('generation')
    for i,c in enumerate(d['problemSpec']['judgeCases']):c['output']=str(2*i+1)
    for i,c in enumerate(d['judgeCases']):c['output']=str(2*i+1)+'\n'
    for language in ('java','python','cpp'):d['referenceSolutions'][language+':ACM']='trusted-wrapper'
    return {'status':'SUCCEEDED','result':{'passed':True,'oracleValidated':True,'normalizedDraft':d}}


def context():return {'taskType':'PRIVATE_PROBLEM','deadlineEpoch':time.time()+900,'request':{'goal':'两数相加独立校验','difficulty':'EASY'}}


@pytest.mark.asyncio
async def test_inputs_have_no_model_answers_and_share_one_codec():
    draft=prepare_candidate(await proposal())
    assert len(draft['referenceSolutions'])==3
    assert all(c['output']=='' for c in draft['judgeCases'])
    assert all(c['output']=='null' for c in draft['problemSpec']['judgeCases'])
    for c,f in zip(draft['judgeCases'],draft['problemSpec']['judgeCases']):
        args=json.loads(f['input']);assert c['input']==plain_value(args['a'],'int')+plain_value(args['b'],'int')


@pytest.mark.parametrize('value,kind,expected',[(True,'boolean','true\n'),('', 'string','\n'),([[],[1,-2]],'int[][]','2\n0\n2\n1\n-2\n'),(None,'tree_node<int>','-1\n'),([1,None,2],'tree_node<int>','3\n1\nnull\n2\n')])
def test_codec_boundaries(value,kind,expected):assert plain_value(value,kind)==expected


@pytest.mark.parametrize('value,kind',[(True,'int'),(2**31,'int'),(2**63,'long'),(float('nan'),'double'),('a\nb','string'),([None,1],'tree_node<int>'),([1,None,None,2],'tree_node<int>')])
def test_invalid_input_rejected_before_sandbox(value,kind):
    with pytest.raises(ValueError):plain_value(value,kind)


@pytest.mark.asyncio
async def test_patch_only_changes_selected_source_and_is_not_in_place():
    candidate=prepare_candidate(await proposal());original=copy.deepcopy(candidate)
    changed=apply_patch(candidate,'java:FUNCTION','class Solution { public int solve(int a,int b) { return a+b; }}')
    assert candidate==original
    for key in ('contentMarkdown','judgeCases','problemSpec','generation'):assert changed[key]==original[key]
    assert changed['referenceSolutions']['cpp:FUNCTION']==original['referenceSolutions']['cpp:FUNCTION']
    with pytest.raises(ValueError):apply_patch(candidate,'oracle',candidate['referenceSolutions']['python:FUNCTION'])


@pytest.mark.asyncio
async def test_failure_sends_only_relevant_source_and_one_counterexample():
    model=CaptureModel();calls=[]
    async def validate(*args):
        calls.append(copy.deepcopy(args[3]))
        if len(calls)==1:return {'status':'FAILED','result':{'passed':False,'category':'COMPILE','combination':'java:FUNCTION','diagnostic':'line 3: missing semicolon'}}
        return success(args[3])
    client=SimpleNamespace(heartbeat=AsyncMock(),validate_private=validate)
    token=active_recorder.set(Recorder())
    try:result=await create_analysis_graph(model,validation_client=client).ainvoke({'context':context()})
    finally:active_recorder.reset(token)
    assert result['repair_count']==1 and result['validation_revision']==2
    assert len(model.calls)==2 and model.calls[1][0] is ReferencePatch
    repair=json.loads(model.calls[1][1][-1].split('\n',1)[1])
    assert set(repair['sources'])=={'java:FUNCTION'}
    assert 'judgeCases' not in repair and 'referenceSolutions' not in repair
    assert calls[0]['judgeCases']==calls[1]['judgeCases']
    assert 'generation' not in result['private_problem']
    assert len(result['private_problem']['referenceSolutions'])==6


@pytest.mark.asyncio
async def test_identical_failure_stops_instead_of_spending_another_call():
    model=CaptureModel();client=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(return_value={'status':'FAILED','result':{'passed':False,'category':'ORACLE_DISAGREEMENT','combination':'python:FUNCTION','diagnostic':'same counterexample'}}))
    token=active_recorder.set(Recorder())
    try:
        with pytest.raises(ValueError,match='ORACLE_DISAGREEMENT'):await create_analysis_graph(model,validation_client=client).ainvoke({'context':context()})
    finally:active_recorder.reset(token)
    assert len(model.calls)==2


@pytest.mark.asyncio
async def test_infrastructure_failure_and_wrapper_failure_do_not_regenerate():
    for category,combo in [('INFRASTRUCTURE',None),('COMPILE','java:ACM')]:
        model=CaptureModel();client=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(return_value={'status':'FAILED','result':{'passed':False,'category':category,'combination':combo}}))
        token=active_recorder.set(Recorder())
        try:
            with pytest.raises(ValueError):await create_analysis_graph(model,validation_client=client).ainvoke({'context':context()})
        finally:active_recorder.reset(token)
        assert len(model.calls)==1


@pytest.mark.asyncio
async def test_resume_replays_same_candidate_without_model_call():
    model=CaptureModel();client=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(side_effect=ConnectionError()))
    graph=create_analysis_graph(model,InMemorySaver(),client);cfg={'configurable':{'thread_id':'canonical-resume'}}
    token=active_recorder.set(Recorder())
    try:
        with pytest.raises(ConnectionError):await graph.ainvoke({'context':context()},cfg)
        before=copy.deepcopy(client.validate_private.call_args.args)
        client.validate_private.side_effect=lambda *args:success(args[3])
        result=await graph.ainvoke(None,cfg)
    finally:active_recorder.reset(token)
    assert len(model.calls)==1 and result['validation_revision']==1
    assert before==client.validate_private.call_args.args


@pytest.mark.asyncio
async def test_rollout_flag_does_not_reinterpret_an_existing_canonical_checkpoint():
    candidate=prepare_candidate(await proposal());model=CaptureModel()
    client=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(side_effect=lambda *args:success(args[3])))
    token=active_recorder.set(Recorder())
    try:
        result=await create_analysis_graph(model,validation_client=client,canonical_generation=False).ainvoke({'context':context(),'canonical_candidate':candidate,'private_problem':candidate,'repair_count':1,'validation':{'category':'COMPILE','combination':'java:FUNCTION','diagnostic':'missing semicolon'}})
    finally:active_recorder.reset(token)
    assert model.calls[0][0] is ReferencePatch and len(model.calls)==1
    assert result['private_problem']['inputProtocol']=='CANONICAL_LINES_V1'
