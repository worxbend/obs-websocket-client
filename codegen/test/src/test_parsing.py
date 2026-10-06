"""Parser characterization and strict invalid-input cases."""

import json
import unittest
from dataclasses import FrozenInstanceError
from pathlib import Path

from obs_codegen.errors import GenerationError
from obs_codegen.parsing import parse_overrides, parse_provenance, parse_schema
from obs_codegen.schema import Overrides, Schema

PATH = Path("offending.json")


def schema(value: object) -> Schema:
    return parse_schema(json.dumps(value).encode(), PATH)


class ParsingTests(unittest.TestCase):
    def test_missing_null_and_empty_collections(self) -> None:
        value: object
        for value in ({}, {"requests": None, "events": [], "enums": None}):
            self.assertEqual(schema(value), Schema())
        result = schema(
            {
                "requests": [{"requestType": "Empty"}],
                "events": [{"eventType": "Changed"}],
                "enums": [{"enumType": "State"}],
            }
        )
        self.assertEqual(result.requests[0].request_fields, ())
        self.assertEqual(result.requests[0].category, "general")
        self.assertEqual(result.events[0].fields, ())
        self.assertEqual(result.enums[0].entries, ())
        with self.assertRaises(FrozenInstanceError):
            attribute = "requests"
            setattr(result, attribute, ())

    def test_unknown_metadata_and_documentation_defaults(self) -> None:
        self.assertEqual(parse_overrides(b'{"numberPolicy":"documentation"}', PATH), Overrides())
        result = schema(
            {
                "unknown": {"fraction": 0.1},
                "requests": [{"requestType": "Empty", "deprecated": True, "category": "config"}],
            }
        )
        self.assertTrue(result.requests[0].documentation.deprecated)
        self.assertEqual(result.requests[0].documentation.summary, "")
        self.assertEqual(
            parse_overrides(b'{"nullableFields":["Foo.request.bar"]}', PATH).nullable_fields,
            ("Foo.request.bar",),
        )
        parsed = parse_provenance(
            b'{"repository":"repo","revision":"rev","sha256":"hash","extra":1}', PATH
        )
        self.assertEqual(parsed.upstream_docs, "repo/blob/rev/docs/generated/protocol.md")

    def test_fields_retain_optional_null_false_empty_values(self) -> None:
        value = {
            "valueName": "x",
            "valueType": "String",
            "valueOptional": False,
            "valueRestrictions": "",
            "valueOptionalBehavior": None,
        }
        result = schema({"requests": [{"requestType": "Example", "requestFields": [value]}]})
        field = result.requests[0].request_fields[0]
        self.assertFalse(field.optional)
        self.assertEqual(field.restrictions, "")
        self.assertIsNone(field.optional_behavior)

    def test_enum_integers_strings_and_null(self) -> None:
        values = [-(2**63), 2**63 - 1, "(1 << 2)", "OBS_STARTED", None]
        result = schema(
            {
                "enums": [
                    {
                        "enumType": "State",
                        "enumIdentifiers": [
                            {"enumIdentifier": f"Value{i}", "enumValue": value}
                            for i, value in enumerate(values)
                        ],
                    }
                ]
            }
        )
        self.assertEqual(
            tuple(entry.value for entry in result.enums[0].entries),
            (str(-(2**63)), str(2**63 - 1), "(1 << 2)", "OBS_STARTED", ""),
        )

    def test_invalid_enum_numbers_are_not_coerced(self) -> None:
        value: object
        for value in (True, False, 1.5, 1.0, 2**63, -(2**63) - 1, [], {}):
            with (
                self.subTest(value=value),
                self.assertRaisesRegex(GenerationError, "64-bit integer"),
            ):
                schema(
                    {
                        "enums": [
                            {
                                "enumType": "Bad",
                                "enumIdentifiers": [{"enumIdentifier": "X", "enumValue": value}],
                            }
                        ]
                    }
                )

    def test_bad_root_json_and_required_properties(self) -> None:
        for data in (
            b"{",
            b"null",
            b"[]",
            b'"text"',
            b"123",
            b"true",
            b"\xff",
            b'{"requests":[{}]}',
            b'{"requests":[null]}',
        ):
            with self.subTest(data=data), self.assertRaisesRegex(GenerationError, "offending.json"):
                parse_schema(data, PATH)
        for data in (b"{}", b'{"repository":false}', b"null"):
            with (
                self.subTest(data=data),
                self.assertRaisesRegex(GenerationError, "schema provenance"),
            ):
                parse_provenance(data, PATH)

    def test_wrong_modeled_types(self) -> None:
        invalid = [
            {"requests": "bad"},
            {"requests": [1]},
            {"requests": [{"requestType": 1}]},
            {"requests": [{"requestType": "X", "deprecated": 0}]},
            {
                "requests": [
                    {
                        "requestType": "X",
                        "requestFields": [
                            {"valueName": "x", "valueType": "String", "valueRestrictions": False}
                        ],
                    }
                ]
            },
            {"events": [{"eventType": "E", "description": None}]},
        ]
        for value in invalid:
            with self.subTest(value=value), self.assertRaises(GenerationError):
                schema(value)
        with self.assertRaises(GenerationError):
            parse_overrides(b'{"nullableFields":false}', PATH)

    def test_duplicate_keys_and_nonstandard_numbers(self) -> None:
        for data in (
            b'{"requests":[],"requests":[]}',
            b'{"unused":NaN}',
            b'{"unused":Infinity}',
            b'{"unused":-Infinity}',
        ):
            with self.subTest(data=data), self.assertRaises(GenerationError):
                parse_schema(data, PATH)
