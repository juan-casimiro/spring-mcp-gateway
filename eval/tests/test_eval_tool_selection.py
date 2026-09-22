"""Unit tests for the project-owned logic in eval_tool_selection.py: env-file
parsing, scoring, and the error/accuracy separation. The MCP handshake and
Anthropic API calls are the official SDKs' responsibility, not tested here —
exercised instead by actually running the script against a live gateway."""
import sys
import unittest
from pathlib import Path
from tempfile import NamedTemporaryFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from eval_tool_selection import compute_stats, exit_code_for, load_env_file, verdict


def _entry(id_, trap_class, expected, actual, verdict_, error=None):
    entry = {
        "id": id_, "trap_class": trap_class, "question": "q",
        "expected_tool_call": expected, "actual_tool_call": actual, "verdict": verdict_,
    }
    if error is not None:
        entry["error"] = error
    return entry


class LoadEnvFileTests(unittest.TestCase):
    def test_missing_file_returns_empty(self):
        self.assertEqual(load_env_file(Path("/nonexistent/.env")), {})

    def test_parses_key_value_and_skips_comments_and_blanks(self):
        with NamedTemporaryFile("w", suffix=".env", delete=False) as f:
            f.write("# comment\n\nANTHROPIC_API_KEY=sk-test-value\nOTHER=\"quoted\"\n")
            path = Path(f.name)
        try:
            values = load_env_file(path)
            self.assertEqual(values["ANTHROPIC_API_KEY"], "sk-test-value")
            self.assertEqual(values["OTHER"], "quoted")
        finally:
            path.unlink()


class VerdictTests(unittest.TestCase):
    def test_pass_when_expected_matches_actual(self):
        self.assertEqual(verdict(True, True), "pass")
        self.assertEqual(verdict(False, False), "pass")

    def test_fail_when_expected_does_not_match_actual(self):
        self.assertEqual(verdict(True, False), "fail")
        self.assertEqual(verdict(False, True), "fail")


class ComputeStatsTests(unittest.TestCase):
    def test_accuracy_excludes_errored_entries_from_denominator(self):
        results = [
            _entry("t01", "trap_a", True, True, "pass"),
            _entry("t02", "trap_a", True, False, "fail"),
            _entry("t03", "trap_b", False, None, "error", error="timed out"),
        ]
        stats = compute_stats(results)
        self.assertEqual(stats["total_questions"], 3)
        self.assertEqual(stats["evaluated"], 2)
        self.assertEqual(stats["correct"], 1)
        self.assertAlmostEqual(stats["accuracy"], 0.5)
        self.assertEqual(len(stats["errored"]), 1)
        self.assertEqual(len(stats["misroutes"]), 1)
        # trap_b had only an errored entry, so it contributes no scored total.
        self.assertNotIn("trap_b", stats["by_trap"])

    def test_all_errored_run_is_not_a_clean_zero_score(self):
        """Regression: a run where every request fails must not present as
        a completed 0/N evaluation — 'evaluated' must be 0, not N."""
        results = [
            _entry("t01", "trap_a", True, None, "error", error="boom"),
            _entry("t02", "trap_a", False, None, "error", error="boom"),
        ]
        stats = compute_stats(results)
        self.assertEqual(stats["total_questions"], 2)
        self.assertEqual(stats["evaluated"], 0)
        self.assertEqual(stats["accuracy"], 0.0)
        self.assertEqual(len(stats["errored"]), 2)

    def test_no_results_is_zero_accuracy_not_a_crash(self):
        stats = compute_stats([])
        self.assertEqual(stats["evaluated"], 0)
        self.assertEqual(stats["accuracy"], 0.0)


class ExitCodeForTests(unittest.TestCase):
    def test_zero_when_every_question_was_scored(self):
        results = [
            _entry("t01", "trap_a", True, True, "pass"),
            _entry("t02", "trap_a", True, False, "fail"),
        ]
        self.assertEqual(exit_code_for(results), 0)

    def test_nonzero_when_any_question_errored(self):
        results = [
            _entry("t01", "trap_a", True, True, "pass"),
            _entry("t02", "trap_a", True, None, "error", error="boom"),
        ]
        self.assertEqual(exit_code_for(results), 1)

    def test_nonzero_when_every_question_errored(self):
        results = [_entry("t01", "trap_a", True, None, "error", error="boom")]
        self.assertEqual(exit_code_for(results), 1)


if __name__ == "__main__":
    unittest.main()
