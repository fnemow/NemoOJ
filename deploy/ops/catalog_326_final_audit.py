"""Read-only release audit and export of evidence containing no credentials/code."""
import hashlib
import json
import os
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request
from pathlib import Path

from catalog_326_release import Api, old_fingerprint

sys.path.insert(0, '/usr/local/lib/nemo-ops')
from common import atomic_json, container, mysql
from catalog_audit import audit


def main(stage):
    sql = """SELECT HEX(JSON_OBJECT('number',p.problemNo,'title',q.title,
      'spec',q.problemSpec,'cases',q.judgeCase,'tags',q.tags,
      'answer',COALESCE(q.answer,''),'snapshotMatches',v.questionId IS NOT NULL
      AND q.problemSpec<=>v.problemSpec AND q.judgeCase<=>v.judgeCase
      AND q.content<=>v.content)) FROM question_public_number p
      JOIN question q ON q.id=p.currentQuestionId
      LEFT JOIN question_version v ON v.questionId=q.id AND v.version=q.problemVersion
      WHERE p.status='ONLINE' AND q.isDelete=0 ORDER BY p.problemNo;"""
    rows = [json.loads(bytes.fromhex(line).decode()) for line in mysql(sql).splitlines()]
    structural = audit(rows)
    atomic_json(stage / 'catalog-audit.json', structural)
    report = {'at': time.time(), 'passed': False, 'onlineProblems': len(rows),
              'continuousNumbers': [row['number'] for row in rows] == list(range(1, 327)),
              'structuralErrors': structural['errors'], 'tagCounts': structural['tagCounts'],
              'allTagsHaveAtLeast20Problems': min(structural['tagCounts'].values()) >= 20,
              'caseCounts': structural['caseCounts'], 'services': {}}
    report['coverageReview'] = [row for row in structural['findings'] if row['problemNo'] >= 307]
    report['old306Unchanged'] = old_fingerprint() == json.loads((stage / 'publish-report.json').read_text())['oldFingerprint']
    report['statisticsMismatches'] = len(mysql(Path('/srv/nemo-oj/deploy/migration/checks/submission_statistics_audit.sql').read_text()).splitlines())
    report['activeTemporaryAccounts'] = int(mysql("SELECT COUNT(*) FROM user WHERE LEFT(userAccount,11)='catalog326_' AND isDelete=0"))
    report['visibleTemporarySubmissions'] = int(mysql("SELECT COUNT(*) FROM question_submit s JOIN user u ON u.id=s.userId WHERE LEFT(u.userAccount,11)='catalog326_' AND s.isDelete=0"))
    report['activeSubmissions'] = int(mysql('SELECT COUNT(*) FROM question_submit WHERE isDelete=0 AND status IN (0,1)'))
    report['activeValidations'] = int(mysql("SELECT COUNT(*) FROM private_validation_job WHERE status IN ('QUEUED','RUNNING')"))
    report['residualSandboxContainers'] = len(subprocess.check_output(['docker', 'ps', '-aq', '--filter', 'label=nemo-oj.sandbox=true']).splitlines())
    queues = subprocess.check_output(['docker', 'exec', container('core', 'rabbitmq'), 'rabbitmqctl', 'list_queues', 'name', 'messages_ready', 'messages_unacknowledged', 'consumers', '--formatter=json']).decode()
    report['queues'] = json.loads(queues[queues.index('['):])
    for service in ('judge-service', 'question-service', 'sandbox'):
        meta = json.loads(subprocess.check_output(['docker', 'inspect', container('core', service)]))[0]
        report['services'][service] = {'image': meta['Config']['Image'], 'state': meta['State']['Status'], 'health': meta['State'].get('Health', {}).get('Status')}
    api = Api()
    report['tagFilterCounts'] = {}
    for tag in ('字符串',):
        result = api.call('/question/list/page/vo', {'current': 1, 'pageSize': 20, 'tags': [tag], 'sortField': 'problemNo', 'sortOrder': 'ascend'})
        report['tagFilterCounts'][tag] = result['total']
        assert result['total'] == structural['tagCounts'][tag], (tag, result['total'])
        assert all(tag in row['tagList'] for row in result['records'])
    # An empty request cannot create an account even if registration was opened accidentally.
    try:
        with urllib.request.urlopen(urllib.request.Request('https://fw-nemo.cn/api/user/register', data=b'{}', headers={'Content-Type': 'application/json'}), timeout=20) as response:
            report['registrationStatus'] = response.status
    except urllib.error.HTTPError as error:
        report['registrationStatus'] = error.code
    assert report['registrationStatus'] == 403
    assert report['continuousNumbers'] and report['onlineProblems'] == 326
    assert report['structuralErrors'] == 0 and report['allTagsHaveAtLeast20Problems']
    assert report['old306Unchanged'] and report['statisticsMismatches'] == 0
    assert report['activeTemporaryAccounts'] == report['visibleTemporarySubmissions'] == 0
    assert all(value['state'] == 'running' for value in report['services'].values())
    assert report['services']['judge-service']['health'] == 'healthy'
    assert report['services']['question-service']['health'] == 'healthy'
    assert report['residualSandboxContainers'] == 0
    assert all(q['messages_ready'] == q['messages_unacknowledged'] == 0 for q in report['queues'])
    assert report['tagCounts']['字符串'] == 69
    report['budgetPreflight'] = json.loads((stage / 'runtime-budget-report.json').read_text())['passed']
    report['budgetRevision'] = json.loads((stage / 'revision-report.json').read_text())
    assert report['budgetPreflight'] and report['budgetRevision']['passed'] and report['budgetRevision']['oldSnapshotUnchanged']
    # Sandbox executions and formal submissions are verified separately.
    assert json.loads((stage / 'runtime-report.json').read_text())['passed']
    assert json.loads((stage / 'judge-report.json').read_text())['passed']
    report['passed'] = True
    atomic_json(stage / 'final-audit.json', report)
    names = ['baseline-report.json', 'runtime-report.json', 'runtime-initial-report.json', 'runtime-budget-report.json', 'revision-report.json', 'publish-report.json', 'api-report.json', 'catalog-audit.json', 'judge-report.json', 'final-audit.json']
    archive_path = stage / 'catalog326-evidence.tar.gz'
    with tarfile.open(archive_path, 'w:gz') as archive:
        for name in names:
            archive.add(stage / name, arcname=name)
    os.chown(archive_path, stage.stat().st_uid, stage.stat().st_gid)
    archive_path.chmod(0o600)
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main(Path(sys.argv[1]))
