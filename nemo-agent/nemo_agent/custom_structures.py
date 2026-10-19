"""Bounded record graphs. No code, eval, imports or arbitrary names from descriptors."""
import math,re
RESERVED=set('JsonObject JsonArray JsonElement JsonNull JsonPrimitive JsonParser Gson ArrayList IdentityHashMap ArrayDeque HashMap HashSet Arrays Collections NemoPlainIO Codec Result base64 json math custom_class custom_convert CUSTOM_DESCRIPTORS class public private protected static abstract return int long double boolean string bool new null true false import package using namespace std main Main Solution Runner ListNode TreeNode NemoCustomCodec NemoCustomNodes Object String System Math List Map Set type super self def lambda pass from None True False __dict__ constructor delete operator virtual template struct union enum break continue for while try catch throw throws finally switch case default void const volatile inline auto register extern signed unsigned if else do instanceof extends implements interface final synchronized native transient assert yield global nonlocal async await with as in is not and or raise except print fields read write scalar discover queue ids nodes root json result input protocol module loader spec convert CUSTOM_NODE_CLASSES'.split())
FIELD_TYPES={'int','long','double','boolean','string','ref','ref[]'}
def is_node(kind):return bool(re.fullmatch(r'node<[A-Z][A-Za-z0-9]{0,39}>',kind))
def identifier(name):return bool(re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,39}',name)) and name not in RESERVED and not name.startswith('nemo')
def validate_definitions(definitions):
    if len(definitions)>4:raise ValueError('custom structure limit')
    names=set()
    for d in definitions:
        name=d['name'];fields=d['fields']
        if not identifier(name) or not re.fullmatch(r'[A-Z][A-Za-z0-9]{0,39}',name) or name in names or not 1<=len(fields)<=12:raise ValueError('custom class')
        names.add(name);seen=set()
        for f in fields:
            if not identifier(f['name']) or f['name'] in seen or f['type'] not in FIELD_TYPES:raise ValueError('custom field')
            seen.add(f['name'])
    return names
def validate_signature(signature):
    names=validate_definitions(signature.get('dataStructures',[]))
    for t in [signature['returnType'],*[p['type'] for p in signature['parameters']]]:
        if is_node(t) and t[5:-1] not in names:raise ValueError('undeclared node type')
def scalar(v,t):
    if t in ('int','long'):
        bits=32 if t=='int' else 64
        if type(v) is not int or not -(2**(bits-1))<=v<2**(bits-1):raise ValueError('custom integer')
    elif t=='boolean':
        if type(v) is not bool:raise ValueError('custom boolean')
    elif t=='string':
        if type(v) is not str or len(v)>200 or '\n' in v or '\r' in v:raise ValueError('single-line custom string')
    elif t=='double':
        if type(v) not in (int,float) or not math.isfinite(v):raise ValueError('custom double')
def validate_graph(v,definition,require_reachable=True):
    if v is None:return
    if type(v) is not dict or set(v)!={'root','nodes'} or type(v['nodes']) is not list or not 1<=len(v['nodes'])<=200:raise ValueError('custom graph')
    nodes=v['nodes'];n=len(nodes);r=v['root']
    if type(r) is not int or not 0<=r<n:raise ValueError('custom root')
    fields=definition['fields'];edges=[]
    def ref(i):
        if type(i) is not int or not -1<=i<n:raise ValueError('custom reference')
        return i
    for node in nodes:
        if type(node) is not dict or set(node)!={f['name'] for f in fields}:raise ValueError('custom fields')
        refs=[]
        for f in fields:
            value=node[f['name']];t=f['type']
            if t=='ref':refs.append(ref(value))
            elif t=='ref[]':
                if type(value) is not list or len(value)>32:raise ValueError('custom reference list')
                refs.extend(ref(i) for i in value)
            else:scalar(value,t)
        edges.append(refs)
    seen=set();pending=[r]
    while pending:
        i=pending.pop()
        if i>=0 and i not in seen:seen.add(i);pending.extend(edges[i])
    if require_reachable and len(seen)!=n:raise ValueError('unreachable custom node')

def canonical_graph(v,definition):
    """Keep precisely the object graph visible from root; never repair invalid values/references."""
    validate_graph(v,definition,require_reachable=False)
    if v is None:return None
    fields=definition['fields'];queue=[v['root']];ids={v['root']:0};i=0
    while i<len(queue):
        node=v['nodes'][queue[i]];i+=1
        for field in fields:
            refs=[node[field['name']]] if field['type']=='ref' else node[field['name']] if field['type']=='ref[]' else []
            for ref in refs:
                if ref>=0 and ref not in ids:ids[ref]=len(queue);queue.append(ref)
    nodes=[]
    for index in queue:
        record={}
        for field in fields:
            value=v['nodes'][index][field['name']];kind=field['type']
            record[field['name']]=ids[value] if kind=='ref' and value>=0 else [ids[x] if x>=0 else -1 for x in value] if kind=='ref[]' else value
        nodes.append(record)
    return {'root':0,'nodes':nodes}
