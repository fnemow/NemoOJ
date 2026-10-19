#!/usr/bin/env python3
"""Generate a reproducible, additive 140-question release; never mutate old seeds."""
import copy
import hashlib
import io
import json
import sys
from collections import Counter
from pathlib import Path
from catalog_306_common import PROBLEMS, reference_code
import catalog_306_graph, catalog_306_dp, catalog_306_strings, catalog_306_greedy
import catalog_306_ranges, catalog_306_math, catalog_306_search

MODULES=(catalog_306_graph,catalog_306_dp,catalog_306_strings,catalog_306_greedy,
         catalog_306_ranges,catalog_306_math,catalog_306_search)


def load():
    if not PROBLEMS:
        for module in MODULES:module.register()
        # Preserve the existing >=20-per-tag taxonomy. New topic tags each have
        # twenty genuine exercises; graph/heap subtechniques remain in explanations.
        for problem in PROBLEMS:
            problem['tags']=list(dict.fromkeys('堆与贪心' if tag in ('堆','贪心') else tag
                                              for tag in problem['tags'] if tag!='并查集'))
    return PROBLEMS


def validate():
    problems=load()
    from generate_algorithm_seed import PROBLEMS as first
    from generate_extended_algorithm_seed import PROBLEMS as second
    from generate_hundred_plus_catalog import PROBLEMS as previous
    all_problems=first+second+previous+problems
    assert len(problems)==140 and len(all_problems)==306
    assert len({p['title'] for p in all_problems})==306
    assert len({p['spec']['methodName'] for p in problems})==140
    old_methods={p['spec']['methodName'] for p in first+second+previous}
    assert not old_methods.intersection(p['spec']['methodName'] for p in problems)
    assert set(Counter(p['group'] for p in problems).values())=={20}
    io_checks=0
    for p in problems:
        assert len(p['content'])<=8192 and len(p['title'])<=80
        assert len(p['acm'])==len(p['spec']['judgeCases']) and 8<=len(p['acm'])<=100
        assert sum(not c['isHidden'] for c in p['acm'])==p['examples']
        for c in p['acm']+p['spec']['judgeCases']:
            assert len(c['input'])<=65536 and len(c['output'])<=65536
        # Existing MySQL TEXT columns cap the whole serialized case/spec field,
        # not only each individual testcase. Leave room for serializer overhead.
        for field in ('acm','spec'):
            assert len(json.dumps(p[field],ensure_ascii=False,separators=(',',':')).encode())<=63000,(p['title'],field)
        acm_source=reference_code(p,'ACM');function_source=reference_code(p,'FUNCTION')
        acm=compile(acm_source,'Main.py','exec');function=compile(function_source,'Solution.py','exec')
        space={};exec(function,space)
        for c,f in zip(p['acm'],p['spec']['judgeCases']):
            incoming,outgoing=sys.stdin,sys.stdout
            try:
                sys.stdin=io.StringIO(c['input']);sys.stdout=io.StringIO()
                exec(acm,{})
                rendered=sys.stdout.getvalue()
            finally:sys.stdin,sys.stdout=incoming,outgoing
            assert rendered==c['output'],(p['title'],rendered[:100],c['output'][:100])
            args=json.loads(f['input']);expected=json.loads(f['output'])
            actual=getattr(space['Solution'](),p['spec']['methodName'])(**copy.deepcopy(args))
            assert actual==expected,p['title']
            io_checks+=2
    counts=Counter(tag for p in all_problems for tag in p['tags'])
    assert min(counts.values())>=20,counts
    return {'totalProblems':306,'newProblems':140,'publicRange':[167,306],
            'topicCounts':dict(Counter(p['group'] for p in problems)),
            'difficultyCounts':dict(Counter(p['tags'][0] for p in problems)),
            'tagCounts':dict(sorted(counts.items())),
            'handCalculatedExamples':sum(p['examples'] for p in problems),
            'acmCases':sum(len(p['acm']) for p in problems),'functionCases':sum(len(p['acm']) for p in problems),
            'localReferenceAndIoChecks':io_checks}


def main():
    summary=validate();directory=Path(__file__).parent
    release=[];manifest=[];bundles=[]
    for number,p in enumerate(load(),167):
        item={key:p[key] for key in ('title','tags','content','answer','acm','spec','group')}
        item.update(problemNo=number,judgeConfig={'timeLimit':3000,'memoryLimit':262144,'outputLimitBytes':1048576})
        release.append(item)
        manifest.append({'problemNo':number,'title':p['title'],'tags':p['tags'],'methodName':p['spec']['methodName'],
                         'acmCases':len(p['acm']),'functionCases':len(p['acm']),'group':p['group']})
    # Sandbox runtime checks in small source bundles, not thousands of public submissions.
    # Each case tests FUNCTION-like invocation and an actual stdin/stdout ACM program.
    for start in range(0,140,4):
        selected=load()[start:start+4];cases=[]
        for p in selected:
            cases.append({'code':reference_code(p,'ACM'),'functionCode':reference_code(p,'FUNCTION'),
                          'method':p['spec']['methodName'],'cases':p['acm'],'functionCases':p['spec']['judgeCases']})
        script='import io,sys,json\nfixtures=json.loads('+repr(json.dumps(cases,ensure_ascii=False))+')\nchecks=0\n'
        script+='''for problem in fixtures:
    program=compile(problem['code'],'Main.py','exec')
    space={};exec(problem['functionCode'],space)
    for case, function_case in zip(problem['cases'], problem['functionCases']):
        incoming,outgoing=sys.stdin,sys.stdout
        try:
            sys.stdin=io.StringIO(case['input']);sys.stdout=io.StringIO()
            exec(program,{})
            actual=sys.stdout.getvalue()
        finally:
            sys.stdin,sys.stdout=incoming,outgoing
        assert actual==case['output'],problem['method']
        actual=getattr(space['Solution'](),problem['method'])(**json.loads(function_case['input']))
        assert actual==json.loads(function_case['output']),problem['method']
        checks+=2
print('PASS '+str(checks))
'''
        assert len(script.encode())<=524288,(start,len(script.encode()))
        bundles.append({'firstProblemNo':167+start,'problemCount':len(selected),'code':script,
                        'expected':'PASS '+str(sum(len(p['acm'])*2 for p in selected))+'\n'})
    def write(name,value):
        (directory/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    write('catalog_306_release.json',release)
    write('catalog_306_manifest.json',{**summary,'problems':manifest})
    # Reference source remains an internal acceptance artifact, never exposed via public APIs.
    target=directory.parents[1]/'build'/'catalog-306';target.mkdir(parents=True,exist_ok=True)
    (target/'sandbox_bundles.json').write_text(json.dumps(bundles,ensure_ascii=False),encoding='utf-8')
    (target/'references.json').write_text(json.dumps([
        {'problemNo':number,'ACM':reference_code(p,'ACM'),'FUNCTION':reference_code(p,'FUNCTION')}
        for number,p in enumerate(load(),167)],ensure_ascii=False),encoding='utf-8')
    digest=hashlib.sha256((directory/'catalog_306_release.json').read_bytes()).hexdigest()
    write('catalog_306_manifest.json',{**summary,'sha256':digest,'problems':manifest})
    print(json.dumps({**summary,'sha256':digest,'sandboxBundles':len(bundles)},ensure_ascii=False))


if __name__=='__main__':main()
