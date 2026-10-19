"""Patch only the provider options in the currently deployed Agent image."""
import hashlib
import json
import subprocess
from pathlib import Path
from model_provider_switch import metadata


def main():
    meta = metadata('nemo-agent'); base = meta['Config']['Image']
    assert base == 'nemo-oj/agent:generation-v3-final2-20261011'
    code = subprocess.check_output(['docker', 'exec', meta['Id'], 'cat', '/app/nemo_agent/analysis_graph.py']).decode()
    anchor = '        return ChatOpenAI(**kwargs)'
    assert code.count(anchor) == 1 and '"enable_thinking"' not in code
    original = hashlib.sha256(code.encode()).hexdigest()
    code = code.replace('from typing import TypedDict\n', 'from typing import TypedDict\nfrom urllib.parse import urlsplit\n', 1)
    assert 'from urllib.parse import urlsplit' in code
    block = '''        elif settings.model_id.lower().startswith("qwen") and (
            urlsplit(settings.base_url).hostname or ""
        ).lower().endswith((".aliyuncs.com", ".alibabacloud.com")):
            # Qwen thinking mode rejects the forced tool_choice used by all
            # structured workflows. Non-thinking mode supports this protocol.
            kwargs["extra_body"] = {"enable_thinking": False}
'''
    code = code.replace(anchor, block + anchor, 1); compile(code, 'analysis_graph.py', 'exec')
    directory = Path('/root/.cache/nemo-model-switch-20261012/image'); directory.mkdir(mode=0o700, exist_ok=True)
    (directory / 'analysis_graph.py').write_text(code)
    (directory / 'Dockerfile').write_text('FROM ' + base + '\nCOPY --chown=10001:10001 analysis_graph.py /app/nemo_agent/analysis_graph.py\n')
    target = 'nemo-oj/agent:qwen-compatible-20261012'
    result = subprocess.run(['docker', 'build', '--network=none', '-t', target, str(directory)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    assert result.returncode == 0, 'Compatibility image build failed; output withheld'
    check = subprocess.run(['docker', 'run', '--rm', '--network=none', '--memory=256m', '--entrypoint=python', target,
        '-c', 'from nemo_agent.analysis_graph import create_model; from nemo_agent.config import Settings; m=create_model(Settings(environment="test",provider="openai",model_id="qwen3.8-flash",api_key="test-only",base_url="https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1")); assert m.extra_body=={"enable_thinking":False}; assert m.max_retries==0'], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
    assert check.returncode == 0, 'Image import/provider assertion failed; output withheld'
    path = directory.parent / 'request.json'; payload = json.loads(path.read_text()); payload['image'] = target
    path.write_text(json.dumps(payload)); path.chmod(0o600)
    report = {'passed': True, 'baseImage': base, 'newImage': target, 'baseSourceSha256': original,
              'newSourceSha256': hashlib.sha256(code.encode()).hexdigest(), 'onlyProviderCompatibilityPatched': True, 'imageProviderAssertionPassed': True}
    (directory.parent / 'image-report.json').write_text(json.dumps(report, indent=2)); print(json.dumps(report), flush=True)


if __name__ == '__main__': main()
