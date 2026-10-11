"""Formats for modeled metadata, shared by JSON and in-memory boundaries."""

import re
from urllib.parse import urlsplit

from .errors import GenerationError
from .schema import Documentation, Provenance


def validate_documentation(doc: Documentation) -> None:
    # Empty metadata remains supported. Upstream versions are dotted decimals,
    # optionally with SemVer prerelease/build labels; RPC versions are integers.
    if doc.initial_version and not re.fullmatch(
        r"[0-9]+(?:\.[0-9]+){0,2}(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?", doc.initial_version
    ):
        raise GenerationError(f"Invalid initialVersion metadata: {doc.initial_version!r}")
    if doc.rpc_version and not re.fullmatch(r"[0-9]+", doc.rpc_version):
        raise GenerationError(f"Invalid rpcVersion metadata: {doc.rpc_version!r}")


def validate_provenance(provenance: Provenance) -> None:
    repository = provenance.repository
    # A repository URL is inserted into a Scaladoc link, not fetched here.
    if not re.fullmatch(r"https?://[A-Za-z0-9._~:/%+-]+", repository):
        raise GenerationError(f"Invalid repository provenance: {repository!r}")
    try:
        url = urlsplit(repository)
        valid_url = bool(url.hostname) and url.port != 0
    except ValueError as error:
        raise GenerationError(f"Invalid repository provenance: {repository!r}") from error
    if not valid_url or url.username or url.password:
        raise GenerationError(f"Invalid repository provenance: {repository!r}")
    revision = provenance.revision
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9._/-]*", revision) or any(
        segment in {"", ".", ".."} for segment in revision.split("/")
    ):
        raise GenerationError(f"Invalid revision provenance: {revision!r}")
    if not re.fullmatch(r"[0-9a-f]{64}", provenance.sha256):
        raise GenerationError(f"Invalid sha256 provenance: {provenance.sha256!r}")
