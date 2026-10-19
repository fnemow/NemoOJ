"""Read-only public template sweep across the live catalog."""
import json,sys,urllib.parse,time
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent));import cloud_acceptance
from cloud_acceptance import Api
cloud_acceptance.BASE='http://127.0.0.1:18082/api';api=Api();result={'details':0,'templates':0,'errors':[],'started':time.time()}
for number in range(1,167):
 try:
  q=api.call('/question/get/vo/by-number?problemNo='+str(number));result['details']+=1
  assert not q.get('answer') and not q.get('judgeCase')
  spec=q['problemSpec'];assert not spec.get('judgeCases')
  for language in ['java','python','cpp']:
   for mode in ['ACM','FUNCTION']:
    t=api.call('/question/code_template?'+urllib.parse.urlencode({'questionId':number,'language':language,'executionMode':mode,'problemVersion':q['problemVersion']}))
    assert t['language']==language and t['mode']==mode and t['code'].strip()
    if mode=='FUNCTION':assert spec['methodName'] in t['code']
    result['templates']+=1
 except Exception as e:result['errors'].append({'problemNo':number,'error':str(e)[:150]})
result['ended']=time.time();Path(sys.argv[1]).write_text(json.dumps(result),encoding='utf-8');print(json.dumps(result))
