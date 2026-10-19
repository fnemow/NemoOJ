"""Verify and release only two C++ codec resources plus the topic-tag frontend."""
import copy
import gzip
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
import zipfile
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql,atomic_json,container


def run(args,timeout=240):
    process=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    if process.returncode:raise RuntimeError('Release command failed: '+args[0]+'; private output suppressed')
    return process.stdout


def probe(stage):
    meta=json.loads(run(['docker','inspect',container('core','sandbox')]))[0]
    ip=next(v['IPAddress'] for v in meta['NetworkSettings']['Networks'].values() if v['IPAddress'])
    env=dict(item.split('=',1) for item in meta['Config']['Env']);report={'variants':[]}
    solution='class Solution {public: std::vector<long long> solve(int value){return {9223372036854775807LL,-9223372036854775807LL-1};}};'
    body='''
        string kind=input.at("kind").get<string>();json actual;
        if(kind=="long")actual=nemo::Codec<vector<int64_t>>::write(solution.solve(0));
        else if(kind=="empty")actual=nemo::Codec<vector<int64_t>>::write(vector<long long>{});
        else if(kind=="nested")actual=nemo::Codec<vector<vector<int64_t>>>::write(vector<vector<long long>>{{5000000000LL},{-5000000000LL}});
        else if(kind=="bool")actual=nemo::Codec<vector<bool>>::write(vector<bool>{true,false});
        else if(kind=="strings")actual=nemo::Codec<vector<string>>::write(vector<string>{"","abc"});
        else if(kind=="int")actual=nemo::Codec<vector<int32_t>>::write(vector<int>{-2147483647-1,2147483647});
        else if(kind=="overflow")actual=nemo::Codec<vector<int32_t>>::write(solution.solve(0));
        else if(kind=="unsigned")actual=nemo::Codec<vector<int64_t>>::write(vector<uint64_t>{numeric_limits<uint64_t>::max()});
        else throw runtime_error("unknown case");
        response={{"status","OK"},{"actual",actual},{"timeMs",0}};
'''
    expected={'long':[9223372036854775807,-9223372036854775808],'empty':[],
              'nested':[[5000000000],[-5000000000]],'bool':[True,False],'strings':['','abc'],
              'int':[-2147483648,2147483647],'overflow':None,'unsigned':None}
    for runner_name in ('runner.cpp','runner-split.cpp'):
        runner=(stage/runner_name).read_text().replace('__BODY__',body)
        plan={'language':'cpp','mode':'FUNCTION','entryPoint':'main','harnessVersion':'1',
              'sourceFiles':[{'fileName':'solution.hpp','content':solution},{'fileName':'runner.cpp','content':runner},
                             {'fileName':'nodes.hpp','content':(stage/'nodes.hpp').read_text()}],
              'cases':[{'caseIndex':i,'stdin':json.dumps({'kind':kind})} for i,kind in enumerate(expected)],
              'limits':{'timeLimitMs':3000,'memoryLimitKB':262144,'outputLimitBytes':1048576}}
        request=urllib.request.Request('http://'+ip+':8090/api/v2/executeCode',data=json.dumps({'submissionId':0,'backgroundValidation':True,'executionPlan':plan}).encode(),headers={'Content-Type':'application/json','auth':env['SANDBOX_SECRET']})
        with urllib.request.urlopen(request,timeout=120) as response:result=json.load(response)
        assert result['overallStatus']==0,(runner_name,result.get('compileMessage'),result.get('systemMessage'))
        assert len(result['cases'])==len(expected)
        for case,(kind,value) in zip(result['cases'],expected.items()):
            wire=json.loads(case['stdout'])
            if value is None:assert wire['status']=='RUNTIME_ERROR',(runner_name,kind,wire)
            else:assert wire['status']=='OK' and wire['actual']==value,(runner_name,kind,wire)
        report['variants'].append({'runner':runner_name,'checks':len(expected),'passed':True})
        print(json.dumps({'codecRunner':runner_name,'checks':len(expected),'passed':True}),flush=True)
    report['passed']=True;atomic_json(stage/'codec-report.json',report)


