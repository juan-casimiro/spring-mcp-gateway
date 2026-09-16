"""Deterministic-fixture tests for quality/baseline.py's comparison logic.

Run with: python3 -m unittest discover -s quality/tests
Each fixture under quality/tests/fixtures/ is real summarize.py-shaped output
(coverage.csv, hotspots.csv, complexity.csv, metadata.json), not an in-memory
stand-in, so these tests also exercise read_snapshot's CSV/JSON parsing.
"""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from baseline import UNAVAILABLE, compare, read_snapshot  # noqa: E402


FIXTURES = Path(__file__).resolve().parent / "fixtures"


def load(case, side):
    return read_snapshot(FIXTURES / case / side)


def method(entries, name):
    matches = [entry for entry in entries if entry["method"] == name]
    assert len(matches) == 1, f"expected exactly one method named {name!r}, found {len(matches)}"
    return matches[0]


class ReadSnapshotTest(unittest.TestCase):
    def test_parses_csv_values_as_numbers_not_strings(self):
        snapshot = load("coverage_decrease", "base")
        self.assertEqual(snapshot["coverage"]["LINE"], {"covered": 90, "missed": 10, "percent": 90.0})
        widget_render = snapshot["methods"][("com.example.Widget", "render", "()V")]
        self.assertEqual(widget_render["jacoco_cyclomatic"], 3)
        self.assertEqual(snapshot["metadata"]["commit"], "base0001")


class CoverageDecreaseTest(unittest.TestCase):
    def setUp(self):
        self.result = compare(load("coverage_decrease", "base"), load("coverage_decrease", "head"))

    def test_baseline_is_ok(self):
        self.assertEqual(self.result["baseline_status"], "ok")
        self.assertEqual(self.result["base_commit"], "base0001")
        self.assertEqual(self.result["head_commit"], "head0001")

    def test_line_coverage_decrease_is_reported_and_warned(self):
        line = self.result["coverage"]["LINE"]
        self.assertEqual(line, {"base": 90.0, "head": 85.0, "delta": -5.0, "warning": True})

    def test_unchanged_branch_coverage_does_not_warn(self):
        self.assertFalse(self.result["coverage"]["BRANCH"]["warning"])

    def test_warnings_mention_line_coverage(self):
        self.assertTrue(any("LINE coverage decreased" in warning for warning in self.result["warnings"]))


class ChangedMethodComplexityIncreaseTest(unittest.TestCase):
    def setUp(self):
        self.result = compare(load("changed_method_complexity_increase", "base"),
                               load("changed_method_complexity_increase", "head"))

    def test_method_is_classified_as_changed_with_positive_deltas(self):
        paint = method(self.result["methods"], "paint")
        self.assertEqual(paint["status"], "changed")
        self.assertEqual(paint["jacoco_cyclomatic"], {"base": 2, "head": 6, "delta": 4})
        self.assertEqual(paint["crap_style"]["delta"], 4.0)
        self.assertTrue(paint["crap_style"]["warning"])

    def test_pmd_cyclomatic_increase_is_classified_as_changed_and_warned(self):
        pmd_entries = [entry for entry in self.result["pmd"] if entry["method"] == "paint"]
        self.assertEqual(len(pmd_entries), 1)
        entry = pmd_entries[0]
        self.assertEqual(entry["status"], "changed")
        self.assertEqual(entry["value"], {"base": 2, "head": 6, "delta": 4, "warning": True})

    def test_warnings_cover_both_signals(self):
        self.assertEqual(len(self.result["warnings"]), 2)


class NewMethodTest(unittest.TestCase):
    def setUp(self):
        self.result = compare(load("new_method", "base"), load("new_method", "head"))

    def test_new_method_is_classified_separately_from_changed(self):
        resize = method(self.result["methods"], "resize")
        self.assertEqual(resize["status"], "new")
        self.assertEqual(resize["jacoco_cyclomatic"], {"base": UNAVAILABLE, "head": 8, "delta": UNAVAILABLE})

    def test_new_method_never_warns_regardless_of_its_own_complexity(self):
        resize = method(self.result["methods"], "resize")
        self.assertFalse(resize["crap_style"]["warning"])
        pmd_entry = next(entry for entry in self.result["pmd"] if entry["method"] == "resize")
        self.assertEqual(pmd_entry["status"], "new")
        self.assertFalse(pmd_entry["value"]["warning"])

    def test_unchanged_existing_method_is_still_reported_as_changed_with_zero_delta(self):
        paint = method(self.result["methods"], "paint")
        self.assertEqual(paint["status"], "changed")
        self.assertEqual(paint["crap_style"]["delta"], 0.0)
        self.assertFalse(paint["crap_style"]["warning"])

    def test_new_code_produces_no_warnings(self):
        self.assertEqual(self.result["warnings"], [])


class MissingBaselineTest(unittest.TestCase):
    def setUp(self):
        self.result = compare(None, load("missing_baseline", "head"))

    def test_baseline_status_is_missing(self):
        self.assertEqual(self.result["baseline_status"], "missing")
        self.assertIsNone(self.result["base_commit"])
        self.assertEqual(self.result["head_commit"], "head0004")

    def test_coverage_is_unavailable_not_zero(self):
        line = self.result["coverage"]["LINE"]
        self.assertEqual(line["base"], UNAVAILABLE)
        self.assertEqual(line["delta"], UNAVAILABLE)
        self.assertEqual(line["head"], 100.0)
        self.assertFalse(line["warning"])

    def test_methods_are_unavailable_not_silently_dropped(self):
        paint = method(self.result["methods"], "paint")
        self.assertEqual(paint["status"], UNAVAILABLE)
        self.assertEqual(paint["crap_style"]["base"], UNAVAILABLE)
        self.assertEqual(paint["crap_style"]["head"], 2.0)

    def test_no_warnings_without_a_baseline(self):
        self.assertEqual(self.result["warnings"], [])


class IncompatibleMeasurementsTest(unittest.TestCase):
    def setUp(self):
        self.result = compare(load("incompatible_measurements", "base"), load("incompatible_measurements", "head"))

    def test_baseline_status_is_incompatible(self):
        self.assertEqual(self.result["baseline_status"], "incompatible")

    def test_mismatch_is_named_explicitly(self):
        self.assertEqual(self.result["toolchain_mismatches"],
                          ["jacoco_plugin_version differs: base='0.8.13' head='0.8.14'"])

    def test_large_apparent_coverage_and_complexity_swings_are_not_computed(self):
        # base=100% head=80% would be a real 20pp drop if comparable, and
        # paint's complexity apparently rose from 2 to 9 - neither may be
        # reported as a numeric delta when the toolchain itself changed.
        line = self.result["coverage"]["LINE"]
        self.assertEqual(line["base"], UNAVAILABLE)
        self.assertEqual(line["delta"], UNAVAILABLE)
        paint = method(self.result["methods"], "paint")
        self.assertEqual(paint["status"], UNAVAILABLE)
        self.assertEqual(paint["jacoco_cyclomatic"]["delta"], UNAVAILABLE)

    def test_no_warnings_when_incompatible(self):
        self.assertEqual(self.result["warnings"], [])


if __name__ == "__main__":
    unittest.main()
