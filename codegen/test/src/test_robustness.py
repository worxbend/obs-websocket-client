"""Regressions for future catalogs: masks, namespaces, and source comments."""

import json
import unittest
from dataclasses import replace
from pathlib import Path

from obs_codegen.errors import GenerationError
from obs_codegen.normalize import normalize
from obs_codegen.parsing import parse_provenance, parse_schema
from obs_codegen.render import Renderer
from obs_codegen.scala import collapse
from obs_codegen.schema import (
    Documentation,
    Overrides,
    Schema,
    SchemaEnum,
    SchemaEnumEntry,
    SchemaEvent,
    SchemaField,
    SchemaRequest,
)
from test_support import PROVENANCE, render


def masks(*entries: tuple[str, str]) -> Schema:
    return Schema(enums=(SchemaEnum("Mask", tuple(SchemaEnumEntry(*e) for e in entries)),))


class MaskRegressionTests(unittest.TestCase):
    def test_malformed_masks_are_rejected(self) -> None:
        for expression in (
            "(1 2)",
            "(One |)",
            "()",
            "(1 <<)",
            "(1 <<< 2)",
            "(1 >> 2)",
            "(1 + 2)",
            "(1 || 2)",
            "(1 & & 2)",
            "(1 << 2",
            "(1 << 2))",
            "(1 |",
            "(0x1F)",
            "(1L << 0)",
            "(01)",
            "(1); sys.exit()",
            "(1\u00a0| 2)",
        ):
            with (
                self.subTest(expression=expression),
                self.assertRaisesRegex(GenerationError, "Mask"),
            ):
                normalize(masks(("One", "1"), ("Bad", expression)), Overrides())

    def test_safe_catalog_expressions_preserve_source_bytes(self) -> None:
        source = render(masks(("One", "(1 << 0)"), ("All", "(One | (1 << 4))")))["enums/Mask.scala"]
        self.assertIn("Mask((1 << 0))", source)
        self.assertIn("Mask((`One`.value | (1 << 4)))", source)

    def test_long_shift_boundaries_and_overflow(self) -> None:
        for shift, result in (
            (30, 1073741824),
            (31, 2147483648),
            (32, 4294967296),
            (62, 4611686018427387904),
            (63, -9223372036854775808),
        ):
            with self.subTest(shift=shift):
                expression = (
                    normalize(masks(("Bit", f"(1 << {shift})")), Overrides())
                    .enums[0]
                    .constants[0]
                    .expression
                )
                self.assertEqual(expression, "(1 << 30)" if shift == 30 else f"{result}L")
        for expression in ("(1 << -1)", "(1 << 64)", "(1 << 1000)", "(9223372036854775808)"):
            with (
                self.subTest(expression=expression),
                self.assertRaisesRegex(GenerationError, "Mask"),
            ):
                normalize(masks(("Bad", expression)), Overrides())

    def test_long_literals_and_negative_masks(self) -> None:
        for literal in ("9223372036854775807", "-9223372036854775808", "2147483648", "-2147483649"):
            with self.subTest(literal=literal):
                expression = (
                    normalize(masks(("Value", literal)), Overrides())
                    .enums[0]
                    .constants[0]
                    .expression
                )
                self.assertEqual(expression, literal + "L")
        expression = (
            normalize(masks(("Value", "(-1 << 63)")), Overrides()).enums[0].constants[0].expression
        )
        self.assertEqual(expression, "-9223372036854775808L")

    def test_forward_references_are_folded_without_reordering_public_constants(self) -> None:
        result = normalize(
            masks(("All", "(Later | 2)"), ("Later", "(Bit)"), ("Bit", "(1 << 32)")), Overrides()
        ).enums[0]
        self.assertEqual(tuple(c.identifier for c in result.constants), ("All", "Later", "Bit"))
        self.assertEqual(
            tuple(c.expression for c in result.constants),
            ("4294967298L", "4294967296L", "4294967296L"),
        )

    def test_self_cycles_and_unknown_references_are_rejected(self) -> None:
        for entries in (
            (("Bad", "(Bad)"),),
            (("A", "(B)"), ("B", "(A)")),
            (("A", "(B)"), ("B", "(C)"), ("C", "(A | 1)")),
            (("Bad", "(Unknown)"),),
        ):
            with self.subTest(entries=entries), self.assertRaisesRegex(GenerationError, "Mask"):
                normalize(masks(*entries), Overrides())

    def test_scala_operator_precedence_and_long_reference_width(self) -> None:
        # Scala's '<<' binds tighter than '&'; references have Long width.
        result = normalize(
            masks(("Forward", "(1 << 2 & 1)"), ("Later", "(Forward)")), Overrides()
        ).enums[0]
        self.assertEqual(result.constants[0].expression, "(1 << 2 & 1)")
        result = normalize(masks(("Forward", "(Later << 32)"), ("Later", "1")), Overrides()).enums[
            0
        ]
        self.assertEqual(result.constants[0].expression, "4294967296L")
        result = normalize(
            masks(("Forward", "(Later | 1 << 2 & 1)"), ("Later", "(1 << 32)")), Overrides()
        ).enums[0]
        self.assertEqual(result.constants[0].expression, "4294967296L")

    def test_expression_size_is_bounded(self) -> None:
        for expression in ("(" * 300 + "1" + ")" * 300, "(" + " " * 4096 + "1)"):
            with (
                self.subTest(expression=expression),
                self.assertRaisesRegex(GenerationError, "Mask"),
            ):
                normalize(masks(("Bad", expression)), Overrides())


