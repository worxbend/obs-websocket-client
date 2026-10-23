#!/usr/bin/env python3
"""Download all 28 release files (7 libraries x POM/jar/sources/javadoc) and hash-check them.

Usage: verify_published.py REPOSITORY_URL
Compares each remote file with the artifact this job built and uploaded. Central
propagation can lag, so 404/5xx retry until one shared deadline.
GitHub Packages needs GITHUB_PACKAGES_USERNAME / GITHUB_PACKAGES_PASSWORD.
"""

import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GROUP = "com.worxbend"
LIBRARIES = ("protocol", "core", "sttp", "okhttp", "zio", "fs2", "pekko")
CENTRAL = "https://repo.maven.apache.org/maven2"
SUFFIXES = (".pom", ".jar", "-sources.jar", "-javadoc.jar")
RETRIED = (404, 429, 500, 502, 503, 504)


def request_headers(repository: str, env: dict[str, str]) -> dict[str, str]:
    if repository == CENTRAL:
        return {}
    if not re.fullmatch(r"https://maven\.pkg\.github\.com/[\w.-]+/[\w.-]+", repository):
        raise ValueError("repository must be Maven Central or a GitHub Packages Maven URL")
    token = f"{env['GITHUB_PACKAGES_USERNAME']}:{env['GITHUB_PACKAGES_PASSWORD']}"
    return {"Authorization": "Basic " + base64.b64encode(token.encode()).decode()}


def artifact_files(data: dict, module: str, version: str) -> list[tuple[Path, str]]:
    """Mill `show <m>.publishArtifacts` JSON -> (local file, repository-relative path)."""
    artifact = f"obs-websocket-client-{module}_3"
    if data["meta"] != {"group": GROUP, "id": artifact, "version": version}:
        raise ValueError(f"{module}: wrong coordinates {data['meta']}")
    names = {name for _, name in data["payload"]}
    if len(data["payload"]) != 4 or names != {f"{artifact}-{version}{s}" for s in SUFFIXES}:
        raise ValueError(f"{module}: expected POM, jar, sources and javadoc, got {sorted(names)}")
    base = f"{GROUP.replace('.', '/')}/{artifact}/{version}"
    return [(Path(ref.split(":", 3)[3]), f"{base}/{name}") for ref, name in data["payload"]]


def fetch(
    request: urllib.request.Request,
    deadline: float,
    *,
    open_url: Callable[..., object],
    clock: Callable[[], float],
    sleep: Callable[[float], None],
) -> bytes:
    while True:
        try:
            with open_url(request, timeout=30) as response:  # type: ignore[attr-defined]
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code not in RETRIED or clock() >= deadline:
                raise
        except urllib.error.URLError:
            if clock() >= deadline:
                raise
        sleep(5)


def verify(
    files: list[tuple[Path, str]],
    repository: str,
    headers: dict[str, str],
    *,
    open_url: Callable[..., object],
    clock: Callable[[], float],
    sleep: Callable[[float], None],
) -> None:
    # One deadline for the whole set, not per file.
    deadline = clock() + (1800 if repository == CENTRAL else 120)
    for local, relative in files:
        request = urllib.request.Request(f"{repository}/{relative}", headers=headers)
        remote = fetch(request, deadline, open_url=open_url, clock=clock, sleep=sleep)
        if hashlib.sha256(remote).digest() != hashlib.sha256(local.read_bytes()).digest():
            raise ValueError(f"remote file differs from built artifact: {relative}")
        print(f"Verified {relative}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository")
    repository = parser.parse_args().repository.rstrip("/")
    headers = request_headers(repository, dict(os.environ))
    version = os.environ["OBS_RELEASE_VERSION"]
    files: list[tuple[Path, str]] = []
    for module in LIBRARIES:
        shown = subprocess.check_output(
            [
                str(ROOT / "mill"),
                "--no-server",
                "--ticker",
                "false",
                "show",
                f"{module}.publishArtifacts",
            ],
            cwd=ROOT,
            text=True,
            timeout=900,
        )
        files.extend(artifact_files(json.loads(shown), module, version))
    verify(
        files,
        repository,
        headers,
        open_url=urllib.request.urlopen,
        clock=time.monotonic,
        sleep=time.sleep,
    )


if __name__ == "__main__":
    main()
