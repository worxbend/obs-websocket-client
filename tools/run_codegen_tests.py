#!/usr/bin/env python3
"""Run native unittest discovery fail-closed, with optional fresh branch coverage."""
import argparse
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]


def run(output, measure=False, names=()):
    output.mkdir(parents=True, exist_ok=True)
    sys.path.insert(0, str(ROOT / 'codegen/src'))
    sys.path.insert(0, str(ROOT / 'codegen/test/src'))
    coverage = None
    if measure:
        from coverage import Coverage
        coverage = Coverage(config_file=str(ROOT / 'codegen/pyproject.toml'),
                            data_file=str(output / '.coverage'),
                            source=[str(ROOT / 'codegen/src')], branch=True)
        # No omissions/pragma exclusions, even if config accidentally introduces them.
        coverage.set_option('run:omit', [])
        coverage.set_option('report:omit', [])
        coverage.set_option('report:exclude_lines', [])
        coverage.set_option('report:partial_branches', [])
        coverage.start()
    try:
        loader = unittest.TestLoader()
        suite = (loader.loadTestsFromNames(names) if names else
                 loader.discover(str(ROOT / 'codegen/test/src'), pattern='test_*.py'))
        if suite.countTestCases() == 0:
            raise ValueError('No codegen tests discovered')
        result = unittest.TextTestRunner(verbosity=2).run(suite)
    finally:
        if coverage is not None:
            coverage.stop()
            coverage.save()
            coverage.json_report(outfile=str(output / 'coverage.json'))
            report = json.loads((output / 'coverage.json').read_text())
            inventory = {}
            for name in report['files']:
                filename = str(ROOT / name)
                _, statements, excluded, _, _ = coverage.analysis2(filename)
                inventory[name] = {'statements': statements, 'excluded': excluded,
                                   'branches': {str(line): total for line, (total, _)
                                                in coverage.branch_stats(filename).items()}}
            (output / 'coverage-inventory.json').write_text(
                json.dumps(inventory, indent=2) + '\n', encoding='utf-8')
            coverage.html_report(directory=str(output / 'html'))
    summary = {'tests': result.testsRun, 'failures': len(result.failures),
               'errors': len(result.errors), 'skipped': len(result.skipped),
               'expected_failures': len(result.expectedFailures),
               'unexpected_successes': len(result.unexpectedSuccesses)}
    (output / 'tests.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    return int(not result.wasSuccessful() or bool(result.skipped) or bool(result.expectedFailures))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--coverage', action='store_true')
    parser.add_argument('names', nargs='*')
    args = parser.parse_args()
    return run(args.output.resolve(), args.coverage, args.names)


if __name__ == '__main__':
    sys.exit(main())
