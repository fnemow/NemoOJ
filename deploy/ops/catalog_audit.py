"""Read-only catalog audit. Output contains findings, not hidden case contents."""
import collections,json,re,sys,time
from pathlib import Path
sys.path.insert(0,'/usr/local/lib/nemo-ops')
from common import mysql,atomic_json
SUPPORTED={'int','long','double','boolean','string','int[]','long[]','double[]','boolean[]','string[]','int[][]','list<int>','list_node<int>','tree_node<int>'}
def audit(rows):
 findings=[];titles=collections.defaultdict(list);tags=collections.Counter();totals=collections.Counter()
 def issue(n,level,message):findings.append({'problemNo':n,'level':level,'message':message})
 for q in rows:
  n=q['number'];titles[q['title'].strip()].append(n)
  try:
   spec=json.loads(q['spec']);cases=json.loads(q['cases']);labels=json.loads(q['tags']);tags.update(labels)
   function=spec.get('judgeCases',[]);params=spec.get('parameters',[]);names={p['name'] for p in params}
   if len(names)!=len(params):issue(n,'error','Duplicate parameter names')
   for p in params:
    if p.get('type') not in SUPPORTED:issue(n,'error','Unsupported parameter type')
   if spec.get('returnType') not in SUPPORTED:issue(n,'error','Unsupported return type')
   if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*',spec.get('methodName','')):issue(n,'error','Invalid method name')
   for name,items in [('ACM',cases),('FUNCTION',function)]:
    totals[name+'Cases']+=len(items)
    if len(items)<5:issue(n,'review',name+' has fewer than five cases')
    if not any(c.get('isHidden') is False for c in items):issue(n,'error',name+' has no explicitly public sample')
    if not any(c.get('isHidden') is True for c in items):issue(n,'review',name+' has no hidden case')
    seen=set()
    for c in items:
     if c['input'] in seen:issue(n,'review',name+' has duplicate input')
     seen.add(c['input'])
   values=[]
   for c in function:
    v=json.loads(c['input']);json.loads(c['output'])
    if set(v)!=names:issue(n,'error','Function case keys differ from signature')
    values.extend(v.values())
   collections_present=any(p['type'].endswith('[]') or p['type'].startswith(('list','tree')) for p in params)
   if collections_present and not any(isinstance(v,list) and len(v)<=1 for v in values):issue(n,'review','No empty/singleton collection case; confirm constraints before adding')
   if not q['answer'].strip():issue(n,'review','Reference explanation missing')
   if not q['snapshotMatches']:issue(n,'error','Current published snapshot differs from question')
  except (ValueError,TypeError,KeyError):issue(n,'error','Malformed specification/cases')
 for ids in titles.values():
  if len(ids)>1:
   for n in ids:issue(n,'review','Duplicate title')
 return {'at':time.time(),'count':len(rows),'caseCounts':dict(totals),'tagCounts':dict(tags),'errors':sum(f['level']=='error' for f in findings),'reviewCount':sum(f['level']=='review' for f in findings),'findings':findings,'writesPerformed':False,'limitation':'Structural audit; does not prove reference solution correctness or boundary coverage'}
def main():
 sql="""SELECT HEX(JSON_OBJECT('number',p.problemNo,'title',q.title,'spec',q.problemSpec,'cases',q.judgeCase,'tags',q.tags,'answer',COALESCE(q.answer,''),'snapshotMatches',v.questionId IS NOT NULL AND q.problemSpec<=>v.problemSpec AND q.judgeCase<=>v.judgeCase AND q.content<=>v.content)) FROM question_public_number p JOIN question q ON q.id=p.currentQuestionId LEFT JOIN question_version v ON v.questionId=q.id AND v.version=q.problemVersion WHERE p.status='ONLINE' AND q.isDelete=0 ORDER BY p.problemNo;"""
 result=audit([json.loads(bytes.fromhex(l).decode('utf-8')) for l in mysql(sql).splitlines()]);atomic_json(sys.argv[1],result);print(json.dumps({k:v for k,v in result.items() if k!='findings'}))
if __name__=='__main__':main()
