import hashlib,json,subprocess,sys
def run(args):
    r=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=25)
    if r.returncode:raise RuntimeError('Audit command failed; output withheld')
    return r.stdout
agent=not bool(run(['docker','ps','-q','--filter','name=nemo-oj-question-service-1']).strip())
name='nemo-ai-nemo-agent-1' if agent else 'nemo-oj-question-service-1'
m=json.loads(run(['docker','inspect',name]))[0]
result={'image':m['Config']['Image'],'running':m['State']['Running'],'restartCount':m['RestartCount']}
if agent:
    result['baseAnalysisSha256']=hashlib.sha256(run(['docker','exec',name,'cat','/app/nemo_agent/analysis_graph.py'])).hexdigest()
else:
    sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql
    result['activeAiTasks']=int(mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')"))
    result['activeSubmissions']=int(mysql('SELECT COUNT(*) FROM question_submit WHERE isDelete=0 AND status IN (0,1)'))
print(json.dumps(result),flush=True)
