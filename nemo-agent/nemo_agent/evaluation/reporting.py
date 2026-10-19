import json
from pathlib import Path


def write_report(out: Path, report: dict):
    (out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    s = report['summary']
    lines = ['# NemoOJ Agent 评测报告', '', f"- 模式：{report['mode']}；范围：{report['scope']}",
             f"- 数据集：{report['datasetSha256']}", f"- 质量结论：**{report['qualityStatus']}**",
             '- Mock 仅验证评测管道，不代表真实模型质量。组件运行不包含生产队列/租约/配额/缓存和沙箱。',
             f"- 预期 {s['expectedRuns']} 次；通过 {s['passed']}，失败 {s['failed']}，待复核 {s['pending']}，跳过/缺失 {s['skipped']}。",
             f"- 严重失败 {s['hardFailures']}；通过率 {s['passRate']}（Mock 不计算）。",
             f"- P50/P95：{s['latencyMs']['p50']} / {s['latencyMs']['p95']} ms；包括已测量的失败/超时。",
             f"- 已知成本 ${s['knownCostUsd']:.6f}；账本完整：{s['costComplete']}；调用 {s['modelCalls']} 次，未知计费 {s['unknownCalls']} 次。",
             '', '| 功能 | 通过 | 失败 | 待复核 | 跳过 |', '|---|---:|---:|---:|---:|']
    for task, values in report['byTask'].items():
        lines.append('| ' + task + ' | ' + ' | '.join(str(values.get(k, 0)) for k in ('passed', 'failed', 'pending', 'skipped')) + ' |')
    lines += ['', '## 失败/缺少证据', '']
    for row in report['cases']:
        if row['status'] != 'passed':
            missing = [c['name'] for c in row['checks'] if not c['passed']] + row['pending']
            lines.append(f"- {row['caseId']} / {row['repetition']}: {row['status']} — {', '.join(missing)}")
    (out / 'report.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')


def compare_reports(before, after):
    for field in ('formatVersion', 'datasetSha256', 'evaluatorSha256', 'mode', 'scope', 'repetitions', 'caseIds'):
        if before.get(field) != after.get(field):
            raise ValueError('reports are not comparable: ' + field)
    if before['mode'] == 'mock':
        raise ValueError('Mock reports cannot establish quality improvements')
    old = {(r['caseId'], r['repetition']): r for r in before['cases']}
    new = {(r['caseId'], r['repetition']): r for r in after['cases']}
    if set(old) != set(new) or any(old[k].get('cacheState') != new[k].get('cacheState') for k in old):
        raise ValueError('reports have different repetitions or cache conditions')
    regressions = [list(k) for k in old if old[k]['status'] == 'passed' and new[k]['status'] != 'passed']
    a, b = before['summary'], after['summary']
    return {'regressions': regressions, 'hardFailureDelta': b['hardFailures'] - a['hardFailures'],
            'passRateDelta': b['passRate'] - a['passRate'],
            'p95DeltaMs': b['latencyMs']['p95'] - a['latencyMs']['p95'] if a['latencyMs']['p95'] is not None and b['latencyMs']['p95'] is not None else None,
            'costDeltaUsd': b['knownCostUsd'] - a['knownCostUsd'] if a['costComplete'] and b['costComplete'] else None,
            'qualityComplete': all(not r['summary']['pending'] and not r['summary']['skipped'] for r in (before, after))}
