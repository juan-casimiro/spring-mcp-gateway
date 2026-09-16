"""CLI-level tests for quality/compare_baseline.py.

These exercise the actual subprocess entry point (exit codes, stdout JSON),
distinct from test_baseline.py's direct calls into the comparison library.
"""
import json
from pathlib import Path
import subprocess
import sys
import unittest


QUALITY_DIR = Path(__file__).resolve().parent.parent
FIXTURES = QUALITY_DIR / "tests" / "fixtures"


def run_cli(*args):
    return subprocess.run([sys.executable, str(QUALITY_DIR / "compare_baseline.py"), *args],
                           capture_output=True, text=True)


class CompareBaselineCliTest(unittest.TestCase):
    def test_reports_a_real_comparison_as_json_on_stdout(self):
        result = run_cli(str(FIXTURES / "coverage_decrease" / "head"), str(FIXTURES / "coverage_decrease" / "base"))
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(report["baseline_status"], "ok")
        self.assertTrue(report["coverage"]["LINE"]["warning"])

    def test_omitted_base_dir_is_a_missing_baseline_not_an_error(self):
        result = run_cli(str(FIXTURES / "missing_baseline" / "head"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["baseline_status"], "missing")

    def test_nonexistent_base_dir_is_treated_as_missing_not_an_error(self):
        result = run_cli(str(FIXTURES / "missing_baseline" / "head"), str(FIXTURES / "missing_baseline" / "nope"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["baseline_status"], "missing")

    def test_no_arguments_fails_with_usage_and_nonzero_exit(self):
        result = run_cli()
        self.assertNotEqual(result.returncode, 0)

    def test_malformed_snapshot_fails_loudly_instead_of_a_silent_report(self):
        result = run_cli(str(FIXTURES))  # a directory with no coverage.csv/etc. of its own
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertIn("Error", result.stderr)


if __name__ == "__main__":
    unittest.main()
