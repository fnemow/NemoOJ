CUSTOM_DESCRIPTORS = {d['name']: d['fields'] for d in json.loads(base64.b64decode('__STRUCTURES__'))}

def custom_class(name, fields):
    def initialize(self, *args, **kwargs):
        names=[f['name'] for f in fields]
        if len(args)>len(names) or any(k not in names for k in kwargs) or any(k in names[:len(args)] for k in kwargs):
            raise ValueError('node constructor')
        for i,f in enumerate(fields):
            default=None if f['type']=='ref' else [] if f['type']=='ref[]' else '' if f['type']=='string' else False if f['type']=='boolean' else 0
            setattr(self,f['name'],args[i] if i<len(args) else kwargs.get(f['name'],default))
    return type(name, (), {'__init__':initialize})

CUSTOM_NODE_CLASSES={name:custom_class(name,fields) for name,fields in CUSTOM_DESCRIPTORS.items()}

def custom_convert(value, kind, output):
    name=kind[5:-1];fields=CUSTOM_DESCRIPTORS[name];cls=CUSTOM_NODE_CLASSES[name]
    if value is None:return None
    if output:
        nodes=[];queue=[];ids={}
        def discover(node):
            if node is None:return -1
            if not isinstance(node,cls):raise ValueError('custom node class')
            if id(node) not in ids:
                if len(queue)>=200:raise ValueError('custom node limit')
                ids[id(node)]=len(queue);queue.append(node)
            return ids[id(node)]
        discover(value);i=0
        while i<len(queue):
            node=queue[i];i+=1;record={}
            for f in fields:
                v=getattr(node,f['name']);t=f['type']
                if t=='ref':v=discover(v)
                elif t=='ref[]':
                    if type(v) is not list or len(v)>32:raise ValueError('references')
                    v=[discover(ref) for ref in v]
                else:
                    v=scalar(v,t)
                    if t=='string' and len(v)>200:raise ValueError('string limit')
                record[f['name']]=v
            nodes.append(record)
        return {'root':0,'nodes':nodes}
    if type(value) is not dict or set(value)!={'root','nodes'} or type(value['nodes']) is not list or not 1<=len(value['nodes'])<=200:
        raise ValueError('custom graph')
    raw=value['nodes'];nodes=[cls() for _ in raw]
    def reference(v, nil=True):
        scalar(v,'int')
        if not (-1 if nil else 0)<=v<len(nodes):raise ValueError('node reference')
        return None if v==-1 else nodes[v]
    root=reference(value['root'],False)
    for node,record in zip(nodes,raw):
        if type(record) is not dict or set(record)!={f['name'] for f in fields}:raise ValueError('node fields')
        for f in fields:
            v=record[f['name']];t=f['type']
            if t=='ref':v=reference(v)
            elif t=='ref[]':
                if type(v) is not list or len(v)>32:raise ValueError('references')
                v=[reference(x) for x in v]
            else:
                v=scalar(v,t)
                if t=='string' and len(v)>200:raise ValueError('string limit')
            setattr(node,f['name'],v)
    if len(custom_convert(root,kind,True)['nodes'])!=len(nodes):raise ValueError('unreachable node')
    return root
