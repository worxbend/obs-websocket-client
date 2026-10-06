#!/usr/bin/env python3
"""Fail closed on missing, stale, or incomplete Scoverage and Python coverage evidence."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MODULES = ('protocol', 'core', 'sttp', 'okhttp', 'zio', 'fs2', 'pekko', 'examples', 'server')
# Integration coverage is collected separately per PLAN §23; any other directory with scoverage
# data under out/ means a production module escapes this gate.
SEPARATE = ('integration',)

STATEMENT_FIELDS = ('source', 'package', 'class', 'class-type', 'full-class-name',
                    'method', 'start', 'end', 'line', 'branch', 'ignored')


def statement_key(root, attributes):
    values = dict(attributes)
    source = Path(values['source'].replace('\\', '/'))
    values['source'] = str((root / source).resolve())
    return tuple(values[field] for field in STATEMENT_FIELDS)


def instrumented_statements(root, module):
    """Read the pinned Scoverage 3.0 format; preserve form-feed record separators."""
    path = root / 'out' / module / 'scoverage/data.dest/scoverage.coverage'
    text = path.read_text()
    if not text.startswith('# Coverage data, format version: 3.0\n'):
        raise ValueError(f'{module}: unsupported instrumentation format')
    lines = text.split('\n')
    first = next(i for i, line in enumerate(lines) if not line.startswith('#'))
    inventory = []
    ids = set()
    for record in '\n'.join(lines[first:]).split('\f'):
        fields = record.strip('\n').split('\n')
        if fields == ['']:
            continue
        if len(fields) < 15 or fields[0] in ids:
            raise ValueError(f'{module}: malformed or duplicate instrumentation record')
        ids.add(fields[0])
        attributes = dict(zip(STATEMENT_FIELDS, fields[1:10] + [fields[12], fields[14]]))
        inventory.append(statement_key(root, attributes))
    return Counter(inventory)


def source_digest(root):
    import os
    excluded = {'.git', 'out', '.bsp', '.metals', '.venv', 'venv', '__pycache__',
                '.mypy_cache', '.ruff_cache', '.pytest_cache'}
    suffixes = {'.scala', '.mill', '.md', '.json', '.conf', '.py', '.sh', '.yml', '.yaml',
                '.j2', '.toml', '.lock', '.in'}
    names = {'.python-version', '.mill-version', '.scalafmt.conf', 'mill', 'mill.bat'}
    files = []
    for directory, dirs, entries in os.walk(root):
        dirs[:] = [name for name in dirs if name not in excluded
                   and not (Path(directory) / name / 'pyvenv.cfg').is_file()]
        files.extend(Path(directory) / name for name in entries
                     if Path(name).suffix in suffixes or name in names)
    files.sort()
    digest = hashlib.sha256()
    for path in files:
        digest.update(str(path.relative_to(root)).encode())
        digest.update(b'\0')
        digest.update(path.read_bytes())
        digest.update(b'\0')
    return digest.hexdigest()


def instrumentation_digests(root):
    return {module: hashlib.sha256((root / 'out' / module / 'scoverage/data.dest/scoverage.coverage').read_bytes()).hexdigest()
            for module in MODULES}


def stray_instrumentation(root):
    """Fail closed: any scoverage data under out/ for a directory not gated or documented is an offender."""
    out = root / 'out'
    if not out.is_dir():
        return []
    known = set(MODULES) | set(SEPARATE)
    return sorted(directory.name for directory in out.iterdir()
                  if directory.is_dir() and directory.name not in known
                  and (directory / 'scoverage').is_dir())


def validate_counts(module, counts):
    errors = []
    statements, covered_statements, branches, covered_branches = counts
    if statements <= 0:
        errors.append(f'{module}: nonempty production module has no measured statements')
    if min(counts) < 0 or covered_statements > statements or covered_branches > branches:
        errors.append(f'{module}: invalid measured counts')
    if statements != covered_statements or branches != covered_branches:
        errors.append(f'{module}: coverage below 100%')
    return errors


def verify_report(root, module, path, started_ns):
    errors = []
    if path.stat().st_mtime_ns < started_ns:
        errors.append(f'{module}: stale coverage XML')
    report = ET.parse(path).getroot()
    entries = report.findall('.//statement')
    inventory = Counter(statement_key(root, entry.attrib) for entry in entries)
    if inventory != instrumented_statements(root, module):
        errors.append(f'{module}: report does not match instrumented statement inventory')
    if any(entry.attrib.get(key) not in {'true', 'false'} for entry in entries for key in ('branch', 'ignored')):
        errors.append(f'{module}: malformed statement classification')
    branches_entries = [entry for entry in entries if entry.attrib['branch'] == 'true']
    counts = [int(report.attrib['statement-count']), int(report.attrib['statements-invoked']),
              len(branches_entries), sum(int(entry.attrib['invocation-count']) > 0 for entry in branches_entries)]
    if len(entries) != counts[0] or sum(int(entry.attrib['invocation-count']) > 0 for entry in entries) != counts[1]:
        errors.append(f'{module}: statement inventory disagrees with totals')
    if any(entry.attrib.get('ignored') == 'true' for entry in entries):
        errors.append(f'{module}: ignored production statements are forbidden')
    errors.extend(validate_counts(module, counts))
    statements, covered_statements, branches, covered_branches = counts
    row = (f'{module}: statements {covered_statements}/{statements}; branches ' +
           (f'{covered_branches}/{branches}' if branches else 'not applicable (0 branches)'))
    return counts, row, errors


def verify_python_report(root, path, started_ns):
    """Require all production files and exact statement/branch counts, never rounded percentages."""
    errors = []
    if not path.is_file():
        return [0, 0, 0, 0], 'codegen: missing Python coverage JSON', ['codegen: missing Python coverage JSON']
    if path.stat().st_mtime_ns < started_ns:
        errors.append('codegen: stale Python coverage JSON')
    report = json.loads(path.read_text())
    if report['meta']['branch_coverage'] is not True:
        errors.append('codegen: Python branch measurement is disabled')
    expected = {p.resolve() for p in (root / 'codegen/src').rglob('*.py')
                if '__pycache__' not in p.parts}
    inventory_path = path.with_name('coverage-inventory.json')
    if inventory_path.stat().st_mtime_ns < started_ns:
        errors.append('codegen: stale Python statement inventory')
    inventory = json.loads(inventory_path.read_text())
    files = report['files']
    if set(inventory) != set(files):
        errors.append('codegen: Python statement inventory differs from report')
    actual = [(root / name.replace('\\', '/')).resolve() for name in files]
    if not expected or set(actual) != expected or len(actual) != len(set(actual)):
        errors.append('codegen: Python report does not match production source inventory')
    totals = [0, 0, 0, 0]
    for name, data in files.items():
        summary = data['summary']
        fields = ('num_statements', 'covered_lines', 'num_branches', 'covered_branches',
                  'missing_lines', 'missing_branches', 'excluded_lines')
        if any(type(summary[field]) is not int or summary[field] < 0 for field in fields):
            raise ValueError(f'codegen: invalid Python counts: {name}')
        statement_lines = set(inventory[name]['statements'])
        # coverage.py records executed docstring lines but does not count them as statements.
        executed = [line for line in data['executed_lines'] if line in statement_lines]
        lines = executed + data['missing_lines']
        branches = [tuple(arc) for arc in data['executed_branches'] + data['missing_branches']]
        branch_counts = Counter(str(arc[0]) for arc in branches)
        if (set(lines) != statement_lines or inventory[name]['excluded']
                or dict(branch_counts) != inventory[name]['branches']
                or len(set(data['executed_lines'])) != len(data['executed_lines'])
                or len(set(lines)) != len(lines) or len(set(branches)) != len(branches)
                or len(lines) != summary['num_statements']
                or len(executed) != summary['covered_lines']
                or len(data['missing_lines']) != summary['missing_lines']
                or len(branches) != summary['num_branches']
                or len(data['executed_branches']) != summary['covered_branches']
                or len(data['missing_branches']) != summary['missing_branches']):
            errors.append(f'codegen: Python inventory disagrees with totals: {name}')
        if data['excluded_lines'] or summary['excluded_lines']:
            errors.append(f'codegen: excluded Python production statements are forbidden: {name}')
        counts = [summary[field] for field in fields[:4]]
        if counts[0] != counts[1] or counts[2] != counts[3]:
            errors.append(f'codegen: coverage below 100%: {name}')
        totals = [a + b for a, b in zip(totals, counts)]
    if [report['totals'][field] for field in ('num_statements', 'covered_lines', 'num_branches', 'covered_branches')] != totals:
        errors.append('codegen: Python aggregate disagrees with file totals')
    errors.extend(validate_counts('codegen', totals))
    tests_path = path.with_name('tests.json')
    if not tests_path.is_file():
        errors.append('codegen: missing Python test result')
    else:
        tests = json.loads(tests_path.read_text())
        if tests_path.stat().st_mtime_ns < started_ns:
            errors.append('codegen: stale Python test result')
        if type(tests['tests']) is not int or tests['tests'] <= 0 or any(
                type(tests[key]) is not int or tests[key] != 0
                for key in ('failures', 'errors', 'skipped', 'expected_failures', 'unexpected_successes')):
            errors.append('codegen: Python tests are empty, failing, or skipped')
    row = f'codegen: statements {totals[1]}/{totals[0]}; branches {totals[3]}/{totals[2]}'
    return totals, row, errors


def verify(root, report_paths, manifest_path):
    manifest = json.loads(manifest_path.read_text())
    errors = []
    strays = stray_instrumentation(root)
    if strays:
        errors.append('Unexpected scoverage data outside gated modules: ' + ', '.join(strays))
    if manifest.get('source_sha256') != source_digest(root):
        errors.append('Source inputs changed since instrumentation began')
    if manifest['instrumentation_sha256'] != instrumentation_digests(root):
        errors.append('Instrumentation changed since the measurement run began')
    started_ns = manifest['started_ns']
    totals = [0, 0, 0, 0]
    rows = []
    for module in MODULES:
        path = report_paths.get(module)
        if path is None or not path.is_file():
            errors.append(f'{module}: missing coverage XML')
            continue
        counts, row, report_errors = verify_report(root, module, path, started_ns)
        errors.extend(report_errors)
        totals = [a + b for a, b in zip(totals, counts)]
        rows.append(row)
    python_path = report_paths.get('codegen', root / 'out/codegen/test/coverage.dest/coverage.json')
    counts, row, report_errors = verify_python_report(root, python_path, started_ns)
    errors.extend(report_errors)
    totals = [a + b for a, b in zip(totals, counts)]
    rows.append(row)
    rows.append(f'aggregate: statements {totals[1]}/{totals[0]}; branches {totals[3]}/{totals[2]}')
    return rows, errors


def manifest_destination(root, destination):
    """Only generated output under the repository out/ tree may be overwritten."""
    output_root = root.resolve() / 'out'
    path = destination.resolve()
    if path == output_root or not path.is_relative_to(output_root):
        raise ValueError('Coverage manifests must be written beneath repository out/')
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--start', action='store_true')
    parser.add_argument('--manifest', type=Path, default=ROOT / 'out/coverage-run.json')
    parser.add_argument('--report', action='append', default=[], metavar='MODULE=REPORT')
    args = parser.parse_args()
    if args.start:
        import time
        try:
            manifest = manifest_destination(ROOT, args.manifest)
            manifest.parent.mkdir(parents=True, exist_ok=True)
            digests = instrumentation_digests(ROOT)
        except (OSError, ValueError) as error:
            print(f'Coverage start failed: {error}; run ./mill --no-server coverage.reset first', file=sys.stderr)
            return 1
        manifest.write_text(json.dumps({'source_sha256': source_digest(ROOT), 'started_ns': time.time_ns(), 'instrumentation_sha256': digests}, indent=2))
        return 0
    reports = {module: Path(path) for module, path in (item.split('=', 1) for item in args.report)}
    try:
        for module in MODULES:
            if module not in reports:
                candidates = list((ROOT / 'out' / module / 'scoverage').glob('xmlReport.dest/**/scoverage.xml'))
                if len(candidates) == 1:
                    reports[module] = candidates[0]
                elif len(candidates) > 1:
                    raise ValueError(f'{module}: multiple scoverage.xml candidates: ' + ', '.join(map(str, candidates)))
        rows, errors = verify(ROOT, reports, args.manifest)
    except (OSError, ValueError, KeyError, TypeError, IndexError, ET.ParseError) as error:
        print(f'Coverage verification failed: {error}', file=sys.stderr)
        return 1
    print('\n'.join(rows))
    for error in errors:
        print(error, file=sys.stderr)
    return int(bool(errors))


if __name__ == '__main__':
    sys.exit(main())
