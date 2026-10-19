"""Redacted diagnostics from synthetic acceptance only; no generated source/output."""
import json,re,sys
sys.path.insert(0,'/usr/local/lib/nemo-ops');from common import mysql
raw=mysql("SET NAMES utf8mb4;SELECT HEX(v.resultJson) FROM private_validation_job v JOIN ai_task t ON t.id=v.taskId JOIN user u ON u.id=t.ownerUserId WHERE u.userName='红黑树生成验收' ORDER BY v.createTime DESC LIMIT 4")
result=[]
for line in raw.splitlines():
    data=json.loads(bytes.fromhex(line).decode())
    d=data.get('result',data)
    diagnostic=str(d.get('diagnostic',''))
    result.append({'keys':list(data),'category':d.get('category'),'combination':d.get('combination'),
                   'diagnosticLength':len(diagnostic),'diagnosticLabels':re.findall(r'(?:error|Exception|Error|expected|invalid|mismatch|syntax|Index|Key|Recursion|Type|Name|Value|NoneType|property)[A-Za-z_]*',diagnostic)[:20],
                   'failedCaseIndex':d.get('failedCaseIndex'),'caseIndex':d.get('caseIndex')})
print(json.dumps(result))
