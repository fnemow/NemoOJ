"""Reproducible string-only append release, numbers 307--326."""
import copy
import hashlib
import io
import json
import sys
from collections import Counter
from pathlib import Path
from catalog_326_strings import load
from catalog_306_common import reference_code


def main():
    problems=load();root=Path(__file__).parents[2];directory=Path(__file__).parent
    from generate_algorithm_seed import PROBLEMS as first
    from generate_extended_algorithm_seed import PROBLEMS as second
    from generate_hundred_plus_catalog import PROBLEMS as third
    old=first+second+third+json.loads((directory/'catalog_306_release.json').read_text(encoding='utf-8'))
    assert len(old)==306 and len(problems)==20
    assert not {p['title'] for p in old}&{p['title'] for p in problems}
    assert not {p['spec']['methodName'] for p in old}&{p['spec']['methodName'] for p in problems}
    checks=0
    for p in problems:
        assert len(p['content'])<=8192 and len(p['title'])<=80
        assert len(p['acm'])==len(p['spec']['judgeCases'])>=8
        # Keep a conservative margin for compile + per-case timeout overhead;
        # release preparation also checks the actual deployed runtime policies.
        assert len(p['acm'])<=28, 'Split or reduce redundant cases before publishing'
        assert sum(not c['isHidden'] for c in p['acm'])==2
        for field in ('acm','spec'):
            assert len(json.dumps(p[field],ensure_ascii=False,separators=(',',':')).encode())<=63000,(p['title'],field)
        acm=compile(reference_code(p,'ACM'),'main.py','exec');scope={};exec(reference_code(p,'FUNCTION'),scope)
        for c,f in zip(p['acm'],p['spec']['judgeCases']):
            old_in,old_out=sys.stdin,sys.stdout
            try:
                sys.stdin=io.StringIO(c['input']);sys.stdout=io.StringIO();exec(acm,{})
                assert sys.stdout.getvalue()==c['output'],p['title']
            finally:sys.stdin,sys.stdout=old_in,old_out
            args=json.loads(f['input']);actual=getattr(scope['Solution'](),p['spec']['methodName'])(**copy.deepcopy(args))
            assert actual==json.loads(f['output']),p['title'];checks+=2
    release=[];manifest=[];references=[];bundles=[]
    for number,p in enumerate(problems,307):
        item={key:p[key] for key in ('title','tags','content','answer','acm','spec','group')}
        item.update(problemNo=number,judgeConfig={'timeLimit':3000,'memoryLimit':262144,'outputLimitBytes':1048576})
        release.append(item);manifest.append({'problemNo':number,'title':p['title'],'tags':p['tags'],'methodName':p['spec']['methodName'],'casesPerMode':len(p['acm'])})
        references.append({'problemNo':number,'ACM':reference_code(p,'ACM'),'FUNCTION':reference_code(p,'FUNCTION')})
    for start in range(0,20,4):
        selected=problems[start:start+4]
        fixtures=[{'code':reference_code(p,'ACM'),'functionCode':reference_code(p,'FUNCTION'),'method':p['spec']['methodName'],'cases':p['acm'],'functionCases':p['spec']['judgeCases']} for p in selected]
        script='import io,sys,json\nfixtures=json.loads('+repr(json.dumps(fixtures,ensure_ascii=False))+')\nchecks=0\n'
        script+='''for problem in fixtures:
    program=compile(problem['code'],'main.py','exec');scope={};exec(problem['functionCode'],scope)
    for case,function in zip(problem['cases'],problem['functionCases']):
        old_in,old_out=sys.stdin,sys.stdout
        try:
            sys.stdin=io.StringIO(case['input']);sys.stdout=io.StringIO();exec(program,{})
            assert sys.stdout.getvalue()==case['output'],problem['method']
        finally:sys.stdin,sys.stdout=old_in,old_out
        assert getattr(scope['Solution'](),problem['method'])(**json.loads(function['input']))==json.loads(function['output']),problem['method']
        checks+=2
print('PASS '+str(checks))
'''
        assert len(script.encode())<=524288
        bundles.append({'firstProblemNo':307+start,'problemCount':4,'code':script,'expected':'PASS '+str(sum(len(p['acm'])*2 for p in selected))+'\n'})
    target=root/'build/catalog-326';target.mkdir(parents=True,exist_ok=True)
    def write(path,data):path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    write(directory/'catalog_326_release.json',release)
    summary={'baseCount':306,'totalProblems':326,'newProblems':20,'publicRange':[307,326],
        'difficultyCounts':dict(Counter(p['tags'][0] for p in problems)),
        'casesPerMode':sum(len(p['acm']) for p in problems),'handCalculatedExamples':40,'referenceAndIoChecks':checks,
        'sha256':hashlib.sha256((directory/'catalog_326_release.json').read_bytes()).hexdigest(),'problems':manifest}
    write(directory/'catalog_326_manifest.json',summary);write(target/'sandbox_bundles.json',bundles);write(target/'references.json',references)
    print(json.dumps({k:v for k,v in summary.items() if k!='problems'},ensure_ascii=True))


if __name__=='__main__':main()
