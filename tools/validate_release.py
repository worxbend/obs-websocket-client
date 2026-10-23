#!/usr/bin/env python3
"""Gate a tag publish: exact tag commit, seven libraries, one version, changelog heading.

Runs in the publish jobs before any credential is used. Reads GITHUB_REF_TYPE,
GITHUB_REF_NAME and GITHUB_SHA; Mill sees OBS_RELEASE_VERSION from the workflow.
"""

import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LIBRARIES = ("protocol", "core", "sttp", "okhttp", "zio", "fs2", "pekko")


def release_version(ref_type: str, tag: str) -> str:
    """vX.Y.Z tag -> X.Y.Z. Stable only: Versions.release rejects anything else."""
    if ref_type != "tag":
        raise ValueError("select a release tag, not a branch")
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
        raise ValueError(f"release tag must be vX.Y.Z, got {tag!r}")
    return tag[1:]


def check_modules(resolved: str) -> None:
    modules = {line.removesuffix(".publishArtifacts") for line in resolved.split()}
    if modules != set(LIBRARIES):
        raise ValueError(
            f"publishable modules must be exactly {sorted(LIBRARIES)}: {sorted(modules)}"
        )


def check_versions(version: str, versions: dict[str, str]) -> None:
    wrong = {module: found for module, found in versions.items() if found != version}
    if wrong or set(versions) != set(LIBRARIES):
        raise ValueError(f"publishVersion must be {version!r} for all seven libraries: {wrong}")


def check_changelog(version: str, changelog: str) -> None:
    if not re.search(rf"^## {re.escape(version)}(?:\s|$)", changelog, re.MULTILINE):
        raise ValueError(f"CHANGELOG.md needs a '## {version} — YYYY-MM-DD' heading")


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def mill(*args: str) -> str:
    command = [str(ROOT / "mill"), "--no-server", "--ticker", "false", *args]
    return subprocess.check_output(command, cwd=ROOT, text=True, timeout=900).strip()


def main() -> None:
    tag = os.environ.get("GITHUB_REF_NAME", "")
    version = release_version(os.environ.get("GITHUB_REF_TYPE", ""), tag)
    if os.environ.get("OBS_RELEASE_VERSION") != version:
        raise ValueError("OBS_RELEASE_VERSION must equal the tag without its 'v'")
    subprocess.run(["git", "diff", "--exit-code", "HEAD"], cwd=ROOT, check=True)
    head = git("rev-parse", "HEAD")
    if head != git("rev-parse", f"refs/tags/{tag}^{{commit}}") or head != os.environ["GITHUB_SHA"]:
        raise ValueError("checkout must be the exact event tag commit")
    check_modules(mill("resolve", "__:PublishModule.publishArtifacts"))
    versions = {m: json.loads(mill("show", f"{m}.publishVersion")) for m in LIBRARIES}
    check_versions(version, versions)
    check_changelog(version, (ROOT / "CHANGELOG.md").read_text(encoding="utf-8"))
    print(f"Validated {tag} at {head}: seven libraries at {version}")


if __name__ == "__main__":
    main()
