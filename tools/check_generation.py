#!/usr/bin/env python3
"""Regenerate the pinned catalog twice and compare every output byte offline."""
import hashlib
import json
from pathlib import Path
import os
import subprocess
import tempfile


def mill_command(root):
    return [str(root / ('mill.bat' if os.name == 'nt' else 'mill')), '--no-server']


def snapshot(directory):
    if not directory.is_dir():
        raise ValueError(f'Missing generated source directory: {directory}')
    files = {p.relative_to(directory).as_posix(): p.read_bytes()
             for p in directory.rglob('*') if p.is_file()}
    if not files or 'catalog-inventory.tsv' not in files:
        raise ValueError(f'Incomplete generated source inventory: {directory}')
    if any(not (name.endswith('.scala') or name == 'catalog-inventory.tsv') for name in files):
        raise ValueError(f'Unexpected files in generated Scala source tree: {directory}')
    return files


def build_snapshot(root):
    return snapshot(root / 'out/protocol/generatedSources.dest/scala')


def main():
    root = Path(__file__).resolve().parents[1]
    provenance = json.loads((root / 'protocol-spec/provenance.json').read_text())
    schema = root / 'protocol-spec/protocol.json'
    # Provenance names are maintained with the pinned specification.
    expected = provenance.get('sha256') or provenance.get('schemaSha256')
    if expected is None:
        expected = provenance.get('schema', {}).get('sha256')
    if expected is None:
        raise SystemExit('Missing schema checksum in provenance')
    if hashlib.sha256(schema.read_bytes()).hexdigest() != expected:
        raise SystemExit('Pinned protocol schema checksum mismatch')

    with tempfile.TemporaryDirectory(prefix='obs-codegen-') as directory:
        runs = [Path(directory) / 'first', Path(directory) / 'second']
        for target in runs:
            subprocess.run(mill_command(root) + ['codegen.run', str(schema), str(target),
                            str(root / 'protocol-spec/overrides.json'),
                            str(root / 'protocol-spec/provenance.json')], cwd=root, check=True)
        if snapshot(runs[0]) != snapshot(runs[1]):
            raise SystemExit('Generation is nondeterministic')
        subprocess.run(mill_command(root) + ['protocol.generatedSources'], cwd=root, check=True)
        if snapshot(runs[0]) != build_snapshot(root):
            raise SystemExit('Build generated sources differ from reproducible output')
        print(f'Pinned schema verified; {len(snapshot(runs[0]))} generated files match byte-for-byte')


if __name__ == '__main__':
    main()
