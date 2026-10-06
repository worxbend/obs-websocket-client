"""Real entrypoint execution, checksum failures, and render-before-write guarantees."""

import contextlib
import io
import json
import runpy
import subprocess
import sys
from unittest.mock import patch

from jinja2 import UndefinedError
from obs_codegen.cli import generate, main
from obs_codegen.errors import GenerationError
from test_support import GOLDEN, ROOT, TemporaryTest, snapshot


class CliTests(TemporaryTest):
    def args(self) -> list[str]:
        return [
            str(GOLDEN / "schema.json"),
            str(self.directory / "out"),
            str(GOLDEN / "overrides.json"),
            str(GOLDEN / "provenance.json"),
        ]

    def test_main_explicit_and_process_arguments(self) -> None:
        self.assertEqual(main(self.args()), 0)
        with patch.object(sys, "argv", ["main.py", *self.args()]):
            self.assertEqual(main(), 0)
        self.assertEqual(snapshot(self.directory / "out"), snapshot(GOLDEN / "expected"))

    def test_invalid_arity_and_missing_input_have_concise_errors(self) -> None:
        for args in ([], ["one"], [str(self.directory / "missing.json")] * 4):
            stderr = io.StringIO()
            with self.subTest(args=args), contextlib.redirect_stderr(stderr):
                self.assertEqual(main(args), 1)
            self.assertTrue(stderr.getvalue().startswith("codegen: "))
            self.assertNotIn("Traceback", stderr.getvalue())

    def test_checksum_failure_does_not_touch_existing_output(self) -> None:
        schema = self.directory / "schema.json"
        schema.write_bytes((GOLDEN / "schema.json").read_bytes() + b" ")
        output = self.directory / "out"
        output.mkdir()
        (output / "sentinel").write_bytes(b"unchanged")
        with self.assertRaisesRegex(GenerationError, "does not match"):
            generate(schema, output, GOLDEN / "overrides.json", GOLDEN / "provenance.json")
        self.assertEqual(snapshot(output), {"sentinel": b"unchanged"})

    def test_parse_and_semantic_failure_before_output(self) -> None:
        invalid = self.directory / "invalid.json"
        for data in (b"{", b"[]", b'{"requests":true}'):
            invalid.write_bytes(data)
            with self.assertRaisesRegex(GenerationError, "invalid.json"):
                generate(
                    invalid,
                    self.directory / "out",
                    GOLDEN / "overrides.json",
                    GOLDEN / "provenance.json",
                )
            self.assertFalse((self.directory / "out").exists())
        invalid.write_text(json.dumps({"nullableFields": ["Example.request.typo"]}))
        with self.assertRaisesRegex(GenerationError, "typo"):
            generate(
                GOLDEN / "schema.json", self.directory / "out", invalid, GOLDEN / "provenance.json"
            )
        self.assertFalse((self.directory / "out").exists())

    def test_template_failure_and_io_error_are_nonzero(self) -> None:
        with (
            patch(
                "obs_codegen.cli.Renderer.render",
                side_effect=UndefinedError("undefined template value"),
            ),
            contextlib.redirect_stderr(io.StringIO()) as stderr,
        ):
            self.assertEqual(main(self.args()), 1)
        self.assertIn("undefined template value", stderr.getvalue())
        self.assertFalse((self.directory / "out").exists())
        (self.directory / "out").write_bytes(b"not a directory")
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(main(self.args()), 1)
        with (
            patch("obs_codegen.cli.Renderer.render", side_effect=RuntimeError("programming error")),
            self.assertRaisesRegex(RuntimeError, "programming error"),
        ):
            main(self.args())

    def test_script_from_foreign_working_directory(self) -> None:
        result = subprocess.run(
            [sys.executable, str(ROOT / "codegen/src/main.py"), *self.args()],
            cwd=self.directory,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(snapshot(self.directory / "out"), snapshot(GOLDEN / "expected"))
        failed = subprocess.run(
            [sys.executable, str(ROOT / "codegen/src/main.py")],
            cwd=self.directory,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(failed.returncode, 1)
        self.assertIn("Expected schema path", failed.stderr)

    def test_main_module_import_and_execution_are_covered(self) -> None:
        runpy.run_path(str(ROOT / "codegen/src/main.py"), run_name="imported_entrypoint")
        with (
            patch.object(sys, "argv", ["main.py", *self.args()]),
            self.assertRaises(SystemExit) as exit_info,
        ):
            runpy.run_path(str(ROOT / "codegen/src/main.py"), run_name="__main__")
        self.assertEqual(exit_info.exception.code, 0)
