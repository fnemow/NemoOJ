"""Compile and exercise repository-authored templates only. No user/model code."""
import json
import random
import shutil
import subprocess
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[2]
    catalog = json.loads((root / 'nemo-frontend/src/features/editor/dataStructureTemplates.json').read_text(encoding='utf-8'))
    target = root / 'build/data-structure-templates'
    target.mkdir(parents=True, exist_ok=True)
    rng = random.Random(20261014)
    java = ['UnionFind uf = new UnionFind(32);', 'FenwickTree bit = new FenwickTree(32);']
    cpp = ['UnionFind uf(32);', 'FenwickTree bit(32);']
    python = ['uf = UnionFind(32)', 'bit = FenwickTree(32)']
    sets = [{i} for i in range(32)]
    values = [0] * 32
    assertions = 0
    def check(j, c, p):
        nonlocal assertions
        java.append('if (!(' + j + ')) throw new AssertionError();')
        cpp.append('assert(' + c + ');')
        python.append('assert ' + p)
        assertions += 1
    for _ in range(300):
        a, b = rng.randrange(32), rng.randrange(32)
        sa, sb = next(s for s in sets if a in s), next(s for s in sets if b in s)
        if rng.randrange(2):
            changed = sa is not sb
            if changed:
                sa.update(sb); sets.remove(sb)
            check(f'uf.unite({a},{b}) == {str(changed).lower()}', f'uf.unite({a},{b}) == {str(changed).lower()}', f'uf.unite({a},{b}) == {changed}')
        else:
            check(f'uf.connected({a},{b}) == {str(sa is sb).lower()}', f'uf.connected({a},{b}) == {str(sa is sb).lower()}', f'uf.connected({a},{b}) == {sa is sb}')
        check(f'uf.count() == {len(sets)}', f'uf.count() == {len(sets)}', f'uf.count() == {len(sets)}')
        index, delta = rng.randrange(32), rng.randint(-10**9, 10**9)
        values[index] += delta
        java.append(f'bit.add({index},{delta}L);'); cpp.append(f'bit.add({index},{delta}LL);'); python.append(f'bit.add({index},{delta})')
        left, right = sorted((rng.randrange(33), rng.randrange(33)))
        expected = sum(values[left:right])
        check(f'bit.rangeSum({left},{right}) == {expected}L', f'bit.rangeSum({left},{right}) == {expected}LL', f'bit.rangeSum({left},{right}) == {expected}')
    check('new UnionFind(0).count() == 0 && new FenwickTree(0).prefixSum(0) == 0',
          'UnionFind(0).count() == 0 && FenwickTree(0).prefixSum(0) == 0',
          'UnionFind(0).count() == 0 and FenwickTree(0).prefixSum(0) == 0')
    java += ['boolean caught = false; try { bit.add(-1,1); } catch (IndexOutOfBoundsException e) { caught = true; } if (!caught) throw new AssertionError();',
             'caught = false; try { uf.find(32); } catch (IndexOutOfBoundsException e) { caught = true; } if (!caught) throw new AssertionError();',
             'RBNode r = new RBNode(1,true);', 'NaryNode n = new NaryNode(1), m = new NaryNode(2); n.children.add(m);',
             'GraphNode g = new GraphNode(1); g.neighbors.add(g);', 'TrieNode t = new TrieNode(), u = new TrieNode(); t.children[0] = u;']
    cpp += ['bool caught = false; try { bit.add(-1,1); } catch (const std::out_of_range&) { caught = true; } assert(caught);',
            'caught = false; try { uf.find(32); } catch (const std::out_of_range&) { caught = true; } assert(caught);',
            'RBNode r(1,true);', 'NaryNode n(1), m(2); n.children.push_back(&m);',
            'GraphNode g(1); g.neighbors.push_back(&g);', 'TrieNode t, u; t.children[0] = &u;']
    python += ['try:\n    bit.add(-1,1)\n    raise AssertionError()\nexcept IndexError:\n    pass',
               'try:\n    uf.find(32)\n    raise AssertionError()\nexcept IndexError:\n    pass',
               'r = RBNode(1,True)', 'n, m = NaryNode(1), NaryNode(2)', 'n.children.append(m)',
               'g = GraphNode(1)', 'g.neighbors.append(g)', 't, u = TrieNode(), TrieNode()', 't.children[0] = u']
    check('r.red && r.left == null && r.parent == null', 'r.red && r.left == nullptr && r.parent == nullptr', 'r.red and r.left is None and r.parent is None')
    check('n.children.size() == 1 && m.children.isEmpty()', 'n.children.size() == 1 && m.children.empty()', 'len(n.children) == 1 and m.children == []')
    check('g.neighbors.get(0) == g', 'g.neighbors[0] == &g', 'g.neighbors[0] is g')
    check('t.children[0] == u && u.children[0] == null', 't.children[0] == &u && u.children[0] == nullptr', 't.children[0] is u and u.children[0] is None')
    check('new RandomListNode(1).random == null && new DoublyListNode(1).prev == null',
          'RandomListNode(1).random == nullptr && DoublyListNode(1).prev == nullptr',
          'RandomListNode(1).random is None and DoublyListNode(1).prev is None')
    sources = {language: '\n\n'.join(item[language] for item in catalog) for language in ('java','cpp','python')}
    (target / 'TemplateSmoke.java').write_text(sources['java'] + '\npublic class TemplateSmoke { public static void main(String[] args) {\n' + '\n'.join(java) + '\nSystem.out.println("PASS");}}', encoding='utf-8')
    (target / 'smoke.cpp').write_text('#include <vector>\n#include <stdexcept>\n#include <cstddef>\n#include <cassert>\n#include <iostream>\n' + sources['cpp'] + '\nint main() {\n' + '\n'.join(cpp) + '\nstd::cout << "PASS";}', encoding='utf-8')
    (target / 'smoke.py').write_text(sources['python'] + '\n' + '\n'.join(python), encoding='utf-8')
    javac, java_bin, compiler = shutil.which('javac'), shutil.which('java'), shutil.which('g++')
    if not all((javac, java_bin, compiler)):
        raise RuntimeError('Java and C++ compilers are required; verification must not silently skip')
    def run(args):
        completed = subprocess.run(args, cwd=target, text=True, capture_output=True, timeout=60)
        if completed.returncode:
            raise RuntimeError(completed.stdout + completed.stderr)
        return completed.stdout.strip()
    run([javac, '--release', '8', '-encoding', 'UTF-8', 'TemplateSmoke.java'])
    assert run([java_bin, '-cp', '.', 'TemplateSmoke']) == 'PASS'
    run([compiler, '-std=c++17', '-Wall', '-Wextra', '-pedantic', 'smoke.cpp', '-o', 'smoke.exe'])
    assert run([str(target / 'smoke.exe')]) == 'PASS'
    exec(compile((target / 'smoke.py').read_text(encoding='utf-8'), 'authored-template-smoke', 'exec'), {})
    print(json.dumps({'templates': len(catalog), 'languages': ['Java 8', 'Python', 'C++17'], 'randomized_checks_per_language': assertions, 'boundary_and_node_checks': 'PASS'}))


if __name__ == '__main__':
    main()
