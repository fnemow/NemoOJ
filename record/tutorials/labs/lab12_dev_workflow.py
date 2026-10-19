"""Local development pipeline: a fixed candidate, tests, bounded repair, report.
The candidate is authored in this file; no generated/untrusted code is executed.
"""
import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import TypedDict
from langgraph.graph import END, START, StateGraph
from common import output_dir, show

BUGGY = 'def find(nums, target):\n    for i in range(len(nums) - 1):\n        if nums[i] == target: return i\n    return -1\n'
FIXED = BUGGY.replace('range(len(nums) - 1)', 'range(len(nums))')
TEST = '''import unittest
from candidate import find
class SearchTests(unittest.TestCase):
    def test_empty(self): self.assertEqual(find([], 1), -1)
    def test_last(self): self.assertEqual(find([2, 4, 6], 6), 2)
    def test_missing(self): self.assertEqual(find([2, 4], 5), -1)
if __name__ == '__main__': unittest.main()
'''


class State(TypedDict, total=False):
    candidate: str
    attempts: int
    passed: bool
    test_output: str
    workdir: str
    status: str


def build_graph():
    def write(state):
        (Path(state['workdir']) / 'candidate.py').write_text(state['candidate'], encoding='utf-8')
        return {'attempts': state.get('attempts', 0) + 1}

    def test(state):
        # -B prevents stale bytecode after the candidate is replaced rapidly.
        result = subprocess.run([sys.executable, '-B', '-m', 'unittest', '-v', 'test_candidate'],
                                cwd=state['workdir'], capture_output=True, text=True, timeout=20)
        return {'passed': result.returncode == 0, 'test_output': result.stdout + result.stderr}

    def route(state):
        return 'report' if state['passed'] else 'repair' if state['attempts'] < 2 else 'fail'

    builder = StateGraph(State)
    builder.add_node('write', write)
    builder.add_node('test', test)
    builder.add_node('repair', lambda s: {'candidate': FIXED})
    builder.add_node('report', lambda s: {'status': 'READY_FOR_REVIEW'})
    builder.add_node('fail', lambda s: {'status': 'FAILED'})
    builder.add_edge(START, 'write')
    builder.add_edge('write', 'test')
    builder.add_conditional_edges('test', route, {'report': 'report', 'repair': 'repair', 'fail': 'fail'})
    builder.add_edge('repair', 'write')
    builder.add_edge('report', END)
    builder.add_edge('fail', END)
    return builder.compile()


def run():
    with tempfile.TemporaryDirectory(prefix='dev-lab-', dir=output_dir()) as directory:
        (Path(directory) / 'test_candidate.py').write_text(TEST, encoding='utf-8')
        result = build_graph().invoke({'candidate': BUGGY, 'workdir': directory, 'attempts': 0},
                                      config={'recursion_limit': 15})
    report = {'status': result['status'], 'attempts': result['attempts'],
              'passed': result['passed'], 'test_output': result['test_output'],
              'note': '离线固定修复；不修改 NemoOJ 源码，不执行部署。'}
    (output_dir() / 'dev_report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    return report


if __name__ == '__main__':
    show(run())
