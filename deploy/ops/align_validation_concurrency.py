"""Align validation fan-out with the deployed background limit. One effective setting only."""
import json
import subprocess
import sys
import time
import urllib.request
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql

def run(args):return subprocess.check_output(args,stderr=subprocess.DEVNULL,timeout=180)
def meta():return json.loads(run(['docker','inspect','nemo-oj-judge-service-1']))[0]

def main():
    assert mysql("SELECT COUNT(*) FROM ai_task WHERE status IN ('QUEUED','RUNNING')")=='0'
    assert mysql("SELECT COUNT(*) FROM question_submit WHERE status IN (0,1) AND isDelete=0")=='0'
    old=meta();labels=old['Config']['Labels'];files=list(dict.fromkeys(labels['com.docker.compose.project.config_files'].split(',')))
    command=['docker','compose','--project-name','nemo-oj','--env-file','/srv/nemo-oj/deploy/.env']
    for name in files:command+=['-f',name]
    before=json.loads(run(command+['config','--format','json']))
    assert before['services']['sandbox']['environment']['SANDBOX_BACKGROUND_MAX_CONCURRENT']=='1'
    path=next(Path(name) for name in reversed(files) if '\n  judge-service:' in Path(name).read_text())
    original=path.read_text();lines=original.splitlines();start=lines.index('  judge-service:');end=len(lines)
    for i in range(start+1,len(lines)):
        if lines[i].startswith('  ') and not lines[i].startswith('   ') and lines[i].strip():end=i;break
    block=lines[start:end];key='AI_PRIVATE_VALIDATION_COMBINATION_CONCURRENCY'
    existing=[i for i,line in enumerate(block) if line.strip().startswith(key+':')]
    if existing:
        assert len(existing)==1
        lines[start+existing[0]]='      '+key+': "1"'
    elif '    environment:' in block:
        index=start+block.index('    environment:')+1
        lines.insert(index,'      '+key+': "1"')
    else:lines[start+1:start+1]=['    environment:','      '+key+': "1"']
    try:
        path.write_text('\n'.join(lines)+'\n');after=json.loads(run(command+['config','--format','json']))
        expected=json.loads(json.dumps(before));expected['services']['judge-service']['environment'][key]='1'
        assert after==expected
        run(command+['up','-d','--no-deps','--pull','never','judge-service'])
        deadline=time.monotonic()+180
        while time.monotonic()<deadline:
            current=meta()
            if current['State'].get('Health',{}).get('Status')=='healthy':break
            time.sleep(3)
        else:raise RuntimeError('Judge health timeout')
        values=dict(v.split('=',1) for v in current['Config']['Env']);assert values[key]=='1'
        print(json.dumps({'passed':True,'backgroundSlots':1,'validationConcurrency':1,'imageUnchanged':old['Config']['Image']==current['Config']['Image'],'onlyOneEffectiveSettingChanged':True}),flush=True)
    except Exception:
        path.write_text(original);run(command+['up','-d','--no-deps','--pull','never','judge-service']);raise RuntimeError('Scheduling deployment rolled back') from None

if __name__=='__main__':main()
