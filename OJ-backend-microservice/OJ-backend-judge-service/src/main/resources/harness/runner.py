import base64, collections, importlib.util, json, math, os, sys, time, traceback
import bisect, functools, heapq, itertools, random, re, string, typing

# Curated names live in the user module only. User definitions/imports can override them.
COMMON_NAMES = {name: module for name, module in (
    ('collections', collections), ('math', math), ('bisect', bisect),
    ('functools', functools), ('heapq', heapq), ('itertools', itertools),
    ('random', random), ('re', re), ('string', string), ('typing', typing))}
for library, names in (
    (collections, ('Counter', 'defaultdict', 'deque', 'OrderedDict', 'namedtuple')),
    (heapq, ('heapify', 'heappush', 'heappop', 'heappushpop', 'heapreplace', 'nlargest', 'nsmallest')),
    (bisect, ('bisect_left', 'bisect_right', 'insort_left', 'insort_right')),
    (functools, ('cache', 'lru_cache', 'reduce', 'cmp_to_key')),
    (itertools, ('accumulate', 'chain', 'combinations', 'permutations', 'product', 'groupby', 'pairwise')),
    (math, ('gcd', 'lcm', 'isqrt', 'sqrt', 'ceil', 'floor', 'factorial', 'comb', 'inf', 'pi')),
    (typing, ('Any', 'List', 'Dict', 'Set', 'Tuple', 'Optional', 'Deque', 'DefaultDict', 'Iterable'))):
    COMMON_NAMES.update({name: getattr(library, name) for name in names})

class ListNode:
    def __init__(self, val=0, next=None):
        self.val, self.next = val, next

class TreeNode:
    def __init__(self, val=0, left=None, right=None):
        self.val, self.left, self.right = val, left, right

MAX_NODES = 10000

def scalar(value, kind):
    if kind in ('int', 'long'):
        bits = 32 if kind == 'int' else 64
        if type(value) is not int or not -(2**(bits-1)) <= value < 2**(bits-1):
            raise ValueError('integer range/type')
    elif kind == 'double':
        if type(value) not in (int, float) or not math.isfinite(value):
            raise ValueError('finite double required')
        value = float(value)
    elif kind == 'boolean':
        if type(value) is not bool: raise ValueError('boolean required')
    elif kind == 'string':
        if type(value) is not str: raise ValueError('string required')
    elif kind == 'void':
        if value is not None: raise ValueError('void required')
    else: raise ValueError('unknown type')
    return value

def convert(value, kind, output=False):
    if kind.endswith('[]') or kind == 'list<int>':
        if type(value) is not list: raise ValueError('array required')
        element = 'int' if kind == 'list<int>' else kind[:-2]
        return [convert(v, element, output) for v in value]
    if kind not in ('list_node<int>', 'tree_node<int>'): return scalar(value, kind)
    tree = kind == 'tree_node<int>'
    if output:
        if value is None: return None
        result, seen = [], set()
        queue = collections.deque([(value, 1)])
        while queue:
            node, depth = queue.popleft()
            if node is None:
                result.append(None)
                continue
            if not isinstance(node, TreeNode if tree else ListNode): raise ValueError('node type')
            if id(node) in seen or len(seen) >= MAX_NODES or (tree and depth > 1000):
                raise ValueError('cycle or node/depth limit')
            seen.add(id(node))
            result.append(scalar(node.val, 'int'))
            if tree: queue.extend(((node.left, depth+1), (node.right, depth+1)))
            elif node.next is not None: queue.append((node.next, depth+1))
        if tree:
            while result and result[-1] is None: result.pop()
        return result
    if value is None or value == []: return None
    if type(value) is not list or len(value) > 2*MAX_NODES+1: raise ValueError('node array limit/type')
    if not tree:
        if len(value) > MAX_NODES: raise ValueError('node limit')
        head = None
        for item in reversed(value): head = ListNode(scalar(item, 'int'), head)
        return head
    if value[0] is None:
        if any(v is not None for v in value): raise ValueError('unreachable node')
        return None
    root = TreeNode(scalar(value[0], 'int'))
    queue, index, count = collections.deque([(root, 1)]), 1, 1
    while queue and index < len(value):
        node, depth = queue.popleft()
        for side in ('left', 'right'):
            if index >= len(value): break
            item = value[index]
            index += 1
            if item is not None:
                count += 1
                if count > MAX_NODES or depth >= 1000: raise ValueError('node/depth limit')
                child = TreeNode(scalar(item, 'int'))
                setattr(node, side, child)
                queue.append((child, depth+1))
    if any(v is not None for v in value[index:]): raise ValueError('unreachable node')
    return root

# Preserve the protocol descriptor, and redirect before importing user code.
protocol = os.fdopen(os.dup(1), 'w', encoding='utf-8', buffering=1)
os.dup2(2, 1)
try:
    spec = json.loads(base64.b64decode('__SPEC_BASE64__'))
    source = json.load(sys.stdin, parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite JSON')))
    if type(source) is not dict: raise ValueError('object required')
    loader = importlib.util.spec_from_file_location('solution', '/app/solution.py')
    module = importlib.util.module_from_spec(loader)
    module.__dict__.update(COMMON_NAMES)
    module.ListNode, module.TreeNode = ListNode, TreeNode
    loader.loader.exec_module(module)
    args = [convert(source[p['name']], p['type']) for p in spec['parameters']]
    start = time.monotonic_ns()
    result = getattr(module.Solution(), spec['methodName'])(*args)
    elapsed = (time.monotonic_ns() - start) // 1000000
    origin = spec.get('resultSource', 'return')
    if origin == 'return': actual = convert(result, spec['returnType'], True)
    elif origin.startswith('parameter:'):
        i = int(origin.split(':', 1)[1])
        actual = convert(args[i], spec['parameters'][i]['type'], True)
    elif origin.startswith('parameters:'):
        indexes = json.loads(origin.split(':', 1)[1])
        actual = [convert(args[i], spec['parameters'][i]['type'], True) for i in indexes]
    else: raise ValueError('invalid resultSource')
    protocol.write(json.dumps({'status':'OK', 'actual':actual, 'timeMs':elapsed}, ensure_ascii=False, allow_nan=False))
except BaseException:
    protocol.write(json.dumps({'status':'RUNTIME_ERROR', 'error':traceback.format_exc()}, ensure_ascii=False))
finally:
    protocol.flush()
