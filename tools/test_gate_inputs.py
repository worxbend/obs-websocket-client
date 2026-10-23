"""Gate scripts read the same inputs the build and generator accept."""

import unittest

from check_dependencies import summarize_drift
from check_generation import schema_checksum


class SchemaChecksumTests(unittest.TestCase):
    def test_reads_generator_key(self):
        self.assertEqual(schema_checksum({"sha256": "ab"}), "ab")

    def test_rejects_keys_generator_rejects(self):
        for provenance in ({"schemaSha256": "ab"}, {"schema": {"sha256": "ab"}}, {"sha256": 1}):
            with self.subTest(provenance=provenance), self.assertRaises(SystemExit):
                schema_checksum(provenance)


class DriftSummaryTests(unittest.TestCase):
    def test_reports_removed_added_changed_per_module(self):
        before = {
            "core": [{"coordinate": "a:a:1"}, {"coordinate": "b:b:1", "license": "MIT"}],
            "gone": [{"coordinate": "c:c:1"}],
        }
        after = {
            "core": [{"coordinate": "b:b:1", "license": "Apache-2.0"}, {"coordinate": "d:d:1"}],
        }
        self.assertEqual(
            summarize_drift(before, after),
            [
                "  core: removed a:a:1",
                "  core: added d:d:1",
                "  core: changed b:b:1",
                "  gone: removed c:c:1",
            ],
        )

    def test_identical_modules_report_nothing(self):
        modules = {"core": [{"coordinate": "a:a:1"}]}
        self.assertEqual(summarize_drift(modules, modules), [])


if __name__ == "__main__":
    unittest.main()
