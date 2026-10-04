#!/usr/bin/env python3
"""Regenerate the pinned catalog twice and compare every output byte offline."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile



def snapshot(directory):
    return {str(p.relative_to(directory)): p.read_bytes() for p in directory.rglob('*') if p.is_file()}


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
            subprocess.run([str(root / 'mill'), '--no-server', 'codegen.run', str(schema), str(target),
                            str(root / 'protocol-spec/overrides.json'),
                            str(root / 'protocol-spec/provenance.json')], cwd=root, check=True)
        if snapshot(runs[0]) != snapshot(runs[1]):
            raise SystemExit('Generation is nondeterministic')
        subprocess.run([str(root / 'mill'), '--no-server', 'protocol.generatedSources'], cwd=root, check=True)
        if snapshot(runs[0]) != snapshot(root / 'out/protocol/generatedSources.dest'):
            raise SystemExit('Build generated sources differ from reproducible output')
        print(f'Pinned schema verified; {len(snapshot(runs[0]))} generated files match byte-for-byte')


if __name__ == '__main__':
    main()