class NamespaceRegressionTests(unittest.TestCase):
    def test_unquoted_type_keywords_and_shadowed_helpers_are_rejected(self) -> None:
        for name in ("class", "enum", "given", "type", "opaque", "_", "String", "Long"):
            for schema in (
                Schema((SchemaRequest(name),)),
                Schema(events=(SchemaEvent(name),)),
                Schema(enums=(SchemaEnum(name),)),
            ):
                with self.subTest(name=name, schema=schema), self.assertRaises(GenerationError):
                    normalize(schema, Overrides())
        for name in (
            "Request",
            "Event",
            "Field",
            "JsonObject",
            "ValueCodec",
            "ProtocolError",
            "data",
            "decode",
            "minimal",
            "selector",
        ):
            with self.subTest(name=name), self.assertRaises(GenerationError):
                normalize(Schema((SchemaRequest(name),)), Overrides())

    def test_inherited_and_generated_payload_members_are_rejected(self) -> None:
        for name in (
            "validate",
            "canEqual",
            "productElementName",
            "productElementNames",
            "hashCode",
            "getClass",
            "notify",
            "toString",
            "data",
            "ValueCodec",
            "executor",
            "requests",
            "Broken",
            "asInstanceOf",
            "isInstanceOf",
        ):
            with self.subTest(name=name), self.assertRaisesRegex(GenerationError, "Broken.request"):
                normalize(
                    Schema((SchemaRequest("Broken", (SchemaField(name, "String"),)),)), Overrides()
                )
        # validate belongs only to Request; the other DTO namespaces remain independent.
        normalize(
            Schema(
                (SchemaRequest("Example", response_fields=(SchemaField("validate", "String"),)),),
                (SchemaEvent("Changed", (SchemaField("validate", "String"),)),),
            ),
            Overrides(),
        )

    def test_constructor_names_cannot_be_shadowed_in_decoders(self) -> None:
        for schema in (
            Schema((SchemaRequest("Example", (SchemaField("Example", "String"),)),)),
            Schema(
                (
                    SchemaRequest(
                        "Example", response_fields=(SchemaField("ExampleResponse", "String"),)
                    ),
                )
            ),
            Schema(events=(SchemaEvent("Changed", (SchemaField("Changed", "String"),)),)),
        ):
            with self.subTest(schema=schema), self.assertRaises(GenerationError):
                normalize(schema, Overrides())

    def test_backticked_payload_keywords_are_supported(self) -> None:
        source = render(Schema((SchemaRequest("Example", (SchemaField("class", "String"),)),)))[
            "requests/Example.scala"
        ]
        self.assertIn("`class`: String", source)

    def test_category_and_method_namespace_collisions(self) -> None:
        for category in ("class", "request", "hashCode", "toString", "foo.bar", "_", "catalog"):
            with self.subTest(category=category), self.assertRaises(GenerationError):
                normalize(Schema((SchemaRequest("Example", category=category),)), Overrides())
        for name in ("Class", "Wait", "ToString"):
            with self.subTest(name=name), self.assertRaises(GenerationError):
                normalize(Schema((SchemaRequest(name),)), Overrides())
        for requests in (
            (SchemaRequest("Get"), SchemaRequest("get")),
            (SchemaRequest("One", category="foo"), SchemaRequest("Two", category="Foo")),
        ):
            with self.subTest(requests=requests), self.assertRaises(GenerationError):
                normalize(Schema(requests), Overrides())

    def test_enum_companion_collisions_and_quoted_keywords(self) -> None:
        for identifier in ("apply", "unapply", "fromProduct", "getClass", "toString", "Mask", "_"):
            with self.subTest(identifier=identifier), self.assertRaises(GenerationError):
                normalize(masks((identifier, "1")), Overrides())
        self.assertIn("val `class`: Mask", render(masks(("class", "1")))["enums/Mask.scala"])


