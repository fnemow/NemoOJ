"""Run every offline exercise, including start/resume in separate processes."""
import json
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def run_all():
    commands = [[p.name] for p in sorted(ROOT.glob('lab*.py')) if p.name != 'lab06_review.py']
    commands.extend([['lab04_workflow.py', '--verdict', 'AC', '--stream'],
                     ['lab03_structured.py', '--invalid'],
                     ['lab06_review.py', 'start', '--task', 'process-demo'],
                     ['lab06_review.py', 'status', '--task', 'process-demo'],
                     ['lab06_review.py', 'resume', '--task', 'process-demo'],
                     ['lab06_review.py', 'start', '--task', 'reject-demo'],
                     ['lab06_review.py', 'resume', '--task', 'reject-demo', '--decision', 'reject'],
                     ['lab09_vector_rag.py', '--method', 'hybrid'],
                     ['lab08_rag.py', '--question', '火星种土豆']])
    for task_type in ['PLAN', 'FEEDBACK', 'INSPECTION', 'PRIVATE_PROBLEM']:
        commands.append(['lab11_nemooj_source.py', '--type', task_type])
    evidence = {'started': time.time(), 'mode': 'offline', 'commands': [], 'api_calls': 0}
    with tempfile.TemporaryDirectory(prefix='tutorial-run-') as directory:
        env = dict(os.environ, PYTHONUTF8='1', TUTORIAL_OUTPUT_DIR=directory, TUTORIAL_TRACE='false')
        for command in commands:
            result = subprocess.run([sys.executable, '-X', 'utf8', *command], cwd=ROOT,
                                    env=env, capture_output=True, text=True, encoding='utf-8', timeout=45)
            evidence['commands'].append({'command': command, 'returncode': result.returncode,
                                         'stdout': result.stdout, 'stderr': result.stderr})
            if result.returncode:
                print(json.dumps(evidence['commands'][-1], ensure_ascii=False, indent=2))
                raise RuntimeError('Exercise failed: ' + command[0])
    evidence['ended'] = time.time()
    evidence['passed'] = True
    (ROOT.parent / 'verification.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'passed': True, 'commands': len(commands), 'provider_api_calls': 0}, ensure_ascii=False))


if __name__ == '__main__':
    run_all()
