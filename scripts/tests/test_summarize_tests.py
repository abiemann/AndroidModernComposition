"""Run with python -m unittest discover -s scripts/tests -v."""

from contextlib import redirect_stdout
import importlib.util
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "summarize-tests.py"
SPEC = importlib.util.spec_from_file_location("summarize_tests", SCRIPT)
summary = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(summary)


class SummaryTest(unittest.TestCase):
    def summarize(self, tests, skipped=0, failures=0, errors=0):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reports = root / "domain/build/test-results/test"
            reports.mkdir(parents=True)
            (reports / "TEST-example.xml").write_text(
                f'<testsuite tests="{tests}" skipped="{skipped}" '
                f'failures="{failures}" errors="{errors}"/>', encoding="utf-8",
            )
            output = io.StringIO()
            with patch.object(summary, "ROOT", root), \
                    patch.object(sys, "argv", [str(SCRIPT), "--require-results"]), \
                    redirect_stdout(output):
                status = summary.main()
            return status, output.getvalue()

    def test_entirely_skipped_reports_do_not_pass(self):
        status, output = self.summarize(tests=3, skipped=3)
        self.assertEqual(1, status)
        self.assertIn("No tests executed", output)

    def test_executed_passing_test_with_skips_passes(self):
        status, _ = self.summarize(tests=3, skipped=2)
        self.assertEqual(0, status)

    def test_empty_suite_does_not_pass(self):
        self.assertEqual(1, self.summarize(tests=0)[0])

    def test_failures_and_errors_still_fail(self):
        for counts in ({"failures": 1}, {"errors": 1}):
            with self.subTest(counts=counts):
                self.assertEqual(1, self.summarize(tests=2, **counts)[0])


if __name__ == "__main__":
    unittest.main()
