"""Reviewed minimum-domain cases. Generate administrator revisions, never edit snapshots.
Expected answers below are independent, hand-checkable small examples.
"""
import copy,json,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent))
import generate_algorithm_seed as base
import generate_extended_algorithm_seed as extended
import generate_hundred_plus_catalog as catalog

CASES={
 12:({'nums':[7]},1),15:({'head':[7],'k':1},[7]),32:({'head':[7],'x':6},[7]),
 39:({'lists':[[7]]},[7]),40:({'head':[1,2]},3),51:({'root':[2,1]},1),
 54:({'root':[2,1],'first':1,'second':2},2),55:({'root':[7]},True),74:({'grid':[[1]]},0),
 101:({'nums':[0,7]},7),125:({'asteroids':[-7]},[-7]),146:({'nums':[7],'target':7},1),
 147:({'nums':[1,2],'target':3},True),148:({'nums':[1,2],'target':3},1),
 149:({'nums':[1,2],'target':4},1),150:({'nums':[1,2],'limit':4},3),
 151:({'nums':[1,2],'target':3},False),152:({'nums':[1,2],'target':3},0),153:({'height':[1,2]},1),
}

def corrections():
    problems=base.PROBLEMS+extended.PROBLEMS+catalog.PROBLEMS
    result=[]
    for number in [1,*CASES]:
        original=problems[number-1];q=copy.deepcopy({k:v for k,v in original.items() if k!='solver'})
        if number==1:
            result.append({'number':1,'method':q['spec']['methodName'],'changed':False,
                           'review':'Requires at least two elements; existing two-element case covers minimum valid size.'})
            continue
        arguments,expected=CASES[number]
        solver=(base.REFERENCE_SOLVERS if number<=12 else extended.SOLVERS if number<=24 else {q['spec']['methodName']:original['solver']})[q['spec']['methodName']]
        actual=solver(**copy.deepcopy(arguments));assert actual==expected,(number,actual,expected)
        params=q['spec']['parameters'];old_cases=q['spec']['judgeCases']
        present=any(json.loads(c['input'])==arguments for c in old_cases)
        if not present:old_cases.append(base.function_case(arguments,expected))
        if number==15:stdin=str(len(arguments['head']))+' '+str(arguments['k'])+'\n'+' '.join(map(str,arguments['head']))+'\n'
        else:stdin=''.join(catalog.encode_input_value(arguments[p['name']],p['type']) for p in params)
        if number==39:
            q['content']=q['content'].replace('- `lists`（`int[][]`）：先输入行数和列数，随后逐行输入矩阵。',
                '- 第一行输入链表条数 `k`。随后对每条链表，先输入长度 `n`，再输入 `n` 个节点值；各链表长度可以不同。')
            q['content']=q['content'].replace('1 ≤ n ≤ 10^5','0 ≤ k ≤ 10^5')+'\n\n允许空链表；所有链表的节点总数不超过 `10^5`。\n'
            q['acm']=[]
            for case in old_cases:
                lists=json.loads(case['input'])['lists'];flat=json.loads(case['output'])
                text=str(len(lists))+'\n'+''.join(str(len(row))+'\n'+' '.join(map(str,row))+'\n' for row in lists)
                q['acm'].append(base.acm_case(text,catalog.encode_output(flat,q['spec']['returnType']),case['isHidden']))
        elif not present:q['acm'].append(base.acm_case(stdin,catalog.encode_output(expected,q['spec']['returnType'])))
        if number==40:q['content']=q['content'].replace('1 ≤ n ≤ 10^5','2 ≤ n ≤ 10^5')+'\n\n链表长度保证为偶数。\n'
        if number==51:q['content']=q['content'].replace('1 ≤ 节点数 ≤ 10^5','2 ≤ 节点数 ≤ 10^5')
        if number==54:q['content']+='\n\n`first` 与 `second` 为树中存在的两个不同节点值。\n'
        if number==55:q['content']=q['content'].replace('输入树满足二叉搜索树性质','输入为一般二叉树，不预先保证二叉搜索树性质')
        if number==101:q['content']=q['content'].replace('1 ≤ n ≤ 10^5','2 ≤ n ≤ 10^5')+'\n\n数组至少包含两个元素，选择两个不同下标。\n'
        changed=(q['content']!=original['content'] or q['acm']!=original['acm'] or q['spec']!=original['spec'])
        result.append({'number':number,'method':q['spec']['methodName'],'changed':changed,
                       'review':'Reviewed minimum legal domain; distinct/even-length constraints respected.',
                       'content':q['content'],'acm':q['acm'],'spec':q['spec']})
    return result

def apply_reviewed_cases(problems,start_number):
    """Apply reviewed data to generated seed files only; never write a database."""
    changes={item['number']:item for item in corrections() if item['changed']}
    for number,problem in enumerate(problems,start_number):
        if number in changes:
            for field in ['content','acm','spec']:problem[field]=copy.deepcopy(changes[number][field])

if __name__=='__main__':
    data=corrections();Path(sys.argv[1]).write_text(json.dumps(data,ensure_ascii=False),encoding='utf-8')
    print(json.dumps({'reviewed':len(data),'revisions':sum(r['changed'] for r in data),'expectedAnswersChecked':len(CASES)}))
