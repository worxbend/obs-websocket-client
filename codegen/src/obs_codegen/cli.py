"""Four-positional-argument offline generator entry point."""

import sys
from collections.abc import Sequence
from pathlib import Path

from jinja2 import TemplateError

from .errors import GenerationError
from .inputs import read_bytes, verify_checksum
from .model import RenderedFile
from .normalize import normalize
from .output import write_outputs
from .parsing import parse_overrides, parse_provenance, parse_schema
from .render import Renderer


def generate(
    schema: Path, output: Path, overrides: Path, provenance: Path
) -> tuple[RenderedFile, ...]:
    """Validate and render before touching output; return exactly the written files."""
    schema_bytes = read_bytes(schema, "protocol schema")
    parsed_schema = parse_schema(schema_bytes, schema)
    parsed_overrides = parse_overrides(read_bytes(overrides, "generation overrides"), overrides)
    parsed_provenance = parse_provenance(read_bytes(provenance, "schema provenance"), provenance)
    verify_checksum(schema_bytes, parsed_provenance, provenance)
    files = Renderer().render(normalize(parsed_schema, parsed_overrides), parsed_provenance)
    write_outputs(output, files)
    return files


def main(argv: Sequence[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    try:
        if len(args) != 4:
            raise GenerationError(
                "Expected schema path, output directory, overrides path, provenance path"
            )
        generate(Path(args[0]), Path(args[1]), Path(args[2]), Path(args[3]))
    except (GenerationError, OSError, TemplateError) as error:
        print(f"codegen: {error}", file=sys.stderr)
        return 1
    return 0
