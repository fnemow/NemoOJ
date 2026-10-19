"""Scan every Maven module separately; never accept a partial reactor export."""
import json
from pathlib import Path
import runpy
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def collect(root, parse_deps):
    backend = root / 'OJ-backend-microservice'
    pom = ET.parse(backend / 'pom.xml')
    modules = [node.text for node in pom.findall('./{*}modules/{*}module')]
    if not modules:
        raise ValueError('No backend modules declared')
    directories = [backend / name for name in modules] + [root / 'nemo-sandbox']
    paths, summary = [], []
    for directory in directories:
        path = directory / 'target/security-deps.txt'
        dependencies = parse_deps([path])  # Missing files fail closed.
        if not dependencies:
            raise ValueError('Empty module inventory: ' + directory.name)
        required = []
        if directory.name == 'OJ-backend-gateway':
            required = [('org.springframework', 'spring-webflux')]
        elif directory.name.endswith('-service') or directory.name == 'nemo-sandbox':
            required = [('org.springframework', 'spring-webmvc')]
        if directory.name == 'nemo-sandbox':
            required += [('com.github.docker-java', 'docker-java-core'),
                         ('org.apache.httpcomponents.client5', 'httpclient5')]
        for group, artifact in required:
            if not any(g == group and a == artifact for g, a, v in dependencies):
                raise ValueError(f'Incomplete {directory.name} inventory: missing {group}:{artifact}')
        paths.append(path)
        summary.append({'module': directory.name, 'dependencyCount': len(dependencies)})
    return paths, summary


def main():
    scanner = runpy.run_path(str(ROOT / 'build/osv-scan.py'))
    paths, summary = collect(ROOT, scanner['parse_deps'])
    inventory = {'modules': summary, 'uniqueDependencies': len(scanner['parse_deps'](paths))}
    Path('runtime-inventory.json').write_text(json.dumps(inventory, indent=2), encoding='utf-8')
    print(json.dumps(inventory), flush=True)
    sys.argv = ['osv-scan.py', *map(str, paths)]
    return scanner['main']()


if __name__ == '__main__':
    raise SystemExit(main())
