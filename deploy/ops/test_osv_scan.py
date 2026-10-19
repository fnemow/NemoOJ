import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('osv_scan', Path(__file__).resolve().parents[2] / 'build/osv-scan.py')
scan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scan)


class DependencyParsingTest(unittest.TestCase):
    def test_plain_classified_and_java_module_lines_are_all_scanned(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'deps.txt'
            path.write_text('''The following files have been resolved:
   org.projectlombok:lombok:jar:1.18.46:compile
   org.springframework:spring-core:jar:6.2.19:compile -- module spring.core [auto]
   io.netty:netty-transport-native-epoll:jar:linux-x86_64:4.1.100.Final:runtime -- module netty.epoll
   com.nemo:OJ-backend-common:jar:0.0.1-SNAPSHOT:compile
''', encoding='utf-8')
            self.assertEqual(scan.parse_deps([path]), [
                ('org.projectlombok', 'lombok', '1.18.46'),
                ('org.springframework', 'spring-core', '6.2.19'),
                ('io.netty', 'netty-transport-native-epoll', '4.1.100.Final')])

    def test_empty_inventory_cannot_report_a_successful_scan(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'deps.txt'
            path.write_text('No dependency records', encoding='utf-8')
            with patch.object(scan.sys, 'argv', ['osv-scan.py', str(path)]), patch.object(scan, 'query_osv') as query:
                self.assertEqual(scan.main(), 2)
                query.assert_not_called()


if __name__ == '__main__':
    unittest.main()
