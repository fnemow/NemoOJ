"""Build a narrow class patch from tested local artifacts; never package credentials."""
import hashlib
import json
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / 'OJ-backend-microservice'
ALLOW = {
    'common': ('com/nemo/OJbackendcommon/security/BrowserSessionSupport', 'com/nemo/OJbackendcommon/security/CsrfTokenSupport',
               'com/nemo/OJbackendcommon/ratelimit/RateLimitGuard'),
    'service-client': ('com/nemo/OJserviceclient/UserFeignClient',),
    'user-service': ('com/nemo/OJbackenduserservice/service/impl/UserServiceImpl',
                     'com/nemo/OJbackenduserservice/controller/UserController',
                     'com/nemo/OJbackenduserservice/controller/PublicReadinessController')}

def main():
    import sys
    stage = Path(sys.argv[1]); stage.mkdir(parents=True, exist_ok=True)
    manifest = {}
    with tarfile.open(stage / 'backend.tgz', 'w:gz') as archive:
        for module, prefixes in ALLOW.items():
            folder = BACKEND / ('OJ-backend-' + module) / 'target/classes'
            for prefix in prefixes:
                files = list((folder / prefix).parent.glob((folder / prefix).name + '*.class'))
                assert files, 'Missing compiled class'
                for file in files:
                    name = module + '/' + file.relative_to(folder).as_posix()
                    manifest[name] = hashlib.sha256(file.read_bytes()).hexdigest()
                    archive.add(file, arcname=name)
    (stage / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    with tarfile.open(stage / 'frontend.tgz', 'w:gz') as archive:
        for file in (ROOT / 'nemo-frontend/dist').rglob('*'):
            if file.is_file(): archive.add(file, arcname=file.relative_to(ROOT / 'nemo-frontend/dist'))
    print(json.dumps({'classes': len(manifest), 'frontendBytes': (stage / 'frontend.tgz').stat().st_size}))

if __name__ == '__main__': main()
