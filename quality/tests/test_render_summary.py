"""Tests for quality/render_summary.py's Markdown rendering."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from baseline import compare, read_snapshot  # noqa: E402
from render_summary import render  # noqa: E402


FIXTURES = Path(__file__).resolve().parent / "fixtures"


def load(case, side):
    return read_snapshot(FIXTURES / case / side)


class RenderCoverageDecreaseTest(unittest.TestCase):
    def setUp(self):
        self.markdown = render(compare(load("coverage_decrease", "base"), load("coverage_decrease", "head")))

    def test_shows_commits_and_ok_status(self):
        self.assertIn("Base: `base0001`", self.markdown)
        self.assertIn("Head: `head0001`", self.markdown)
        self.assertIn("Baseline status: **ok**", self.markdown)

    def test_marks_the_regressed_counter_and_lists_it_as_a_warning(self):
        self.assertIn("| LINE | 90.00% | 85.00% | -5.00pp :warning: |", self.markdown)
        self.assertIn("- :warning: LINE coverage decreased from 90.00% to 85.00%", self.markdown)

    def test_unchanged_counter_has_no_warning_marker(self):
        self.assertIn("| BRANCH | 90.00% | 90.00% | +0.00pp |", self.markdown)

    def test_unchanged_method_is_filtered_out_of_the_table(self):
        self.assertIn("_No changed, new, or removed methods with a coverage/complexity delta._", self.markdown)


class RenderNewMethodTest(unittest.TestCase):
    def setUp(self):
        self.markdown = render(compare(load("new_method", "base"), load("new_method", "head")))

    def test_new_method_is_shown_with_unavailable_base_and_no_warning(self):
        self.assertIn("| com.example.Widget | `resize(II)V` | new | unavailable | 8.000 | unavailable |",
                       self.markdown)
        self.assertNotIn(":warning:", self.markdown.split("### Warnings")[0].split("Method complexity")[1])

    def test_unchanged_existing_method_does_not_appear_in_the_table(self):
        self.assertNotIn("paint", self.markdown)

    def test_warnings_section_says_none(self):
        self.assertIn("### Warnings\n\nNone.", self.markdown)


class RenderMissingBaselineTest(unittest.TestCase):
    def setUp(self):
        self.markdown = render(compare(None, load("missing_baseline", "head")))

    def test_explains_the_missing_baseline(self):
        self.assertIn("Baseline status: **missing**", self.markdown)
        self.assertIn("No comparable baseline could be found or measured", self.markdown)

    def test_unavailable_values_render_as_the_literal_word_not_zero(self):
        self.assertIn("| LINE | unavailable | 100.00% | unavailable |", self.markdown)
        self.assertNotIn("| LINE | 0", self.markdown)

    def test_no_warnings_without_a_baseline(self):
        self.assertIn("### Warnings\n\nNone.", self.markdown)


class RenderIncompatibleMeasurementsTest(unittest.TestCase):
    def setUp(self):
        self.markdown = render(compare(load("incompatible_measurements", "base"),
                                        load("incompatible_measurements", "head")))

    def test_explains_the_toolchain_mismatch(self):
        self.assertIn("Baseline status: **incompatible**", self.markdown)
        self.assertIn("jacoco_plugin_version differs: base='0.8.13' head='0.8.14'", self.markdown)

    def test_does_not_show_a_misleading_numeric_delta(self):
        self.assertIn("| LINE | unavailable | 80.00% | unavailable |", self.markdown)


if __name__ == "__main__":
    unittest.main()
