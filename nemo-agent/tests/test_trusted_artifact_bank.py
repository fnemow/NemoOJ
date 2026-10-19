from nemo_agent.trusted_templates import CATALOG,build_template
from nemo_agent.redblack_topics import build_redblack_candidate
import pytest

@pytest.mark.parametrize('key',list(CATALOG))
def test_audited_bank_is_task_independent_without_changing_private_title(key):
    request={'difficulty':CATALOG[key][0]}
    a=build_template(key,request,'task-a','用户A')
    b=build_template(key,request,'task-b','用户B')
    assert a['title']!=b['title']
    assert a['judgeCases']==b['judgeCases'] and a['problemSpec']==b['problemSpec']
    assert a['referenceSolutions']==b['referenceSolutions'] and a['generation']==b['generation']
    assert len(a['judgeCases'])==20

@pytest.mark.parametrize('difficulty',['EASY','MEDIUM','HARD'])
def test_redblack_bank_is_fixed_by_difficulty(difficulty):
    a=build_redblack_candidate({'difficulty':difficulty},'task-a')
    b=build_redblack_candidate({'difficulty':difficulty},'task-b')
    assert a==b and len(a['judgeCases'])==20
