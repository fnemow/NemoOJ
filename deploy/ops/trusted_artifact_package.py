"""Release-owned allowlist and prevalidation requests, never derived from user/model input."""
import hashlib,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'nemo-agent'))
from nemo_agent.trusted_templates import CATALOG,build_template
from nemo_agent.redblack_topics import build_redblack_candidate
def normalized(v):
    if isinstance(v,dict):return {k:normalized(x) for k,x in sorted(v.items()) if x is not None}
    if isinstance(v,list):return [normalized(x) for x in v]
    return v
def contract(d):
    g=d['generation']
    return normalized({k:d[k] for k in ('judgeConfig','judgeCases','problemSpec','referenceSolutions')}|{'protocol':g['protocol'],'oracle':g['oracleSolution'],'templateId':g.get('templateId'),'templateVersion':g.get('templateVersion')})
def identity(d):return hashlib.sha256(json.dumps(contract(d),ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
def request(d):
    g=d['generation'];return {'question':{'title':d['title'],'content':d['contentMarkdown'],**{k:d[k] for k in ('judgeConfig','problemSpec')},'judgeCase':d['judgeCases']},'referenceSolutions':d['referenceSolutions'],'generationProtocol':g['protocol'],'oracleSolution':g['oracleSolution'],'templateId':g.get('templateId'),'templateVersion':g.get('templateVersion')}
items={key:build_template(key,{'difficulty':value[0]},'prevalidation') for key,value in CATALOG.items()}
items.update({'redblack_'+d.lower():build_redblack_candidate({'difficulty':d},'prevalidation') for d in ('EASY','MEDIUM','HARD')})
out=ROOT/'build/trusted-artifacts-20261014';out.mkdir(parents=True,exist_ok=True)
registry={'revision':'complete-proof-v1','contracts':sorted(identity(d) for d in items.values()),'contentHashes':{identity(d):hashlib.sha256(json.dumps(d['contentMarkdown'],ensure_ascii=False,separators=(',',':')).encode()).hexdigest() for d in items.values()}}
path=ROOT/'OJ-backend-microservice/OJ-backend-judge-service/src/main/resources/trusted-validation-contracts.json'
path.write_text(json.dumps(registry,indent=2),encoding='utf-8')
fixture=ROOT/'OJ-backend-microservice/OJ-backend-judge-service/src/test/resources/trusted-artifact-fixture.json'
fixture.parent.mkdir(parents=True,exist_ok=True);fixture.write_text(json.dumps(request(items['binary_first']),ensure_ascii=False),encoding='utf-8')
(out/'prevalidation-requests.json').write_text(json.dumps({k:request(d) for k,d in items.items()},ensure_ascii=False),encoding='utf-8')
(out/'identities.json').write_text(json.dumps({k:identity(d) for k,d in items.items()}),encoding='utf-8')
print(json.dumps({'auditedContracts':len(items),'output':str(out)}))
