"""Read original bytes and verify their pinned digest before generation."""

from hashlib import sha256
from pathlib import Path

from .errors import GenerationError
from .schema import Provenance


def read_bytes(path: Path, description: str) -> bytes:
    try:
        return path.read_bytes()
    except OSError as error:
        raise GenerationError(f"Failed to read {description} from {path}: {error}") from error


def verify_checksum(data: bytes, provenance: Provenance, path: Path) -> None:
    digest = sha256(data).hexdigest()
    if digest != provenance.sha256:
        raise GenerationError(
            f"Schema checksum {digest} does not match {provenance.sha256} recorded in {path}"
        )
