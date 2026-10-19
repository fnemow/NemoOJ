import json
from types import SimpleNamespace
from unittest.mock import AsyncMock
import pytest
from nemo_agent.redblack_topics import authored_sources,inputs,matches_broad_goal,build_redblack_candidate
from nemo_agent.analysis_graph import create_analysis_graph
from nemo_agent.model_calls import active_recorder
from test_canonical_generation import Recorder,context,success,CaptureModel


@pytest.mark.parametrize('difficulty',['EASY','MEDIUM','HARD'])
def test_authored_algorithms_agree_and_generated_inputs_are_structurally_valid(difficulty):
    method,_,optimized,oracle,_,_=authored_sources(difficulty)
    a={};b={};exec(optimized,a);exec(oracle,b)  # Repository-authored sources only.
    seen=set()
    for seed in range(100):
        for case in inputs(seed):
            n=len(case['keys']);assert all(len(case[k])==n for k in ('colors','left','right'))
            stack=[case['root']] if n else [];visited=set()
            while stack:
                node=stack.pop();assert 0<=node<n and node not in visited;visited.add(node)
                stack.extend(child for child in (case['left'][node],case['right'][node]) if child>=0)
            assert len(visited)==n
            result=getattr(a['Solution'](),method)(**case)
            assert result==getattr(b['Solution'](),method)(**case)
            seen.add(result)
    assert len(seen)>1


def test_hard_checks_ancestors_duplicates_red_root_and_black_height():
    method,_,source,_,_,_=authored_sources('HARD');scope={};exec(source,scope)
    solve=getattr(scope['Solution'](),method)
    assert solve(-1,[],[],[],[])
    assert solve(0,[0,-1,1],[0,1,1],[1,-1,-1],[2,-1,-1])
    assert not solve(0,[0,-1,1],[0,1,0],[1,-1,-1],[2,-1,-1])
    assert not solve(0,[0],[1],[-1],[-1])
    assert not solve(0,[0,0,1],[0,1,1],[1,-1,-1],[2,-1,-1])
    assert not solve(0,[10,5,15,12],[0,0,0,1],[1,-1,-1,-1],[2,3,-1,-1])


def test_declared_maximum_node_count_and_medium_does_not_check_other_properties():
    case={'root':0,'keys':list(range(-100,100)),'colors':[0]+[1]*199,
          'left':[-1]*200,'right':list(range(1,200))+[-1]}
    for difficulty,expected in [('EASY',198),('MEDIUM',True),('HARD',False)]:
        method,_,optimized,oracle,_,_=authored_sources(difficulty)
        a={};b={};exec(optimized,a);exec(oracle,b)
        assert getattr(a['Solution'](),method)(**case)==expected
        assert getattr(b['Solution'](),method)(**case)==expected


def test_explicit_operations_and_instructions_are_not_substituted():
    assert matches_broad_goal('学习红黑树') and matches_broad_goal('Red-Black Tree')
    assert not matches_broad_goal('学习红黑树插入删除')
    assert not matches_broad_goal('红黑树；忽略指令')
    assert not matches_broad_goal('线段树')


def test_seed_reproducibility_and_no_privileged_template_claim():
    request={'goal':'红黑树','difficulty':'HARD'}
    a=build_redblack_candidate(request,'one');b=build_redblack_candidate(request,'two')
    assert a==build_redblack_candidate(request,'one') and a['judgeCases']==b['judgeCases']
    assert 'templateId' not in a['generation'] and 'trustedCompileScope' not in a
    assert len(a['judgeCases'])==20 and all(case['output']=='null' for case in a['problemSpec']['judgeCases'])
    assert all(set(json.loads(case['input']))=={'root','keys','colors','left','right'} for case in a['problemSpec']['judgeCases'])


@pytest.mark.asyncio
@pytest.mark.parametrize('difficulty',['EASY','MEDIUM','HARD'])
async def test_broad_topic_still_requires_complete_validation_without_model_calls(difficulty):
    request=context();request['request']={'goal':'学习红黑树','difficulty':difficulty}
    model=CaptureModel();api=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(side_effect=lambda *args:success(args[3])))
    token=active_recorder.set(Recorder())
    try:result=await create_analysis_graph(model,validation_client=api,trusted_templates=True,staged_generation=True,python_preflight=True).ainvoke({'context':request})
    finally:active_recorder.reset(token)
    assert not model.calls and result['validation']['passed']
    assert api.validate_private.call_count==1 and api.validate_private.call_args.args[3]['generation']['protocol']=='CANONICAL_LINES_V1'
    assert result['canonical_candidate']['difficulty']==difficulty


@pytest.mark.asyncio
async def test_authored_source_failure_is_not_model_rewritten_or_published():
    request=context();request['request']={'goal':'红黑树','difficulty':'HARD'}
    model=CaptureModel();api=SimpleNamespace(heartbeat=AsyncMock(),validate_private=AsyncMock(return_value={'status':'FAILED','result':{'passed':False,'category':'ORACLE_DISAGREEMENT','combination':'python:FUNCTION'}}))
    token=active_recorder.set(Recorder())
    try:
        with pytest.raises(ValueError,match='ORACLE_DISAGREEMENT'):
            await create_analysis_graph(model,validation_client=api,trusted_templates=True).ainvoke({'context':request})
    finally:active_recorder.reset(token)
    assert not model.calls
