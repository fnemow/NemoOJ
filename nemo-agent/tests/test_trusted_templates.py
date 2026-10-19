import copy
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock
import pytest
from nemo_agent.trusted_templates import CATALOG, RULES, build_template, generate_inputs, validate_input, python_source
from nemo_agent.schemas import TemplateSelection
from nemo_agent.analysis_graph import create_analysis_graph, MockStructuredModel
from nemo_agent.model_calls import active_recorder
from test_canonical_generation import Recorder, success, context
from test_extended_templates import decoded, Node


@pytest.mark.parametrize("key",list(CATALOG))
def test_authored_solvers_agree_for_many_legal_cases_and_fixed_boundaries(key):
    # Only repository-authored programs are executed here, never provider output.
    optimized={'ListNode':Node,'TreeNode':Node};oracle={'ListNode':Node,'TreeNode':Node};exec(python_source(key),optimized);exec(python_source(key,True),oracle)
    for seed in range(100):
        for args in generate_inputs(key,seed):
            validate_input(key,args)
            assert getattr(optimized['Solution'](),CATALOG[key][2])(**decoded(args))==getattr(oracle['Solution'](),CATALOG[key][2])(**decoded(args))


def test_previous_counterexamples_and_structural_constraints():
    with pytest.raises(ValueError,match="rectangle"):validate_input('prefix_matrix',{'matrix':[[1,2],[3,4]],'queries':[[0,1,1,0]]})
    with pytest.raises(ValueError,match="interval"):validate_input('prefix_single',{'nums':[1,2],'left':1,'right':0})
    with pytest.raises(ValueError,match="k"):validate_input('binary_matrix_k',{'matrix':[[1]],'k':2})
    with pytest.raises(ValueError,match="sorted"):validate_input('binary_first',{'nums':[2,1],'target':1})
    scope={};exec(python_source('binary_first'),scope);assert scope['Solution']().firstIndex([0,0,0,0],0)==0
    generated=generate_inputs('binary_matrix_k',42)
    assert any((flat:=[v for row in case['matrix'] for v in row])!=sorted(flat) for case in generated)
    # A row-major index shortcut must not pass all generated kth-element cases.
    assert any([v for row in case['matrix'] for v in row][case['k']-1]!=sorted(v for row in case['matrix'] for v in row)[case['k']-1] for case in generated)


def test_deterministic_generation_and_template_difficulty_are_fixed():
    a=build_template('prefix_matrix',{'difficulty':'HARD'},'task-a');b=build_template('prefix_matrix',{'difficulty':'HARD'},'task-a');c=build_template('prefix_matrix',{'difficulty':'HARD'},'task-b')
    assert a==b and a['judgeCases']==c['judgeCases']
    assert RULES['prefix_matrix'] in a['contentMarkdown']
    with pytest.raises(ValueError):build_template('prefix_matrix',{'difficulty':'EASY'},'task')


class Selector(MockStructuredModel):
    def __init__(self,choice):super().__init__();self.choice=choice;self.calls=[]
    def with_structured_output(self,schema,**kwargs):self.output_schema=schema;return self
    async def ainvoke(self,messages):
        self.calls.append(self.output_schema)
        if self.output_schema is TemplateSelection:return {'parsed':TemplateSelection(templateId=self.choice,contextLabel='库存查询',reason='需求匹配已审核题型'),'raw':None}
        return await super().ainvoke(messages)


@pytest.mark.asyncio
async def test_template_path_requires_one_short_call_and_validation():
    model=Selector('prefix_single');client=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(side_effect=lambda *a:success(a[3])))
    token=active_recorder.set(Recorder())
    try:r=await create_analysis_graph(model,validation_client=client,trusted_templates=True).ainvoke({'context':context()})
    finally:active_recorder.reset(token)
    assert model.calls==[TemplateSelection] and r['validation']['passed']
    assert client.validate_private.call_args.args[3]['generation']['templateId']=='prefix_single'


@pytest.mark.asyncio
async def test_unsupported_goal_falls_back_without_substituting_a_template():
    model=Selector(None);client=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(side_effect=lambda *a:success(a[3])))
    token=active_recorder.set(Recorder())
    try:r=await create_analysis_graph(model,validation_client=client,trusted_templates=True).ainvoke({'context':context()})
    finally:active_recorder.reset(token)
    assert len(model.calls)==2 and 'templateId' not in r['canonical_candidate']['generation']


@pytest.mark.asyncio
async def test_failed_authored_template_is_never_rewritten_by_a_model():
    model=Selector('prefix_single');client=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(return_value={'status':'FAILED','result':{'passed':False,'category':'PROPERTY_VIOLATION','combination':'python:ORACLE'}}))
    token=active_recorder.set(Recorder())
    try:
        with pytest.raises(ValueError,match='PROPERTY_VIOLATION'):await create_analysis_graph(model,validation_client=client,trusted_templates=True).ainvoke({'context':context()})
    finally:active_recorder.reset(token)
    assert model.calls==[TemplateSelection]


@pytest.mark.asyncio
async def test_turning_off_selection_keeps_existing_template_candidate_without_a_model_call():
    candidate=build_template('prefix_single',{'difficulty':'EASY'},'task');model=Selector(None)
    client=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(side_effect=lambda *a:success(a[3])))
    token=active_recorder.set(Recorder())
    try:r=await create_analysis_graph(model,validation_client=client,trusted_templates=False).ainvoke({'context':context(),'private_problem':candidate,'canonical_candidate':candidate})
    finally:active_recorder.reset(token)
    assert model.calls==[] and r['canonical_candidate']==candidate