class MetadataRegressionTests(unittest.TestCase):
    def test_invalid_documentation_versions_rejected_at_both_boundaries(self) -> None:
        for field, value in (
            ("initialVersion", "5.0 */ code"),
            ("initialVersion", "5\npackage bad"),
            ("rpcVersion", "1/*"),
            ("rpcVersion", "-1"),
        ):
            with self.subTest(field=field), self.assertRaises(GenerationError):
                parse_schema(
                    json.dumps({"requests": [{"requestType": "Example", field: value}]}).encode(),
                    Path("schema.json"),
                )
            doc = (
                Documentation(initial_version=value)
                if field == "initialVersion"
                else Documentation(rpc_version=value)
            )
            with self.assertRaises(GenerationError):
                normalize(Schema((SchemaRequest("Example", documentation=doc),)), Overrides())

    def test_invalid_provenance_rejected_in_parser_and_renderer(self) -> None:
        for field, value in (
            ("repository", "https://example.com/*/obs"),
            ("repository", "https://example.com/\npackage bad"),
            ("repository", "not-a-url"),
            ("repository", "https://example.com:999999/obs"),
            ("repository", "https://:80/obs"),
            ("repository", "https://example.com:0/obs"),
            ("revision", "x*/"),
            ("revision", "../main"),
            ("sha256", "hash"),
            ("sha256", "f" * 64 + "\n"),
        ):
            obj = {
                "repository": PROVENANCE.repository,
                "revision": PROVENANCE.revision,
                "sha256": PROVENANCE.sha256,
                field: value,
            }
            with self.subTest(field=field, value=value), self.assertRaises(GenerationError):
                parse_provenance(json.dumps(obj).encode(), Path("provenance.json"))
            with self.assertRaises(GenerationError):
                Renderer().render(
                    normalize(Schema(), Overrides()), replace(PROVENANCE, **{field: value})
                )

    def test_block_comment_open_close_and_unicode_escapes_are_neutralized(self) -> None:
        text = "nested /* comment */ literal \\u002a\\u002f\u2028new line"
        safe = collapse(text)
        self.assertNotIn("/*", safe)
        self.assertNotIn("*/", safe)
        self.assertNotIn("\\u002a", safe)
        self.assertNotIn("\u2028", safe)
        self.assertEqual(collapse(r"C:\Users\user\Desktop"), r"C:\Users\user\Desktop")
        self.assertNotIn(r"\uu002a", collapse(r"\uu002a"))
        source = render(Schema((SchemaRequest("Example", documentation=Documentation(text)),)))[
            "requests/Example.scala"
        ]
        self.assertIn(safe, source)

    def test_category_facades_use_the_small_executor_seam(self) -> None:
        source = render(Schema((SchemaRequest("Example"),)))["RequestApi.scala"]
        self.assertIn("trait RequestExecutor[E]:", source)
        self.assertIn("trait RequestApi[E] extends RequestExecutor[E]:", source)
        self.assertIn("(executor: RequestExecutor[E])", source)
