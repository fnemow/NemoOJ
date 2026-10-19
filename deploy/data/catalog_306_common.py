"""Authoring helpers for the additive 167--306 catalog. No database side effects."""
import ast
import copy
import hashlib
import json
import random
from collections import Counter, defaultdict, deque
from pathlib import Path
from generate_algorithm_seed import acm_case, function_case, spec
from generate_hundred_plus_catalog import encode_input_value, encode_output, input_rule, output_rule

MOD = 1000000007
RNG = random.Random(30620261011)
PROBLEMS = []


def add(group, title, difficulty, tags, method, declaration, result_type, statement,
        constraints, idea, solver, examples, extra):
    """Examples carry hand-calculated answers; extras carry legal input tuples."""
    parameters = [tuple(item.split(':')) for item in declaration.split(',')]
    for arguments, expected in examples:
        actual = solver(*copy.deepcopy(arguments))
        assert actual == expected, (title, arguments, expected, actual)
    cases = [args for args, _ in examples] + list(extra)
    # Repeat neither visible samples nor hidden inputs to inflate case counts.
    unique = {json.dumps(args, ensure_ascii=False): args for args in cases}
    cases = list(unique.values())
    assert len(cases) >= 8, (title, len(cases))
    signature = ', '.join(f'{name}: {kind}' for name, kind in parameters)
    content = f'''## 题目描述\n\n{statement}\n\n## ACM 模式输入\n\n按下列参数顺序输入，各参数之间不得省略长度或维度：\n\n'''
    content += '\n'.join(input_rule(name, kind) for name, kind in parameters)
    content += f'''\n\n长度为 0 的数组仍保留一行空的元素行；矩阵允许 0 行时维度写作 `0 0`。字符串整行读取，可为空；题目约定不含换行。\n\n## ACM 模式输出\n\n{output_rule(result_type)}\n\n## 核心代码模式\n\n实现 `Solution.{method}({signature}) -> {result_type}`。\n\n## 数据范围\n\n{constraints}\n'''
    acm, functions = [], []
    for i, args in enumerate(cases):
        assert len(args) == len(parameters)
        expected = solver(*copy.deepcopy(args))
        if result_type == 'int': assert isinstance(expected, int) and -2**31 <= expected < 2**31
        if result_type == 'long': assert isinstance(expected, int) and -2**63 <= expected < 2**63
        for value, (_, kind) in zip(args, parameters):
            if kind == 'int[][]': assert not value or len({len(row) for row in value}) == 1
        arguments = dict(zip([name for name, _ in parameters], args))
        stdin = ''.join(encode_input_value(value, kind) for value, (_, kind) in zip(args, parameters))
        acm.append(acm_case(stdin, encode_output(expected, result_type), i >= len(examples)))
        functions.append(function_case(arguments, expected, i >= len(examples)))
    PROBLEMS.append(dict(title=title, tags=[difficulty, *dict.fromkeys(tags)], content=content,
        answer=idea, acm=acm, spec=spec(method, parameters, result_type, functions),
        group=group, solver=solver, examples=len(examples)))


def arrays(count=10, minimum=0, maximum=12, low=-5, high=8):
    return [([RNG.randint(low, high) for _ in range(RNG.randint(minimum, maximum))],) for _ in range(count)]


def strings(count=10, alphabet='abc', minimum=0, maximum=15):
    return [(''.join(RNG.choice(alphabet) for _ in range(RNG.randint(minimum, maximum))),) for _ in range(count)]


def reference_code(problem, mode):
    """Export definitions only: authoring registries/tests never execute in the sandbox."""
    source = Path(problem['solver'].__code__.co_filename).read_text(encoding='utf-8')
    tree = ast.parse(source)
    definitions = '\n\n'.join(ast.get_source_segment(source, node) for node in tree.body
                              if isinstance(node, (ast.FunctionDef, ast.ClassDef)) and node.name != 'register')
    header = ('from collections import Counter, defaultdict, deque\n'
              'from functools import lru_cache\nfrom bisect import bisect_left, bisect_right\n'
              'import heapq, math, re, itertools\nMOD=1000000007\n')
    signature = problem['spec']; names = [p['name'] for p in signature['parameters']]
    function = problem['solver'].__name__
    if mode == 'FUNCTION':
        return header + definitions + '\n\nclass Solution:\n    def ' + signature['methodName'] + '(self, ' + ', '.join(names) + '):\n        return ' + function + '(' + ', '.join(names) + ')\n'
    parser = '''
import sys
def read_value(kind):
    if kind == 'string': return sys.stdin.readline().rstrip('\\r\\n')
    if kind in ('int', 'long'): return int(sys.stdin.readline())
    if kind == 'int[]':
        n=int(sys.stdin.readline()); row=list(map(int,sys.stdin.readline().split()))
        assert len(row)==n
        return row
    if kind == 'string[]':
        n=int(sys.stdin.readline());return [sys.stdin.readline().rstrip('\\r\\n') for _ in range(n)]
    if kind == 'int[][]':
        n,m=map(int,sys.stdin.readline().split()); rows=[list(map(int,sys.stdin.readline().split())) for _ in range(n)]
        assert all(len(row)==m for row in rows)
        return rows
    raise ValueError(kind)
def write_value(value):
    if isinstance(value,bool): print('true' if value else 'false')
    elif isinstance(value,list): print(' '.join(map(str,value)))
    else: print(value)
'''
    return header + definitions + parser + '\nargs=[read_value(t) for t in ' + repr([p['type'] for p in signature['parameters']]) + ']\nwrite_value(' + function + '(*args))\n'
