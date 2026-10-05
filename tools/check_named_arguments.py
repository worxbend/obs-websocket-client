#!/usr/bin/env python3
"""Exercise the semantic named-argument rule against a versioned executable fixture.

This explicit integration check invokes Mill; importing it never starts a build.
"""
import difflib
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
FIXTURE_FILE = 'Fixture.scala'


def prepare_fixture(root):
    """Reset only the fixed, nonsymlinked scratch directory under repository out/."""
    root = root.resolve()
    resources = root / 'namedArgumentRules/test/resources'
    support = (resources / 'support.scala.txt').read_bytes()
    original = {FIXTURE_FILE: (resources / 'input.scala.txt').read_bytes(), 'Support.scala': support}
    expected = {FIXTURE_FILE: (resources / 'expected.scala.txt').read_bytes(), 'Support.scala': support}
    scratch = root / 'out/named-argument-fixture'
    if scratch.resolve() != scratch:
        raise ValueError('Named-argument fixture output must not traverse a symlink')
    if scratch.exists():
        shutil.rmtree(scratch)
    scratch.mkdir(parents=True)
    for filename, source in original.items():
        (scratch / filename).write_bytes(source)
    return scratch, original, expected


def run_mill(root, task, *arguments, expect_success=True):
    command = [str(root / 'mill'), '--no-server', task, *arguments]
    result = subprocess.run(command, cwd=root, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, check=False, timeout=600)
    print(result.stdout, end='')
    if expect_success and result.returncode != 0:
        raise RuntimeError(f'{task} failed with exit code {result.returncode}')
    return result


def require_rewrite_diagnostic(result):
    """A compiler/download failure must never count as the expected failing check."""
    if result.returncode == 0:
        raise RuntimeError('Named-argument check unexpectedly accepted the positional input fixture')
    diagnostic = result.stdout
    if not (FIXTURE_FILE in diagnostic and all(marker in diagnostic for marker in ('--- ', '+++ ', '@@ '))):
        raise RuntimeError('Initial named-argument check failed without the expected fixture rewrite diff')


def require_sources(scratch, expected, description):
    for filename, source in expected.items():
        fixture = scratch / filename
        actual = fixture.read_bytes()
        if actual != source:
            difference = ''.join(difflib.unified_diff(source.decode().splitlines(keepends=True),
                                                    actual.decode().splitlines(keepends=True),
                                                    fromfile=f'expected/{filename}', tofile=str(fixture)))
            raise RuntimeError(f'{description}\n{difference}')


def main():
    scratch, original, expected = prepare_fixture(ROOT)
    rejected = run_mill(ROOT, 'namedArgumentFixtures.fix', '--check', expect_success=False)
    require_rewrite_diagnostic(rejected)
    require_sources(scratch, original, 'Check mode changed the input fixture')
    run_mill(ROOT, 'namedArgumentFixtures.fix')
    require_sources(scratch, expected, 'Named-argument rewrite differs from the versioned expected fixture')
    run_mill(ROOT, 'namedArgumentFixtures.run')
    # Regenerate semantic information from the rewritten source before proving idempotence.
    run_mill(ROOT, 'namedArgumentFixtures.semanticDbData')
    run_mill(ROOT, 'namedArgumentFixtures.fix', '--check')
    require_sources(scratch, expected, 'The idempotence check changed the rewritten fixture')
    print('Named-argument rule rejected positional input, matched its golden fixture, '
          'executed behavioral assertions, and passed a fresh semantic check')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f'Named-argument rule verification failed: {error}', file=sys.stderr)
        sys.exit(1)
