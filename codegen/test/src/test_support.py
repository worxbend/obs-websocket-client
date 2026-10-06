"""Shared real-file fixtures; temporary resources honor the process TMPDIR."""

import tempfile
import unittest
from pathlib import Path

from obs_codegen.model import RenderedFile
from obs_codegen.normalize import normalize
from obs_codegen.render import Renderer
from obs_codegen.schema import Overrides, Provenance, Schema

ROOT = Path(__file__).resolve().parents[3]
RESOURCES = ROOT / "codegen/test/resources"
GOLDEN = RESOURCES / "golden"
PROVENANCE = Provenance("https://example.com/obs", "abc123", "f" * 64)
NO_OVERRIDES = Overrides()


def render(schema: Schema, overrides: Overrides = NO_OVERRIDES) -> dict[str, str]:
    return {
        file.path: file.content
        for file in Renderer().render(normalize(schema, overrides), PROVENANCE)
    }


def snapshot(directory: Path) -> dict[str, bytes]:
    return {
        path.relative_to(directory).as_posix(): path.read_bytes()
        for path in directory.rglob("*")
        if path.is_file()
    }


class TemporaryTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory(prefix="obs-codegen-test-")
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)

    def assert_outputs(self, files: tuple[RenderedFile, ...], expected: dict[str, bytes]) -> None:
        actual = {file.path: file.content.encode("utf-8") for file in files}
        self.assertEqual(set(actual), set(expected))
        for path in expected:
            with self.subTest(path=path):
                self.assertEqual(actual[path], expected[path])
