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
        (root / 'codegen/src').mkdir(parents=True)
        (root / 'codegen/src/main.py').write_text('x = 1\nif x:\n    x = 2\nelse:\n    x = 3\n')
        python_path = root / 'out/codegen/test/coverage.dest/coverage.json'
        python_path.parent.mkdir(parents=True)
        counts = {'num_statements': 4, 'covered_lines': 4, 'num_branches': 2,
                  'covered_branches': 2, 'missing_lines': 0, 'missing_branches': 0,
                  'excluded_lines': 0}
        python_path.write_text(json.dumps({'meta': {'branch_coverage': True}, 'totals': counts,
            'files': {'codegen/src/main.py': {'summary': counts, 'executed_lines': [1, 2, 3, 5],
                'missing_lines': [], 'excluded_lines': [],
                'executed_branches': [[2, 3], [2, 5]], 'missing_branches': []}}}))
        python_path.with_name('coverage-inventory.json').write_text(json.dumps({
            'codegen/src/main.py': {'statements': [1, 2, 3, 5], 'excluded': [], 'branches': {'2': 2}}}))
        python_path.with_name('tests.json').write_text(json.dumps({'tests': 2, 'failures': 0,
            'errors': 0, 'skipped': 0, 'expected_failures': 0, 'unexpected_successes': 0}))
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

    def test_mill_wrapper_participates_in_source_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before = source_digest(root)
            (root / 'mill').write_text('#!/bin/sh\n')
            self.assertNotEqual(before, source_digest(root))

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


    def test_python_report_faults_fail_closed(self):
        for fault in ('missing', 'stale', 'unimported', 'branches_disabled', 'missed_branch',
                      'excluded', 'rounded', 'missing_tests', 'skipped', 'zero_tests', 'duplicate'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                manifest, reports = self.fixture(root)
                path = root / 'out/codegen/test/coverage.dest/coverage.json'
                report = json.loads(path.read_text())
                entry = report['files']['codegen/src/main.py']
                if fault == 'missing':
                    path.unlink()
                elif fault == 'stale':
                    import os
                    os.utime(path, ns=(0, 0))
                    evidence = json.loads(manifest.read_text())
                    evidence['started_ns'] = 1
                    manifest.write_text(json.dumps(evidence))
                elif fault == 'unimported':
                    (root / 'codegen/src/unimported.py').write_text('x = 1\n')
                elif fault in ('missing_tests', 'skipped', 'zero_tests'):
                    tests_path = path.with_name('tests.json')
                    if fault == 'missing_tests':
                        tests_path.unlink()
                    else:
                        tests = json.loads(tests_path.read_text())
                        tests['skipped' if fault == 'skipped' else 'tests'] = 1 if fault == 'skipped' else 0
                        tests_path.write_text(json.dumps(tests))
                else:
                    if fault == 'branches_disabled':
                        report['meta']['branch_coverage'] = False
                    elif fault in ('missed_branch', 'rounded'):
                        entry['missing_branches'] = [entry['executed_branches'].pop()]
                        entry['summary']['covered_branches'] = 1
                        entry['summary']['missing_branches'] = 1
                        report['totals']['covered_branches'] = 1
                        report['totals']['percent_covered'] = 100.0
                    elif fault == 'excluded':
                        entry['excluded_lines'] = [99]
                    else:
                        entry['executed_lines'].append(1)
                    path.write_text(json.dumps(report))
                self.assertTrue(any('codegen:' in error for error in verify(root, reports, manifest)[1]))

    def test_executed_docstrings_are_not_counted_as_statements(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, reports = self.fixture(root)
            path = root / 'out/codegen/test/coverage.dest/coverage.json'
            report = json.loads(path.read_text())
            report['files']['codegen/src/main.py']['executed_lines'].append(99)
            path.write_text(json.dumps(report))
            self.assertEqual(verify(root, reports, manifest)[1], [])

    def test_python_inventory_cannot_drop_uncovered_statements(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, reports = self.fixture(root)
            path = root / 'out/codegen/test/coverage.dest/coverage.json'
            report = json.loads(path.read_text())
            entry = report['files']['codegen/src/main.py']
            entry['executed_lines'].pop()
            entry['summary']['covered_lines'] = 3
            entry['summary']['num_statements'] = 3
            report['totals']['covered_lines'] = 3
            report['totals']['num_statements'] = 3
            path.write_text(json.dumps(report))
            self.assertTrue(any('inventory disagrees' in error for error in verify(root, reports, manifest)[1]))

    def test_codegen_inputs_invalidate_freshness(self):
        for name in ('template.j2', 'pyproject.toml', 'requirements.lock',
                     'requirements-dev.lock', 'requirements.in', '.python-version'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                manifest, reports = self.fixture(root)
                (root / name).write_text('changed')
                self.assertIn('Source inputs changed since instrumentation began',
                              verify(root, reports, manifest)[1])

    def test_virtualenv_and_cache_files_do_not_invalidate_freshness(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before = source_digest(root)
            for name in ('venv', '.venv', '__pycache__', 'out', 'custom-environment'):
                path = root / name
                path.mkdir()
                (path / 'pyvenv.cfg').write_text('home = somewhere')
                (path / 'unrelated.py').write_text('changed')
            self.assertEqual(source_digest(root), before)

    def test_retired_scala_codegen_is_not_silently_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'out/codegen/scoverage').mkdir(parents=True)
            self.assertEqual(stray_instrumentation(root), ['codegen'])


if __name__ == '__main__':
    unittest.main()
