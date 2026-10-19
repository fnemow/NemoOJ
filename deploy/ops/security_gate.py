"""Fail on unreviewed findings. Preserve raw OSV records, including mitigated ones."""
import argparse
from datetime import date, datetime, timezone
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
COMMON_GUARD = 'OJ-backend-microservice/OJ-backend-common/src/main/java/com/nemo/OJbackendcommon/websecurity/RestApiRenderingGuard.java'
SANDBOX_GUARD = 'nemo-sandbox/src/main/java/com/nemo/nemosandbox/security/RestApiRenderingGuard.java'
GATEWAY_GUARD = 'OJ-backend-microservice/OJ-backend-gateway/src/main/java/com/nemo/OJbackendgateway/config/ReactiveSurfaceGuard.java'
GUARDS = {COMMON_GUARD, SANDBOX_GUARD, GATEWAY_GUARD}
FORBIDDEN = re.compile(r'\b(?:XsltView(?:Resolver)?|FragmentsRendering|RenderingResponse|ModelAndView|RouterFunctions?|DispatcherHandler)\b')


def architecture_violations(root):
    errors = []
    for name in GUARDS:
        if not (root / name).is_file(): errors.append('Missing runtime guard: ' + name)
    if errors: return errors
    common = (root / COMMON_GUARD).read_text(encoding='utf-8').split('\n', 1)[1]
    sandbox = (root / SANDBOX_GUARD).read_text(encoding='utf-8').split('\n', 1)[1]
    if common != sandbox: errors.append('Servlet security policies diverged')
    for module in ['OJ-backend-microservice', 'nemo-sandbox']:
        for path in (root / module).rglob('*.java'):
            rel = path.relative_to(root).as_posix()
            if '/src/main/java/' not in rel or rel in GUARDS: continue
            if FORBIDDEN.search(path.read_text(encoding='utf-8')):
                errors.append('Forbidden server rendering / functional web surface: ' + rel)
    return errors


def assess(findings, register, today, control_errors):
    errors = list(control_errors)
    if register.get('status') != 'mitigated-not-version-fixed': errors.append('Invalid risk disposition')
    if date.fromisoformat(register['expiresAt']) < today: errors.append('Risk review expired')
    reviewed = {(r['dependency'], r['version'], r['id']) for r in register['records']}
    residual, unreviewed = [], []
    for finding in findings:
        key = (finding['dependency'], finding['version'], finding['id'])
        (residual if key in reviewed else unreviewed).append(finding)
    return {'passed': not errors and not unreviewed,
            'rawFindingCount': len(findings), 'mitigatedNotFixedCount': len(residual),
            'unreviewedCount': len(unreviewed), 'controlErrors': errors,
            'mitigatedNotFixed': residual, 'unreviewed': unreviewed,
            'reviewExpiresAt': register['expiresAt']}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('report', type=Path)
    parser.add_argument('--out', type=Path, default=Path('security-triage.json'))
    args = parser.parse_args()
    findings = json.loads(args.report.read_text(encoding='utf-8'))
    register = json.loads((ROOT / 'deploy/security/risk-register.json').read_text(encoding='utf-8'))
    result = assess(findings, register, datetime.now(timezone.utc).date(), architecture_violations(ROOT))
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k not in ('mitigatedNotFixed', 'unreviewed')}))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
