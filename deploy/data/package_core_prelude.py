"""Package exactly the classes/resources changed by the hidden-import feature."""
import hashlib
import json
import tarfile
from pathlib import Path

root = Path(__file__).parents[2]
stage = root / 'build/core-prelude'
stage.mkdir(parents=True, exist_ok=True)
modules = {
    'judge-service': ('OJ-backend-judge-service', [
        'com/nemo/OJbackendjudgeservice/judge/harness/FunctionCodePrelude.class',
        'com/nemo/OJbackendjudgeservice/judge/harness/CanonicalPlainProtocol.class',
        'com/nemo/OJbackendjudgeservice/judge/mode/FunctionJudgeModeAdapter.class',
        'com/nemo/OJbackendjudgeservice/judge/UserDiagnosticSanitizer.class',
        'harness/runner.cpp', 'harness/runner-split.cpp', 'harness/runner.py']),
    'question-service': ('OJ-backend-question-service', [
        'com/nemo/OJbackendquestionservice/service/LanguageCapabilityService.class']),
}
entries = {}
for service, (module, names) in modules.items():
    entries[service] = []
    for name in names:
        source = root / 'OJ-backend-microservice' / module / 'target/classes' / name
        destination = stage / 'patches' / service / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(source.read_bytes())
        entries[service].append({'name': 'BOOT-INF/classes/' + name,
            'path': 'patches/' + service + '/' + name,
            'sha256': hashlib.sha256(source.read_bytes()).hexdigest()})
(stage / 'patch-manifest.json').write_text(json.dumps(entries), encoding='utf-8')
html = (root / 'nemo-frontend/dist/index.html').read_bytes()
(stage / 'frontend-manifest.json').write_text(json.dumps({'htmlSha256': hashlib.sha256(html).hexdigest()}), encoding='utf-8')
with tarfile.open(stage / 'frontend.tar.gz', 'w:gz') as archive:
    for path in sorted((root / 'nemo-frontend/dist').iterdir()):
        archive.add(path, arcname=path.name)
with tarfile.open(stage / 'patches.tar.gz', 'w:gz') as archive:
    archive.add(stage / 'patches', arcname='patches')
print(json.dumps({'patchEntries': sum(map(len, entries.values())), 'htmlSha256': hashlib.sha256(html).hexdigest()}))
