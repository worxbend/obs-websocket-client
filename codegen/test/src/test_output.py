"""Writer ownership, path containment, duplicate detection and real I/O failures."""

from obs_codegen.errors import GenerationError
from obs_codegen.model import RenderedFile
from obs_codegen.output import write_outputs
from test_support import TemporaryTest, snapshot


class OutputTests(TemporaryTest):
    def test_utf8_lf_and_stale_files_are_preserved(self) -> None:
        stale = self.directory / "stale.scala"
        stale.write_bytes(b"caller owned")
        files = (
            RenderedFile("requests/Example.scala", "é😀\n"),
            RenderedFile("Catalog.scala", "first\n"),
        )
        write_outputs(self.directory, files)
        self.assertEqual((self.directory / "requests/Example.scala").read_bytes(), "é😀\n".encode())
        write_outputs(self.directory, (RenderedFile("Catalog.scala", "second\n"),))
        self.assertEqual(stale.read_bytes(), b"caller owned")
        self.assertEqual((self.directory / "Catalog.scala").read_bytes(), b"second\n")
        write_outputs(self.directory / "unused", ())
        self.assertFalse((self.directory / "unused").exists())

    def test_all_destinations_validated_before_writing(self) -> None:
        for name in (
            "",
            "/absolute.scala",
            "../outside.scala",
            "a/../b",
            "a//b",
            "./a",
            "a/",
            "C:/absolute.scala",
            "C:relative",
            "a\\b",
            "a:b",
        ):
            with self.subTest(name=name), self.assertRaises(GenerationError):
                write_outputs(
                    self.directory / "out",
                    (RenderedFile("first.scala", "safe"), RenderedFile(name, "bad")),
                )
            self.assertFalse((self.directory / "out").exists())

    def test_duplicate_and_casefold_collisions(self) -> None:
        for second in ("Example.scala", "example.scala", "EXAMPLE.SCALA"):
            with self.subTest(second=second), self.assertRaisesRegex(GenerationError, "Duplicate"):
                write_outputs(
                    self.directory,
                    (RenderedFile("Example.scala", "one"), RenderedFile(second, "two")),
                )
        self.assertEqual(snapshot(self.directory), {})

    def test_symlink_escape_directories_and_files(self) -> None:
        outside = self.directory / "outside"
        outside.mkdir()
        root = self.directory / "root"
        root.mkdir()
        try:
            (root / "link").symlink_to(outside, target_is_directory=True)
            (root / "file.scala").symlink_to(outside / "file.scala")
        except OSError as error:
            self.skipTest(f"Symlink creation is unavailable: {error}")
        for name in ("link/Escape.scala", "file.scala"):
            with self.subTest(name=name), self.assertRaisesRegex(GenerationError, "escapes"):
                write_outputs(
                    root, (RenderedFile("first.scala", "safe"), RenderedFile(name, "bad"))
                )
        self.assertFalse((root / "first.scala").exists())
        self.assertEqual(snapshot(outside), {})

    def test_symlink_aliases_cannot_overwrite_one_destination(self) -> None:
        actual = self.directory / "actual"
        actual.mkdir()
        try:
            (self.directory / "alias").symlink_to(actual, target_is_directory=True)
        except OSError as error:
            self.skipTest(f"Symlink creation is unavailable: {error}")
        with self.assertRaisesRegex(GenerationError, "Duplicate resolved"):
            write_outputs(
                self.directory,
                (
                    RenderedFile("actual/Example.scala", "first"),
                    RenderedFile("alias/Example.scala", "second"),
                ),
            )
        self.assertFalse((actual / "Example.scala").exists())

    def test_real_directory_and_file_write_failures_propagate(self) -> None:
        (self.directory / "blocked").write_bytes(b"not a directory")
        with self.assertRaises(OSError):
            write_outputs(self.directory, (RenderedFile("blocked/file.scala", "bad"),))
        (self.directory / "directory.scala").mkdir()
        with self.assertRaises(OSError):
            write_outputs(
                self.directory,
                (RenderedFile("first.scala", "written"), RenderedFile("directory.scala", "bad")),
            )
        self.assertEqual((self.directory / "first.scala").read_bytes(), b"written")
