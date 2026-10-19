"""Two durable authored candidates and aggregate sandbox stage deltas; no model calls."""
import json
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, '/usr/local/lib/nemo-ops')
from common import mysql
from validation_job_benchmark import main as benchmark


def metrics():
    meta = json.loads(subprocess.check_output(['docker', 'inspect', 'nemo-oj-sandbox-1']))[0]
    env = dict(v.split('=', 1) for v in meta['Config']['Env'])
    ip = next(iter(meta['NetworkSettings']['Networks'].values()))['IPAddress']
    url = 'http://' + ip + ':' + env.get('MANAGEMENT_PORT', '9190') + '/actuator/prometheus'
    with urllib.request.urlopen(url, timeout=10) as response:
        lines = response.read().decode().splitlines()
    values = {}
    for line in lines:
        match = re.fullmatch(r'(nemo_sandbox_stage_duration_seconds_(?:sum|count))\{([^}]+)\} ([0-9.eE+-]+)', line)
        if match:
            stage = re.search(r'(?:^|,)stage="([a-z_]+)"', match[2])
            if stage:
                values[stage[1] + ('.seconds' if match[1].endswith('_sum') else '.count')] = float(match[3])
    return values


def main(output):
    before = metrics()
    benchmark(output)
    after = metrics()
    delta = {key: round(value - before.get(key, 0), 6) for key, value in after.items()}
    Path(output + '.stages.json').write_text(json.dumps(delta, indent=2))
    print(json.dumps({'stageDelta': delta}), flush=True)


if __name__ == '__main__':
    main(sys.argv[1])
