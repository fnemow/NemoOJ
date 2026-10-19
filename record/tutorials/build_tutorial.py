"""Assemble chapters with the exact lab sources and actual offline evidence.
Uses only the standard library. Never reads a real .env or production secrets.
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def code_block(match):
    relative = match.group(1)
    source = (ROOT / relative).resolve()
    if not source.is_relative_to(ROOT) or source.name == '.env':
        raise ValueError('Disallowed code source: ' + relative)
    contents = source.read_text(encoding='utf-8').rstrip()
    language = {'.py': 'python', '.json': 'json', '.md': 'markdown'}.get(source.suffix, 'text')
    if source.name == '.env.example':
        language = 'dotenv'
    # A fence longer than any embedded backtick run safely quotes source markdown.
    longest = max((len(x) for x in re.findall(r'`+', contents)), default=0)
    fence = '`' * max(3, longest + 1)
    return f'源码：`{relative}`。\n\n{fence}{language}\n{contents}\n{fence}'


def verification_block():
    evidence = json.loads((ROOT / 'verification.json').read_text(encoding='utf-8'))
    metadata = json.loads((ROOT / 'verification-versions.json').read_text(encoding='utf-8'))
    log = (ROOT / 'unit-verification.log').read_text(encoding='utf-8-sig')
    count = re.search(r'Ran (\d+) tests', log)
    if not evidence.get('passed') or any(c['returncode'] for c in evidence['commands']) or not count or not log.rstrip().endswith('OK'):
        raise ValueError('Do not publish unverified success claims.')
    lines = [
        f"验证环境：{metadata['platform']}，Python {metadata['python']}。",
        f"记录时间：{metadata['verified_at']}。",
        '',
        f"- 单元测试：{count.group(1)} 项通过。",
        f"- 独立脚本命令：{len(evidence['commands'])} 次全部退出码为 0。",
        '- 覆盖实际工具执行、Schema 拒绝、条件路由、有限修正、并行汇合、SQLite 跨进程恢复/拒绝、三种检索、无依据不生成、项目五种图任务、本地测试修复与参数更新。',
        '- 全部使用离线分支，未配置或调用供应商模型；测试进程禁止非回环 socket 连接。记录中的 api_calls=0 是离线运行方式的说明，未对供应商账单进行外部核对。',
        '- 未运行真实大模型训练；训练练习是纯 Python 字符模型。',
        '', '| 包 | 本次验证版本 |', '| --- | --- |',
    ]
    lines += [f'| `{name}` | `{version}` |' for name, version in metadata['packages'].items()]
    return '\n'.join(lines)


def validate_links(document):
    errors = []
    anchors = set(re.findall(r'<a id="([^"]+)"', document))
    # All explicit anchor links used by the navigation refer to HTML IDs.
    for target in re.findall(r'\]\(([^\s)]+)\)', document):
        if target.startswith('#'):
            if target[1:] not in anchors:
                errors.append('Missing anchor: ' + target)
        elif re.match(r'^[A-Za-z]:/', target):
            path = re.sub(r':\d+$', '', target)
            if not Path(path).is_file():
                errors.append('Missing local file: ' + path)
    if errors:
        raise ValueError('\n'.join(errors))


def main():
    chapters = sorted((ROOT / 'chapters').glob('*.md'))
    document = '\n\n---\n\n'.join(p.read_text(encoding='utf-8').strip() for p in chapters)
    document = re.sub(r'\{\{CODE:([^}]+)\}\}', code_block, document)
    document = document.replace('{{VERIFICATION}}', verification_block())
    # Native absolute Windows file links for Codex; not file:// URLs.
    document = document.replace('](/C:/', '](C:/')
    if '{{CODE:' in document or '{{VERIFICATION}}' in document:
        raise ValueError('Unexpanded tutorial marker')
    validate_links(document)
    destination = ROOT / 'AgentWorkflowTutorial.md'
    destination.write_text(document + '\n', encoding='utf-8')
    print(json.dumps({'output': str(destination), 'chapters': len(chapters),
                      'characters': len(document), 'lines': document.count('\n') + 1}, ensure_ascii=False))


if __name__ == '__main__':
    main()
