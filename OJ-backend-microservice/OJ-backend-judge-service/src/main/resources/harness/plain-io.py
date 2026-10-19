import sys as _plain_sys

def plain_element(t):
    return t[:-2] if t.endswith('[]') else t[5:-1] if t.startswith('list<') else None

def plain_read(t):
    line = _plain_sys.stdin.readline()
    if not line: raise ValueError('missing input')
    line = line.rstrip('\r\n')
    element = plain_element(t)
    nodes = t in ('list_node<int>', 'tree_node<int>')
    if element or nodes:
        n = int(line)
        if nodes and n == -1: return None
        if not 0 <= n <= 10000: raise ValueError('length')
        if t == 'tree_node<int>':
            return [None if (v := _plain_sys.stdin.readline().strip()) == 'null' else int(v) for _ in range(n)]
        return [plain_read(element or 'int') for _ in range(n)]
    if t == 'string': return line
    if t == 'boolean':
        if line not in ('true', 'false'): raise ValueError('boolean')
        return line == 'true'
    return float(line) if t == 'double' else int(line)

def plain_format(v, t):
    element = plain_element(t)
    nodes = t in ('list_node<int>', 'tree_node<int>')
    if element or nodes:
        if nodes and v is None: return '-1\n'
        return str(len(v)) + '\n' + ''.join('null\n' if nodes and x is None else plain_format(x, element or 'int') for x in v)
    if t == 'boolean': return ('true' if v else 'false') + '\n'
    if t == 'double': return format(v, '.16e') + '\n'
    return str(v) + '\n'
