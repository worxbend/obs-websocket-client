#!/usr/bin/env python3
"""Fail closed on missing, stale, or less-than-complete Scoverage evidence."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MODULES = ('codegen', 'protocol', 'core', 'sttp', 'examples', 'server')
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
    files = sorted(p for p in root.rglob('*') if p.is_file()
                   and not any(part in {'.git', 'out', '.bsp', '.metals'} for part in p.relative_to(root).parts)
                   and (p.suffix in {'.scala', '.mill', '.md', '.json', '.conf', '.py', '.sh', '.yml', '.yaml'} or p.name in {'.mill-version', '.scalafmt.conf', 'mill'}))
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
        statements, covered_statements, branches, covered_branches = counts
        if statements <= 0:
            errors.append(f'{module}: nonempty production module has no measured statements')
        if min(counts) < 0 or covered_statements > statements or covered_branches > branches:
            errors.append(f'{module}: invalid measured counts')
        if statements != covered_statements or branches != covered_branches:
            errors.append(f'{module}: coverage below 100%')
        totals = [a + b for a, b in zip(totals, counts)]
        rows.append(f'{module}: statements {covered_statements}/{statements}; branches ' +
                    (f'{covered_branches}/{branches}' if branches else 'not applicable (0 branches)'))
    rows.append(f'aggregate: statements {totals[1]}/{totals[0]}; branches {totals[3]}/{totals[2]}')
    return rows, errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--start', action='store_true')
    parser.add_argument('--manifest', type=Path, default=ROOT / 'out/coverage-run.json')
    parser.add_argument('--report', action='append', default=[], metavar='MODULE=XML')
    args = parser.parse_args()
    if args.start:
        import time
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        try:
            digests = instrumentation_digests(ROOT)
        except OSError as error:
            print(f'Coverage start failed: {error}; run ./mill --no-server coverage.reset first', file=sys.stderr)
            return 1
        args.manifest.write_text(json.dumps({'source_sha256': source_digest(ROOT), 'started_ns': time.time_ns(), 'instrumentation_sha256': digests}, indent=2))
        return 0
    paths = dict(item.split('=', 1) for item in args.report)
    reports = {module: Path(path) for module, path in paths.items()}
    try:
        for module in MODULES:
            if module not in reports:
                candidates = list((ROOT / 'out' / module / 'scoverage').glob('xmlReport.dest/**/scoverage.xml'))
                if len(candidates) == 1:
                    reports[module] = candidates[0]
                elif len(candidates) > 1:
                    raise ValueError(f'{module}: multiple scoverage.xml candidates: ' + ', '.join(map(str, candidates)))
        rows, errors = verify(ROOT, reports, args.manifest)
    except (OSError, ValueError, KeyError, ET.ParseError) as error:
        print(f'Coverage verification failed: {error}', file=sys.stderr)
        return 1
    print('\n'.join(rows))
    for error in errors:
        print(error, file=sys.stderr)
    return int(bool(errors))


if __name__ == '__main__':
    sys.exit(main())
