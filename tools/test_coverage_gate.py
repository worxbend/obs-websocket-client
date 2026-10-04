import json
from pathlib import Path
import tempfile
import unittest

from check_coverage import MODULES, instrumentation_digests, source_digest, stray_instrumentation, verify


class CoverageGateTests(unittest.TestCase):
    def fixture(self, root, missed=False):
        (root / 'source.scala').write_text('object Example')
        for module in MODULES:
            instrumentation = root / 'out' / module / 'scoverage/data.dest/scoverage.coverage'
            instrumentation.parent.mkdir(parents=True)
            records = []
            for index in (1, 2):
                records.append('\n'.join([str(index), f'{module}/src/Example.scala', module,
                                           'Example', 'Object', f'{module}.Example', 'run',
                                           str(index), str(index + 1), '1', 'symbol', 'tree',
                                           'true', '0', 'false', 'description']) + '\n\f\n')
            instrumentation.write_text('# Coverage data, format version: 3.0\n' + ''.join(records))
        manifest = root / 'run.txt'
        manifest.write_text(json.dumps({'source_sha256': source_digest(root), 'started_ns': 0, 'instrumentation_sha256': instrumentation_digests(root)}))
        reports = {}
        for module in MODULES:
            path = root / f'{module}.xml'
            statements = ''.join(
                f'<statement source="{module}/src/Example.scala" package="{module}" class="Example" '
                f'class-type="Object" full-class-name="{module}.Example" method="run" '
                f'start="{index}" end="{index + 1}" line="1" branch="true" '
                f'invocation-count="{0 if missed and index == 2 else 1}" ignored="false"/>'
                for index in (1, 2))
            path.write_text(f'<scoverage statement-count="2" statements-invoked="{1 if missed else 2}">'
                            + statements + '</scoverage>')
            reports[module] = path
        return manifest, reports

    def test_exact_complete_counts_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, reports = self.fixture(root)
            self.assertEqual(verify(root, reports, manifest)[1], [])

    def test_deliberately_missed_branch_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, reports = self.fixture(root, missed=True)
            self.assertTrue(any('below 100%' in error for error in verify(root, reports, manifest)[1]))

    def test_missing_module_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, reports = self.fixture(root)
            del reports['core']
            self.assertIn('core: missing coverage XML', verify(root, reports, manifest)[1])

    def test_changed_source_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, reports = self.fixture(root)
            (root / 'source.scala').write_text('object Changed')
            self.assertIn('Source inputs changed since instrumentation began', verify(root, reports, manifest)[1])

    def test_changed_instrumentation_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, reports = self.fixture(root)
            (root / 'out/core/scoverage/data.dest/scoverage.coverage').write_bytes(b'changed IDs')
            with self.assertRaises(ValueError):
                verify(root, reports, manifest)

    def test_stale_report_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, reports = self.fixture(root)
            evidence = json.loads(manifest.read_text())
            evidence['started_ns'] = max(p.stat().st_mtime_ns for p in reports.values()) + 1
            manifest.write_text(json.dumps(evidence))
            self.assertIn('core: stale coverage XML', verify(root, reports, manifest)[1])

    def test_unexpected_module_instrumentation_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, reports = self.fixture(root)
            stray = root / 'out' / 'stray' / 'scoverage' / 'data.dest'
            stray.mkdir(parents=True)
            (stray / 'scoverage.coverage').write_bytes(b'stray instrumentation')
            separate = root / 'out' / 'integration' / 'scoverage' / 'data.dest'
            separate.mkdir(parents=True)
            (separate / 'scoverage.coverage').write_bytes(b'separately collected integration data')
            self.assertEqual(stray_instrumentation(root), ['stray'])
            errors = verify(root, reports, manifest)[1]
            self.assertIn('Unexpected scoverage data outside gated modules: stray', errors)

    def test_swapped_module_report_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, reports = self.fixture(root)
            reports['server'] = reports['core']
            self.assertIn('server: report does not match instrumented statement inventory',
                          verify(root, reports, manifest)[1])

    def test_modified_statement_inventory_fails(self):
        import xml.etree.ElementTree as ET
        for mutation in ('missing', 'duplicate', 'location', 'classification'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                manifest, reports = self.fixture(root)
                tree = ET.parse(reports['core'])
                report = tree.getroot()
                entry = report.find('statement')
                if mutation == 'missing':
                    report.remove(entry)
                elif mutation == 'duplicate':
                    report.append(entry)
                elif mutation == 'location':
                    entry.set('source', 'other.scala')
                else:
                    entry.set('branch', 'false')
                tree.write(reports['core'])
                self.assertIn('core: report does not match instrumented statement inventory',
                              verify(root, reports, manifest)[1])


if __name__ == '__main__':
    unittest.main()
