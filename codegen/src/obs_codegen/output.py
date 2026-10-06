"""Validate all paths before writes; caller owns stale cleanup, not this writer."""

from pathlib import Path, PurePosixPath, PureWindowsPath

from .errors import GenerationError
from .model import RenderedFile


def write_outputs(directory: Path, outputs: tuple[RenderedFile, ...]) -> None:
    """Write UTF-8/LF files; an I/O failure propagates and may leave partial output.

    Existing unrelated files are never removed. Use a fresh standalone destination.
    This is not a transaction or a sandbox against concurrent filesystem changes.
    """
    root = directory.resolve()
    seen: set[str] = set()
    targets = []
    for output in outputs:
        name = output.path
        relative = PurePosixPath(name)
        if (
            not name
            or relative.is_absolute()
            or PureWindowsPath(name).drive
            or "\\" in name
            or ":" in name
            or any(part in ("", ".", "..") for part in name.split("/"))
        ):
            raise GenerationError(f"Unsafe output path: {name!r}")
        key = name.casefold()
        if key in seen:
            raise GenerationError(f"Duplicate output destination: {name}")
        seen.add(key)
        target = root.joinpath(*relative.parts)
        resolved = target.resolve()
        if not resolved.is_relative_to(root):
            raise GenerationError(f"Output path escapes destination: {name}")
        canonical_key = resolved.as_posix().casefold()
        if canonical_key in seen:
            raise GenerationError(f"Duplicate resolved output destination: {name}")
        seen.add(canonical_key)
        targets.append((target, output.content))
    for target, content in targets:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content.encode("utf-8"))
