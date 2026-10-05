#!/usr/bin/env python3
"""Fail when resolved runtime dependencies drift from the committed baseline.

Regenerates the artifact inventory with ./mill release.dependencyReport and
compares it with the committed dependency-baseline.json. Mill has no native
dependency lockfile, so this baseline is the resolution-time verification.
Update the baseline intentionally after a reviewed build.mill change:
    ./mill --no-server release.dependencyReport
    cp out/release/dependencyReport.dest/dependencies.json dependency-baseline.json
"""
import json
from pathlib import Path
import subprocess




def summarize_drift(baseline_modules, current_modules):
    lines = []
    for module in sorted(set(baseline_modules) | set(current_modules)):
        before = {row['coordinate']: row for row in baseline_modules.get(module, [])}
        after = {row['coordinate']: row for row in current_modules.get(module, [])}
        lines.extend(f'  {module}: removed {coordinate}' for coordinate in sorted(set(before) - set(after)))
        lines.extend(f'  {module}: added {coordinate}' for coordinate in sorted(set(after) - set(before)))
        lines.extend(f'  {module}: changed {coordinate}'
                     for coordinate in sorted(set(before) & set(after)) if before[coordinate] != after[coordinate])
    return lines


def main():
    root = Path(__file__).resolve().parents[1]
    baseline_path = root / 'dependency-baseline.json'
    report_path = root / 'out/release/dependencyReport.dest/dependencies.json'
    subprocess.run([str(root / 'mill'), '--no-server', 'release.dependencyReport'], cwd=root, check=True)
    baseline = json.loads(baseline_path.read_text())
    current = json.loads(report_path.read_text())
    if baseline == current:
        print(f'Resolved dependencies match {baseline_path.name}')
        return
    message = ['Resolved dependencies drift from the committed dependency-baseline.json:']
    message.extend(summarize_drift(baseline.get('modules', {}), current.get('modules', {})))
    message.append('If the drift is an intentional build.mill change, regenerate and commit the baseline:')
    message.append('  ./mill --no-server release.dependencyReport')
    message.append(f'  cp {report_path.relative_to(root)} {baseline_path.name}')
    raise SystemExit('\n'.join(message))


if __name__ == '__main__':
    main()
