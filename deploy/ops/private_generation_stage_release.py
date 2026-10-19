"""Agent-only release. Secrets stay on the host; keep previous image for rollback."""
import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path


def run(args, **kwargs):
    return subprocess.run(args,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,**kwargs).stdout


def meta():
    ids=run(['docker','ps','-q','--filter','label=com.docker.compose.project=nemo-ai',
             '--filter','label=com.docker.compose.service=nemo-agent']).decode().split()
    assert len(ids)==1
    return json.loads(run(['docker','inspect',ids[0]]))[0]


def main(stage, rollback=False):
    stage=Path(stage).resolve(); old=meta()
    base=old['Config']['Image']; assert base in ('nemo-oj/agent:qwen-compatible-20261012','nemo-oj/agent:staged-generation-20261013')
    manifest=json.loads((stage/'manifest.json').read_text())
    files={'analysis_graph.py','schemas.py','config.py','worker.py','model_calls.py','generation_diagnostics.py'}
    assert set(manifest)==files
    for name,digest in manifest.items():
        assert hashlib.sha256((stage/name).read_bytes()).hexdigest()==digest
        compile((stage/name).read_text(),name,'exec')
    target='nemo-oj/agent:qwen-compatible-20261012' if rollback else 'nemo-oj/agent:staged-generation-20261013'
    (stage/'Dockerfile').write_text('FROM '+base+'\n'+''.join(
        'COPY --chown=10001:10001 '+name+' /app/nemo_agent/'+name+'\n' for name in sorted(files)))
    if not rollback:
        run(['docker','build','--network=none','--pull=false','-t',target,str(stage)],timeout=120)
        run(['docker','run','--rm','--network=none','--memory=256m','--entrypoint=python',target,'-c',
             'from nemo_agent.analysis_graph import create_analysis_graph; from nemo_agent.config import Settings; assert Settings().staged_generation_enabled'],timeout=45)
    labels=old['Config']['Labels']; env_path=Path(labels['com.docker.compose.project.working_dir'])/'.env.agent'
    original=env_path.read_text(); lines=original.splitlines(); found=False
    for i,line in enumerate(lines):
        if line.startswith('NEMO_AGENT_IMAGE='):lines[i]='NEMO_AGENT_IMAGE='+target;found=True
    assert found
    changed='\n'.join(lines)+'\n'
    config_files=list(dict.fromkeys(labels['com.docker.compose.project.config_files'].split(',')))
    command=['docker','compose','--env-file',str(env_path)]
    for name in config_files: command.extend(['-f',name])
    def write(value):
        temporary=env_path.with_name('.env.agent.staged.tmp')
        fd=os.open(temporary,os.O_CREAT|os.O_TRUNC|os.O_WRONLY,0o600)
        with os.fdopen(fd,'w') as f:f.write(value);f.flush();os.fsync(f.fileno())
        os.replace(temporary,env_path)
    before=json.loads(run(command+['config','--format','json']))
    write(changed)
    step='configuration'
    try:
        after=json.loads(run(command+['config','--format','json']))
        after['services']['nemo-agent']['image']=before['services']['nemo-agent']['image']
        assert before==after, 'Unexpected deployment configuration change'
        step='recreate'
        run(command+['up','-d','--no-deps','nemo-agent'],timeout=180)
        time.sleep(10); new=meta()
        step='running'
        assert new['Config']['Image']==target and new['RestartCount']==0
        step='environment'
        assert dict(e.split('=',1) for e in old['Config']['Env'])==dict(e.split('=',1) for e in new['Config']['Env']), 'Runtime environment changed'
        if not rollback:
            step='source-hashes'
            code="import hashlib,json;from pathlib import Path;print(json.dumps({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in Path('/app/nemo_agent').glob('*.py')}))"
            hashes=json.loads(run(['docker','exec',new['Id'],'python','-c',code]))
            assert all(hashes.get(name)==digest for name,digest in manifest.items())
        print(json.dumps({'passed':True,'oldImage':base,'newImage':target,'onlyAgentChanged':True,'privateEnvMode':oct(env_path.stat().st_mode&0o777),'files':manifest}))
    except BaseException:
        write(original)
        run(command+['up','-d','--no-deps','nemo-agent'],timeout=180)
        print(json.dumps({'rolledBack':True,'failedStep':step}))
        raise RuntimeError('Agent release failed; details withheld') from None


if __name__=='__main__':main(sys.argv[1], '--rollback' in sys.argv)
