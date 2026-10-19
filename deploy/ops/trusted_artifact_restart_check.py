"""Confirm persistent proof survives redeploy and changed code cannot borrow it."""
import copy,json,sys,time
from pathlib import Path
stage=Path(sys.argv[1])
# Reuse the reviewed internal HTTP client without starting its prewarm loop.
source=(stage/'trusted_artifact_prewarm.py').read_text();exec(source.split('for name,body in requests.items():')[0])
report={'samples':[],'passed':False};before=counts()
for name,body in requests.items():
    other=copy.deepcopy(body);other['question']['title']='Private display '+name
    start=time.monotonic();value=call(other)
    assert value.get('passed') and value.get('completeProofReused') and len(value['combinations'])==6
    report['samples'].append({'template':name,'seconds':round(time.monotonic()-start,3)})
assert counts()==before;report['sandboxExecutionsForWarm']=0
bad=copy.deepcopy(requests['binary_first']);bad['referenceSolutions']['java:FUNCTION']='class Solution { invalid syntax; }'
value=call(bad);assert not value.get('passed') and not value.get('completeProofReused') and value.get('category')=='COMPILE'
report['unapprovedSourceRejected']=True;report['passed']=True
atomic_json(stage/'restart-check.json',report);print(json.dumps(report),flush=True)
