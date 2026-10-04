#!/usr/bin/env python3
"""Report resolved runtime JAR hashes and license declarations from cached POMs.

This is an inventory of resolved artifacts, not a legal review or vulnerability scan.
No network requests are made; absent/unresolved POM declarations remain explicit.
"""
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def declared_licenses(pom: Path, repository: Path, visited: frozenset[Path] = frozenset()) -> list[dict]:
    if pom in visited or not pom.is_file():
        return []
    root = ET.parse(pom).getroot()
    licenses = [
        {"name": item.findtext("m:name", default="unspecified", namespaces=NS),
         "url": item.findtext("m:url", default="", namespaces=NS),
         "pom": str(pom.relative_to(repository))}
        for item in root.findall("m:licenses/m:license", NS)
    ]
    if licenses:
        return licenses
    parent = root.find("m:parent", NS)
    if parent is None:
        return []
    group = parent.findtext("m:groupId", namespaces=NS)
    artifact = parent.findtext("m:artifactId", namespaces=NS)
    version = parent.findtext("m:version", namespaces=NS)
    if not all((group, artifact, version)):
        return []
    parent_pom = repository / group.replace(".", "/") / artifact / version / f"{artifact}-{version}.pom"
    return declared_licenses(parent_pom, repository, visited | {pom})


def inspect_jar(value: str) -> dict:
    jar = Path(value)
    try:
        marker = jar.parts.index("maven2")
    except ValueError:
        return {"coordinate": "unparsed", "file": jar.name,
                "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
                "licenses": [],
                "license_status": "requires-review-no-cached-declaration"}
    repository = Path(*jar.parts[: marker + 1])
    relative = jar.relative_to(repository)
    *group_parts, artifact, version, filename = relative.parts
    licenses = declared_licenses(jar.parent / f"{artifact}-{version}.pom", repository)
    return {"coordinate": f"{'.'.join(group_parts)}:{artifact}:{version}",
            "file": filename, "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            "licenses": licenses,
            "license_status": "declared-in-pom" if licenses else "requires-review-no-cached-declaration"}


def main() -> None:
    source, destination = map(Path, sys.argv[1:])
    modules = json.loads(source.read_text())
    report = {"scope": "Resolved runtime dependencies; declarations are not a license compatibility assessment.",
              "modules": {module: sorted((inspect_jar(path) for path in paths), key=lambda row: row["coordinate"])
                          for module, paths in sorted(modules.items())}}
    destination.write_text(json.dumps(report, indent=2) + "\n")
    print(destination)


if __name__ == "__main__":
    main()
