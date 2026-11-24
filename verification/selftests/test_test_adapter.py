import unittest
import json
import tempfile
import subprocess
import sys
from pathlib import Path
from verification.checks.test_adapter import (
    run_synthetic_suite,
    parse_test_summary,
    validate_test_summary_integrity,
    StructuredTestResult
)

class TestTestAdapter(unittest.TestCase):
    def test_zero_discovered_tests_TEST_INV_001(self):
        summary = {"total": 0, "passed": 0, "failures": 0, "errors": 0, "skipped": 0, "expected_failures": 0, "unexpected_successes": 0}
        status, diagnostic = parse_test_summary(summary)
        self.assertEqual(status, "FAIL")
        self.assertIn("Zero tests discovered", diagnostic)

    def test_disallowed_skipped_tests_TEST_INV_002(self):
        summary = {"total": 5, "passed": 4, "failures": 0, "errors": 0, "skipped": 1, "expected_failures": 0, "unexpected_successes": 0}
        status, diagnostic = parse_test_summary(summary, allow_skipped=False)
        self.assertEqual(status, "FAIL")
        self.assertIn("Disallowed skipped tests", diagnostic)

    def test_accurate_count_extraction_TEST_INV_003(self):
        suite_code = """
import unittest
class SyntheticSuite(unittest.TestCase):
    def test_p1(self): pass
    def test_p2(self): pass
    def test_p3(self): pass
    def test_p4(self): pass
    def test_p5(self): pass
    def test_p6(self): pass
    def test_p7(self): pass
    def test_p8(self): pass
    def test_f1(self): self.fail("fail 1")
    def test_f2(self): self.fail("fail 2")
"""
        with tempfile.TemporaryDirectory() as td:
            f = Path(td) / "test_synth.py"
            f.write_text(suite_code)
            res = run_synthetic_suite(f)
            self.assertEqual(res.total, 10)
            self.assertEqual(res.passed, 8)
            self.assertEqual(res.failures, 2)
            self.assertEqual(res.errors, 0)
            self.assertEqual(res.skipped, 0)
            self.assertEqual(res.exit_code, 1)

    def test_single_passing_test_TEST_INV_004(self):
        suite_code = "import unittest\nclass S(unittest.TestCase):\n    def test_1(self): pass"
        with tempfile.TemporaryDirectory() as td:
            f = Path(td) / "test_one.py"
            f.write_text(suite_code)
            res = run_synthetic_suite(f)
            self.assertEqual(res.total, 1)
            self.assertEqual(res.passed, 1)
            self.assertEqual(res.exit_code, 0)

    def test_assertion_failure_TEST_INV_005(self):
        suite_code = "import unittest\nclass S(unittest.TestCase):\n    def test_fail(self): self.assertEqual(1, 2)"
        with tempfile.TemporaryDirectory() as td:
            f = Path(td) / "test_fail.py"
            f.write_text(suite_code)
            res = run_synthetic_suite(f)
            self.assertEqual(res.failures, 1)
            self.assertEqual(res.exit_code, 1)

    def test_runtime_error_TEST_INV_006(self):
        suite_code = "import unittest\nclass S(unittest.TestCase):\n    def test_err(self): 1/0"
        with tempfile.TemporaryDirectory() as td:
            f = Path(td) / "test_err.py"
            f.write_text(suite_code)
            res = run_synthetic_suite(f)
            self.assertEqual(res.errors, 1)
            self.assertEqual(res.exit_code, 1)

    def test_malformed_test_summary_TEST_INV_007(self):
        with self.assertRaises(ValueError):
            parse_test_summary({"malformed": True})

    def test_unapproved_expected_failures(self):
        summary = {"total": 5, "passed": 4, "failures": 0, "errors": 0, "skipped": 0, "expected_failures": 1, "unexpected_successes": 0}
        status, diagnostic = parse_test_summary(summary, allow_expected_failures=False)
        self.assertEqual(status, "FAIL")
        self.assertIn("Unapproved expected failures", diagnostic)

    def test_unexpected_successes(self):
        summary = {"total": 5, "passed": 4, "failures": 0, "errors": 0, "skipped": 0, "expected_failures": 0, "unexpected_successes": 1}
        status, diagnostic = parse_test_summary(summary)
        self.assertEqual(status, "FAIL")
        self.assertIn("Unexpected successes", diagnostic)

    def test_inconsistent_execution_counts(self):
        # total=10, but passed(5)+failures(2)+errors(1)+skipped(1) = 9 != 10
        summary = {"total": 10, "passed": 5, "failures": 2, "errors": 1, "skipped": 1, "expected_failures": 0, "unexpected_successes": 0}
        ok, diag = validate_test_summary_integrity(summary, exit_code=1)
        self.assertFalse(ok)
        self.assertIn("Inconsistent execution counts", diag)

    def test_exit_code_and_report_status_contradictions(self):
        # exit code 0 but failures > 0
        summary = {"total": 2, "passed": 1, "failures": 1, "errors": 0, "skipped": 0, "expected_failures": 0, "unexpected_successes": 0}
        ok, diag = validate_test_summary_integrity(summary, exit_code=0)
        self.assertFalse(ok)
        self.assertIn("Exit-code contradiction", diag)

        # exit code 1 but all passed
        summary2 = {"total": 2, "passed": 2, "failures": 0, "errors": 0, "skipped": 0, "expected_failures": 0, "unexpected_successes": 0}
        ok2, diag2 = validate_test_summary_integrity(summary2, exit_code=1)
        self.assertFalse(ok2)
        self.assertIn("Exit-code contradiction", diag2)

    def test_cli_invocation_discover_mode(self):
        # Test executing test_adapter.py as a CLI module discovering selftests
        res = subprocess.run(
            [sys.executable, "verification/checks/test_adapter.py", "--discover", "verification/selftests", "--pattern", "test_registry.py"],
            capture_output=True,
            text=True
        )
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertIn("TEST_SUMMARY_JSON:", res.stdout)

if __name__ == "__main__":
    unittest.main()