def deploy(stage):
    assert json.loads((stage/'codec-report.json').read_text())['passed']
    assert int(mysql('SELECT COUNT(*) FROM question_submit WHERE status IN (0,1) AND isDelete=0'))==0
    assert int(mysql("SELECT COUNT(*) FROM private_validation_job WHERE status IN ('QUEUED','RUNNING')"))==0
    meta=json.loads(run(['docker','inspect',container('core','judge-service')]))[0];old_image=meta['Config']['Image']
    original=run(['docker','exec',meta['Id'],'cat','/app/service.jar'])
    replacements={'BOOT-INF/classes/harness/'+name:(stage/name).read_bytes() for name in ('runner.cpp','runner-split.cpp')}
    out=io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(original)) as previous,zipfile.ZipFile(out,'w') as new:
        for item in previous.infolist():new.writestr(copy.copy(item),replacements.get(item.filename,previous.read(item)))
    with zipfile.ZipFile(io.BytesIO(original)) as previous,zipfile.ZipFile(io.BytesIO(out.getvalue())) as new:
        assert set(previous.namelist())==set(new.namelist())
        assert all(previous.read(name)==new.read(name) for name in previous.namelist() if name not in replacements)
        assert all(new.read(name)==value for name,value in replacements.items())
    image='nemo-oj/judge-service:catalog306-codec-20261011';folder=stage/'judge-codec';folder.mkdir(exist_ok=True)
    (folder/'service.jar').write_bytes(out.getvalue());(folder/'Dockerfile').write_text('FROM '+old_image+'\nCOPY service.jar /app/service.jar\n')
    run(['docker','build','--network=none','--pull=false','-t',image,str(folder)])
    labels=meta['Config']['Labels'];envfile=Path(labels['com.docker.compose.project.environment_file'])
    assert envfile==Path('/srv/nemo-oj/deploy/.env');before_text=envfile.read_text();assert 'REGISTRATION_ENABLED=false' in before_text.splitlines()
    compose=['docker','compose','--project-name',labels['com.docker.compose.project'],'--env-file',str(envfile)]
    for path in labels['com.docker.compose.project.config_files'].split(','):
        assert Path(path).parent==envfile.parent;compose+=['-f',path]
    before=json.loads(run(compose+['config','--format','json']))
    after_text='\n'.join(line for line in before_text.splitlines() if not line.startswith('NEMO_JUDGE_IMAGE='))+'\nNEMO_JUDGE_IMAGE='+image+'\n'
    def write(text):
        fd,name=tempfile.mkstemp(dir=envfile.parent,prefix='.catalog306-');os.fchmod(fd,envfile.stat().st_mode&0o777)
        with os.fdopen(fd,'w') as stream:stream.write(text);stream.flush();os.fsync(stream.fileno())
        os.replace(name,envfile)
    try:
        write(after_text);after=json.loads(run(compose+['config','--format','json']));after['services']['judge-service']['image']=before['services']['judge-service']['image']
        assert after==before,'Unexpected configuration changes'
        run(compose+['up','-d','--no-deps','--pull','never','judge-service'])
        until=time.monotonic()+180
        while time.monotonic()<until:
            state=json.loads(run(['docker','inspect',container('core','judge-service')]))[0]['State']
            if state.get('Health',{}).get('Status')=='healthy':break
            time.sleep(3)
        else:raise RuntimeError('Judge readiness timeout')
    except Exception:
        write(before_text);run(compose+['up','-d','--no-deps','--pull','never','judge-service']);raise
    atomic_json(stage/'codec-release-report.json',{'passed':True,'image':image,'previousImage':old_image,
                 'changedJarEntries':list(replacements),'otherJarEntriesUnchanged':True,'backupsCreated':False})
    print(json.dumps({'codecDeployed':True,'image':image,'otherJarEntriesUnchanged':True}),flush=True)


def frontend(stage):
    destination=Path('/srv/nemo-oj/nemo-frontend/dist');unpack=stage/'frontend-stage';unpack.mkdir(exist_ok=True)
    with tarfile.open(stage/'frontend.tar.gz') as archive:
        for member in archive.getmembers():
            assert (unpack/member.name).resolve().is_relative_to(unpack.resolve()) and (member.isfile() or member.isdir())
        archive.extractall(unpack,filter='data')
    html=(unpack/'index.html').read_bytes();manifest=json.loads((stage/'frontend-manifest.json').read_text())
    assert hashlib.sha256(html).hexdigest()==manifest['htmlSha256']
    assert html.decode().count('鄂ICP备2026055670号-1')==1 and b'https://beian.miit.gov.cn/' in html
    all_js='\n'.join(path.read_text() for path in (unpack/'assets').glob('*.js'))
    assert all(tag in all_js for tag in ('图论','堆与贪心','前缀和与区间查询'))
    assert 'REGISTRATION_ENABLED=false' in Path('/srv/nemo-oj/deploy/.env').read_text().splitlines()
    (unpack/'index.html.gz').write_bytes(gzip.compress(html,mtime=0))
    for file in unpack.rglob('*'):
        if not file.is_file() or file.name in ('index.html','index.html.gz'):continue
        target=destination/file.relative_to(unpack);target.parent.mkdir(parents=True,exist_ok=True)
        if target.exists():assert target.read_bytes()==file.read_bytes(),'Immutable asset collision'
        else:shutil.copyfile(file,target);target.chmod(0o644)
    for name in ('index.html.gz','index.html'):
        temporary=destination/(name+'.publish-tmp');shutil.copyfile(unpack/name,temporary);temporary.chmod(0o644);os.replace(temporary,destination/name)
    with urllib.request.urlopen('https://fw-nemo.cn/?catalog306=1',timeout=20) as response:served=response.read()
    assert served==html
    report={'passed':True,'htmlSha256':manifest['htmlSha256'],'newTagOptions':3,'registrationEnabled':False,'servicesRestarted':False,'backupsCreated':False}
    atomic_json(stage/'frontend-report.json',report);print(json.dumps(report),flush=True)


if __name__=='__main__':{'probe':probe,'deploy':deploy,'frontend':frontend}[sys.argv[1]](Path(sys.argv[2]))
