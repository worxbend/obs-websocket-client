"""Independent golden/hash oracles plus explicit Jinja branch matrices."""

import hashlib
import json
import unittest
from itertools import product

from jinja2 import UndefinedError
from obs_codegen.cli import generate
from obs_codegen.normalize import normalize
from obs_codegen.render import Renderer
from obs_codegen.scala import collapse, quote
from obs_codegen.schema import (
    Documentation,
    Overrides,
    Schema,
    SchemaEvent,
    SchemaField,
    SchemaRequest,
)
from test_support import GOLDEN, PROVENANCE, RESOURCES, ROOT, TemporaryTest, render, snapshot


class RenderTests(TemporaryTest):
    def test_miniature_golden_files_unchanged(self) -> None:
        files = generate(
            GOLDEN / "schema.json",
            self.directory,
            GOLDEN / "overrides.json",
            GOLDEN / "provenance.json",
        )
        expected = snapshot(GOLDEN / "expected")
        self.assertEqual(len(expected), 9)
        self.assert_outputs(files, expected)
        self.assertEqual(snapshot(self.directory), expected)

    def test_full_catalog_matches_frozen_scala_hashes(self) -> None:
        spec = ROOT / "protocol-spec"
        files = generate(
            spec / "protocol.json",
            self.directory,
            spec / "overrides.json",
            spec / "provenance.json",
        )
        expected = json.loads((RESOURCES / "catalog-sha256.json").read_text())
        self.assertEqual(len(expected), 218)
        hashes = {file.path: hashlib.sha256(file.content.encode()).hexdigest() for file in files}
        self.assertEqual(hashes, expected)
        self.assertEqual(
            {
                path: hashlib.sha256(data).hexdigest()
                for path, data in snapshot(self.directory).items()
            },
            expected,
        )
        second = generate(
            spec / "protocol.json",
            self.directory / "second",
            spec / "overrides.json",
            spec / "provenance.json",
        )
        self.assertEqual(files, second)

    def test_strict_undefined_on_actual_templates(self) -> None:
        renderer = Renderer()
        for name in (
            "request.scala.j2",
            "event.scala.j2",
            "enum.scala.j2",
            "catalog.scala.j2",
            "request_api.scala.j2",
            "event_dispatch.scala.j2",
            "inventory.tsv.j2",
        ):
            with self.subTest(name=name), self.assertRaises(UndefinedError):
                renderer.environment.get_template(name).render()
        self.assertFalse(renderer.environment.autoescape)
        self.assertTrue(renderer.environment.keep_trailing_newline)
        self.assertEqual(renderer.environment.newline_sequence, "\n")

    def test_empty_catalog_and_payloads(self) -> None:
        files = render(Schema())
        self.assertEqual(len(files), 4)
        self.assertEqual(files["Catalog.scala"].count(" = Map.empty"), 2)
        self.assertIn("eventType match\n\n    case _", files["Event.scala"])
        self.assertTrue(files["catalog-inventory.tsv"].endswith("restrictions\n\n"))
        self.assertTrue(files["RequestApi.scala"].endswith("Either[E, A]\n\n"))
        event = render(Schema(events=(SchemaEvent("Empty"),)))["events/Empty.scala"]
        self.assertIn("eventData: JsonObject = JsonObject.empty", event)
        self.assertIn(
            "val _ = data // Empty payloads deliberately accept unknown future fields.", event
        )
        self.assertIn("Right(Empty())", event)

    def test_all_optional_nullable_and_dotted_combinations(self) -> None:
        for optional, nullable, dotted in product((False, True), repeat=3):
            with self.subTest(optional=optional, nullable=nullable, dotted=dotted):
                name = "parent.child" if dotted else "child"
                field = SchemaField(name, "String", optional)
                schema = Schema(
                    (SchemaRequest("Example", (field,), (field,)),),
                    (SchemaEvent("Changed", (field,)),),
                )
                overrides = Overrides(
                    tuple(
                        f"{owner}.{kind}.{name}"
                        for owner, kind in (
                            ("Example", "request"),
                            ("Example", "response"),
                            ("Changed", "event"),
                        )
                    )
                    if nullable
                    else ()
                )
                files = render(schema, overrides)
                source = files["requests/Example.scala"]
                parameter = f"`{name}`: " + (
                    "Field[String] = Field.Missing"
                    if optional
                    else "Option[String]"
                    if nullable
                    else "String"
                )
                self.assertIn(parameter, source)
                codec = (
                    "ValueCodec.nullable(ValueCodec.string)"
                    if nullable and not optional
                    else "ValueCodec.string"
                )
                method = "field" if optional else "required"
                start = f"NestedFields.{method}(data, " if dotted else f"data.{method}("
                read = (
                    start
                    + f'"{name}", {codec}'
                    + (f", {str(nullable).lower()}" if optional else "")
                    + ")"
                )
                self.assertIn(read, source)
                self.assertIn(read, files["events/Changed.scala"])
                self.assertIn("NestedFields.encode(" if dotted else "JsonObject(", source)
                if optional:
                    self.assertIn(f'ValueCodec.put("{name}", `{name}`, ValueCodec.string)', source)
                else:
                    self.assertIn(f'Map("{name}" -> {codec}.encode(`{name}`))', source)

    def test_documentation_version_branches(self) -> None:
        for initial, rpc, deprecated, summary in product(
            ("", "5.0"), ("", "1"), (False, True), ("", "Summary. */")
        ):
            doc = Documentation(summary, initial, rpc, deprecated)
            source = render(Schema((SchemaRequest("Example", documentation=doc),)))[
                "requests/Example.scala"
            ]
            self.assertIn("initial version", source) if initial else self.assertNotIn(
                "initial version", source
            )
            self.assertIn("RPC version", source) if rpc else self.assertNotIn("RPC version", source)
            self.assertIn("Deprecated upstream.", source) if deprecated else self.assertNotIn(
                "Deprecated upstream.", source
            )
            self.assertIn("Summary. * /" if summary else "Generated binding for Example.", source)

    def test_dispatch_chunks_keep_public_and_private_contracts(self) -> None:
        for count in (0, 1, 24, 25, 48, 49):
            with self.subTest(count=count):
                schema = Schema(
                    tuple(SchemaRequest(f"Request{index:03}") for index in range(count))
                )
                source = render(schema)["Catalog.scala"]
                self.assertEqual(source.count("private def decodeRequest"), (count + 23) // 24)
                self.assertEqual(source.count("private def roundTripResponse"), (count + 23) // 24)
                self.assertIn("\n  def decodeRequest(", source)
                self.assertIn("\n  private[protocol] def roundTripResponse(", source)
                self.assertIn("Right(RawRequest(name, data))", source)
                self.assertIn("(Right(data))", source)

    def test_inventory_uses_case_sensitive_wire_order(self) -> None:
        schema = Schema((SchemaRequest("Alpha"), SchemaRequest("AZulu"), SchemaRequest("lower")))
        source = render(schema)["catalog-inventory.tsv"]
        rows = source.splitlines()[1:]
        self.assertEqual([row.split("\t")[1] for row in rows], ["AZulu", "Alpha", "lower"])
        self.assertEqual(rows, sorted(rows))

    def test_custom_template_root_is_explicit(self) -> None:
        # This exercises the renderer boundary without allowing schema-supplied templates.
        template = self.directory / "inventory.tsv.j2"
        template.write_text("{{ absent }}", encoding="utf-8")
        renderer = Renderer(self.directory)
        with self.assertRaises(UndefinedError):
            renderer.environment.get_template(template.name).render(
                schema=normalize(Schema(), Overrides()), provenance=PROVENANCE
            )


class ScalaLiteralTests(unittest.TestCase):
    def test_quotes_controls_and_unicode(self) -> None:
        self.assertEqual(quote('"\\\n\r\t\x00'), '"\\"\\\\\\u000a\\u000d\\u0009\\u0000"')
        self.assertEqual(quote(""), '""')
        self.assertEqual(quote("é😀<>&"), '"é😀<>&"')
        self.assertEqual(quote("\x7f"), '"\x7f"')
        for value in range(32):
            self.assertEqual(quote(chr(value)), f'"\\u{value:04x}"')

    def test_documentation_uses_java_whitespace_semantics(self) -> None:
        self.assertEqual(collapse("\x00  line\n\tvalue */ \r"), "line value * /")
        self.assertEqual(collapse("\u00a0 x \u00a0"), "\u00a0 x \u00a0")
