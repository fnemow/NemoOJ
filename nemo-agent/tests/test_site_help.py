import json
from types import SimpleNamespace
import pytest
from pydantic import ValidationError
from nemo_agent.analysis_graph import create_analysis_graph,MockStructuredModel
from nemo_agent.schemas import SiteHelpDecision

class Capture(MockStructuredModel):
    def __init__(self):super().__init__();self.messages=[]
    def with_structured_output(self,schema,**kwargs):self.output_schema=schema;return self
    async def ainvoke(self,messages):self.messages=messages;return await super().ainvoke(messages)

@pytest.mark.asyncio
async def test_site_help_never_receives_submission_or_analysis_context():
    model=Capture()
    result=await create_analysis_graph(model).ainvoke({'context':{'taskType':'SITE_HELP','message':'怎么提交题目','history':[],
        'guideCatalog':[{'key':'submit_flow','scope':'提交题目'}],'code':'SECRET_SOURCE','report':{'likelyCause':'SECRET_REPORT'},'submissionId':42}})
    payload=json.loads(model.messages[-1].content.split('\n',1)[1])
    assert set(payload)=={'taskType','message','history','guideCatalog'}
    assert 'SECRET' not in model.messages[-1].content
    assert result['site_help']=={'kind':'HELP','sections':['submit_flow']} and 'report' not in result

@pytest.mark.parametrize('value',[
 {'kind':'HELP','sections':['solve_code']},{'kind':'HELP','sections':[]},
 {'kind':'CODE_REDIRECT','sections':['submit_flow']},
 {'kind':'HELP','sections':['submit_flow'],'text':'code answer'},
 {'kind':'HELP','sections':['submit_flow']*4},
])
def test_model_cannot_return_code_or_free_text(value):
    with pytest.raises(ValidationError):SiteHelpDecision(**value)

def test_code_and_unrelated_questions_have_no_generated_reply():
    assert SiteHelpDecision(kind='CODE_REDIRECT').sections==[]
    assert SiteHelpDecision(kind='UNSUPPORTED').sections==[]
