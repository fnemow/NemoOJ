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

from catalog_306_release import Api, old_fingerprint

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
              'continuousNumbers': [row['number'] for row in rows] == list(range(1, 307)),
              'structuralErrors': structural['errors'], 'tagCounts': structural['tagCounts'],
              'allTagsHaveAtLeast20Problems': min(structural['tagCounts'].values()) >= 20,
              'caseCounts': structural['caseCounts'], 'services': {}}
    report['coverageReview'] = [row for row in structural['findings'] if row['problemNo'] >= 167]
    report['old166Unchanged'] = old_fingerprint() == json.loads((stage / 'publish-report.json').read_text())['oldFingerprint']
    report['statisticsMismatches'] = len(mysql(Path('/srv/nemo-oj/deploy/migration/checks/submission_statistics_audit.sql').read_text()).splitlines())
    report['activeTemporaryAccounts'] = int(mysql("SELECT COUNT(*) FROM user WHERE LEFT(userAccount,11)='catalog306_' AND isDelete=0"))
    report['visibleTemporarySubmissions'] = int(mysql("SELECT COUNT(*) FROM question_submit s JOIN user u ON u.id=s.userId WHERE LEFT(u.userAccount,11)='catalog306_' AND s.isDelete=0"))
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
    for tag in ('图论', '堆与贪心', '前缀和与区间查询'):
        result = api.call('/question/list/page/vo', {'current': 1, 'pageSize': 20, 'tags': [tag], 'sortField': 'problemNo', 'sortOrder': 'ascend'})
        report['tagFilterCounts'][tag] = result['total']
        assert result['total'] == structural['tagCounts'][tag], (tag, result['total'])
        assert all(tag in row['tagList'] for row in result['records'])
    with urllib.request.urlopen('https://fw-nemo.cn/?catalog306-final=1', timeout=20) as response:
        report['htmlSha256'] = hashlib.sha256(response.read()).hexdigest()
    assert report['htmlSha256'] == json.loads((stage / 'frontend-manifest.json').read_text())['htmlSha256']
    # An empty request cannot create an account even if registration was opened accidentally.
    try:
        with urllib.request.urlopen(urllib.request.Request('https://fw-nemo.cn/api/user/register', data=b'{}', headers={'Content-Type': 'application/json'}), timeout=20) as response:
            report['registrationStatus'] = response.status
    except urllib.error.HTTPError as error:
        report['registrationStatus'] = error.code
    assert report['registrationStatus'] == 403
    assert report['continuousNumbers'] and report['onlineProblems'] == 306
    assert report['structuralErrors'] == 0 and report['allTagsHaveAtLeast20Problems']
    assert report['old166Unchanged'] and report['statisticsMismatches'] == 0
    assert report['activeTemporaryAccounts'] == report['visibleTemporarySubmissions'] == 0
    assert all(value['state'] == 'running' for value in report['services'].values())
    assert report['services']['judge-service']['health'] == 'healthy'
    assert report['services']['question-service']['health'] == 'healthy'
    # Sandbox has no Docker healthcheck; its 35 native executions were checked separately.
    assert json.loads((stage / 'runtime-report.json').read_text())['passed']
    assert json.loads((stage / 'judge-report.json').read_text())['passed']
    report['passed'] = True
    atomic_json(stage / 'final-audit.json', report)
    names = ['runtime-report.json', 'publish-report.json', 'api-report.json', 'catalog-audit.json', 'codec-report.json', 'codec-release-report.json', 'frontend-report.json', 'judge-report.json', 'final-audit.json']
    archive_path = stage / 'catalog306-evidence.tar.gz'
    with tarfile.open(archive_path, 'w:gz') as archive:
        for name in names:
            archive.add(stage / name, arcname=name)
    os.chown(archive_path, stage.stat().st_uid, stage.stat().st_gid)
    archive_path.chmod(0o600)
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main(Path(sys.argv[1]))
