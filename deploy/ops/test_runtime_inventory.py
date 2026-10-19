from pathlib import Path
import runpy
import tempfile
import unittest
from scan_runtime_dependencies import ROOT, collect

parse = runpy.run_path(str(ROOT / 'build/osv-scan.py'))['parse_deps']


class RuntimeInventoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        backend = self.root / 'OJ-backend-microservice'
        backend.mkdir()
        (backend / 'pom.xml').write_text('<project><modules><module>OJ-backend-gateway</module></modules></project>')
        self.gateway = backend / 'OJ-backend-gateway/target/security-deps.txt'
        self.gateway.parent.mkdir(parents=True)
        self.gateway.write_text('org.springframework:spring-webflux:jar:6.2.19:compile\n')
        sandbox = self.root / 'nemo-sandbox/target/security-deps.txt'
        sandbox.parent.mkdir(parents=True)
        sandbox.write_text('org.springframework:spring-webmvc:jar:6.2.19:compile\n'
                           'com.github.docker-java:docker-java-core:jar:3.7.1:compile\n'
                           'org.apache.httpcomponents.client5:httpclient5:jar:5.6.3:compile\n')

    def test_all_declared_modules_and_sandbox_are_collected(self):
        paths, summary = collect(self.root, parse)
        self.assertEqual(2, len(paths))
        self.assertEqual([1, 3], [m['dependencyCount'] for m in summary])

    def test_missing_module_fails_instead_of_scanning_just_sandbox(self):
        self.gateway.unlink()
        with self.assertRaises(FileNotFoundError): collect(self.root, parse)

    def test_empty_or_wrong_module_inventory_fails(self):
        for body in ['', 'org.springframework:spring-core:jar:6.2.19:compile\n']:
            self.gateway.write_text(body)
            with self.assertRaises(ValueError): collect(self.root, parse)


if __name__ == '__main__': unittest.main()
