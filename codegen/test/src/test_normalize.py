"""Semantic parity for fields, categories, naming, and enum classification."""

import unittest
from dataclasses import replace

from obs_codegen.errors import GenerationError
from obs_codegen.normalize import category_name, normalize
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
from test_support import render


class NormalizationTests(unittest.TestCase):
    def test_all_type_mappings_and_immutable_ordering(self) -> None:
        types = ("String", "Number", "Boolean", "Object", "Any", "Array<Object>", "Array<String>")
        fields = tuple(SchemaField(f"field{i}", value) for i, value in enumerate(types))
        schema = Schema(
            (SchemaRequest("Zed", fields, fields), SchemaRequest("Empty")),
            (SchemaEvent("Changed", fields),),
        )
        result = normalize(schema, Overrides(("Zed.request.field0",)))
        self.assertEqual(tuple(request.name for request in result.requests), ("Empty", "Zed"))
        self.assertEqual(
            tuple(field.scala_type for field in result.requests[1].request_fields),
            (
                "String",
                "BigDecimal",
                "Boolean",
                "JsonObject",
                "JsonValue",
                "Vector[JsonObject]",
                "Vector[String]",
            ),
        )
        self.assertEqual(result.requests[1].request_fields[0].placeholder, "None")
        source = render(schema, Overrides(("Zed.request.field0",)))
        self.assertIn("`field0` = None", source["requests/Zed.scala"])
        self.assertEqual(
            source,
            render(
                replace(schema, requests=tuple(reversed(schema.requests))),
                Overrides(("Zed.request.field0",)),
            ),
        )

    def test_field_docs_renames_and_restrictions(self) -> None:
        field = SchemaField(
            "requestType", "Number", True, ">= 0,\n<= 100", "Volume. */", "Specify another"
        )
        schema = Schema(
            (SchemaRequest("Volume", (field,), (field,)),), (SchemaEvent("Changed", (field,)),)
        )
        output = render(schema)
        expected = "@param `payloadRequestType` Volume. * / Restrictions: >= 0, <= 100 When omitted: Specify another"
        self.assertEqual(output["requests/Volume.scala"].count(expected), 2)
        self.assertIn(expected, output["events/Changed.scala"])
        self.assertIn("\t>= 0, <= 100\n", output["catalog-inventory.tsv"])

    def test_categories_metadata_and_envelope_renames(self) -> None:
        doc = Documentation("First.\nSecond.", "5.0.0", "1", True)
        schema = Schema(
            (
                SchemaRequest(
                    "Zed",
                    (SchemaField("eventType", "String"),),
                    documentation=doc,
                    category="scene items",
                ),
                SchemaRequest("Empty", category="config"),
            )
        )
        source = render(schema)
        self.assertIn("val configuration: ConfigurationApi[E]", source["RequestApi.scala"])
        self.assertIn("val sceneItems: SceneItemsApi[E]", source["RequestApi.scala"])
        self.assertIn("def empty()", source["RequestApi.scala"])
        self.assertIn(
            "(initial version 5.0.0, RPC version 1). Deprecated upstream.",
            source["requests/Zed.scala"],
        )
        self.assertIn("/** First. Second.", source["requests/Zed.scala"])
        self.assertIn("`payloadEventType`", source["requests/Zed.scala"])
        self.assertEqual(category_name("input ABC"), "inputABC")

    def test_duplicate_names_and_response_collisions(self) -> None:
        cases = (
            (Schema((SchemaRequest("Same"), SchemaRequest("Same"))), "Duplicate request"),
            (Schema(events=(SchemaEvent("Same"), SchemaEvent("Same"))), "Duplicate event"),
            (Schema(enums=(SchemaEnum("Same"), SchemaEnum("Same"))), "Duplicate enum"),
            (Schema((SchemaRequest("Zed"), SchemaRequest("ZedResponse"))), "ZedResponse"),
        )
        for schema, message in cases:
            with self.subTest(message=message), self.assertRaisesRegex(GenerationError, message):
                normalize(schema, Overrides())

    def test_field_collisions_in_all_payload_kinds(self) -> None:
        pairs = (
            ("slot", "slot"),
            ("requestType", "payloadRequestType"),
            ("eventType", "payloadEventType"),
        )
        for first, second in pairs:
            fields = (SchemaField(first, "String"), SchemaField(second, "Number"))
            for schema in (
                Schema((SchemaRequest("Collision", fields),)),
                Schema((SchemaRequest("Collision", response_fields=fields),)),
                Schema(events=(SchemaEvent("Collision", fields),)),
            ):
                with (
                    self.subTest(first=first, schema=schema),
                    self.assertRaisesRegex(GenerationError, "Collision"),
                ):
                    normalize(schema, Overrides())

    def test_invalid_identifiers_and_types_and_unmatched_override(self) -> None:
        for name in (
            "",
            "not-a-name",
            "bad`name",
            "parent..child",
            ".child",
            "parent.",
            "toJson",
            "copy",
        ):
            with self.subTest(name=name), self.assertRaisesRegex(GenerationError, "Broken.request"):
                normalize(
                    Schema((SchemaRequest("Broken", (SchemaField(name, "String"),)),)), Overrides()
                )
        with self.assertRaisesRegex(GenerationError, "Broken.request.x"):
            normalize(
                Schema((SchemaRequest("Broken", (SchemaField("x", "Mystery"),)),)), Overrides()
            )
        with self.assertRaisesRegex(GenerationError, "Broken.request.typo"):
            normalize(Schema(), Overrides(("Broken.request.typo",)))
        for name in ("", "../Escape", "A/B", "Bad`Name"):
            with (
                self.subTest(name=name),
                self.assertRaisesRegex(GenerationError, "Invalid generated"),
            ):
                normalize(Schema((SchemaRequest(name),)), Overrides())

    def test_category_errors(self) -> None:
        for category in ("", "bad-name", "copy"):
            with (
                self.subTest(category=category),
                self.assertRaisesRegex(GenerationError, "category"),
            ):
                normalize(Schema((SchemaRequest("Example", category=category),)), Overrides())
        with self.assertRaisesRegex(GenerationError, "Duplicate category"):
            normalize(
                Schema(
                    (
                        SchemaRequest("One", category="config"),
                        SchemaRequest("Two", category="configuration"),
                    )
                ),
                Overrides(),
            )

    def test_numeric_string_empty_enums_and_order(self) -> None:
        mask = SchemaEnum(
            "Mask",
            (
                SchemaEnumEntry("One", "(1 << 0)"),
                SchemaEnumEntry("Type", "(1 << 1)", "Bitmask member."),
                SchemaEnumEntry("All", "(One | Type)"),
                SchemaEnumEntry("Both", "(One & Type)"),
                SchemaEnumEntry("Single", "(One)"),
            ),
        )
        text = SchemaEnum("State", (SchemaEnumEntry("Started", 'OBS_"\\\n'),))
        number = SchemaEnum(
            "Status", (SchemaEnumEntry("Success", "100"), SchemaEnumEntry("Negative", "-1"))
        )
        output = render(Schema(enums=(text, number, mask, SchemaEnum("Empty"))))
        self.assertIn("Mask((`One`.value | `Type`.value))", output["enums/Mask.scala"])
        self.assertIn("Mask((`One`.value & `Type`.value))", output["enums/Mask.scala"])
        self.assertIn("Mask((`One`.value))", output["enums/Mask.scala"])
        self.assertIn("/** Bitmask member. */", output["enums/Mask.scala"])
        self.assertIn('State("OBS_\\"\\\\\\u000a")', output["enums/State.scala"])
        self.assertIn("value: Long", output["enums/Status.scala"])
        self.assertIn("value: String", output["enums/State.scala"])
        self.assertTrue(output["enums/Empty.scala"].endswith("object Empty:\n\n"))

    def test_invalid_enums(self) -> None:
        entries = (
            (SchemaEnumEntry("X", ""),),
            (SchemaEnumEntry("X", "100"), SchemaEnumEntry("Y", "OBS_TEXT")),
            (SchemaEnumEntry("X", "1"), SchemaEnumEntry("X", "2")),
            (SchemaEnumEntry("bad`name", "1"),),
            (SchemaEnumEntry("X", "(0x1F)"),),
            (SchemaEnumEntry("X", "(1L << 0)"),),
            (SchemaEnumEntry("X", "(Unknown | X)"),),
            (SchemaEnumEntry("X", str(2**63)),),
        )
        for values in entries:
            with self.subTest(values=values), self.assertRaisesRegex(GenerationError, "Broken"):
                normalize(Schema(enums=(SchemaEnum("Broken", values),)), Overrides())
