"""
Structured Test Execution Adapter & Metric Extractor.
Executes Python unittest suites, captures exact counts, and produces machine-readable metrics.
Standard library only.
"""
from dataclasses import dataclass
import argparse
import importlib.util
import json
import os
from pathlib import Path
import sys
import unittest
from typing import Optional

SUMMARY_PREFIX = "TEST_SUMMARY_JSON:"

@dataclass
class StructuredTestResult:
    total: int
    passed: int
    failures: int
    errors: int
    skipped: int
    expected_failures: int
    unexpected_successes: int
    exit_code: int
    diagnostic: str = ""

class CountingTestResult(unittest.TestResult):
    def __init__(self):
        super().__init__()
        self.passed = 0
        self.num_total = 0

    def startTest(self, test):
        super().startTest(test)
        self.num_total += 1

    def addSuccess(self, test):
        super().addSuccess(test)
        self.passed += 1

def _check_metric_strict(name: str, val) -> Optional[str]:
    if isinstance(val, bool) or not isinstance(val, int):
        return f"metric '{name}' must be an integer, got {type(val).__name__} ({val})"
    if val < 0:
        return f"metric '{name}' cannot be negative ({val})"
    return None

def parse_test_summary(
    summary: dict,
    allow_skipped: bool = False,
    allow_expected_failures: bool = False
) -> tuple[str, str]:
    """
    Evaluates test summary metrics and returns (status, diagnostic).
    Status is 'PASS' or 'FAIL'.
    """
    if not isinstance(summary, dict):
        raise ValueError("Test summary must be a dictionary")

    required_keys = ["total", "passed", "failures", "errors", "skipped"]
    for k in required_keys:
        if k not in summary:
            raise ValueError(f"Missing required metric in test summary: '{k}'")

    for k, val in summary.items():
        err = _check_metric_strict(k, val)
        if err:
            raise ValueError(err)

    total = summary["total"]
    passed = summary["passed"]
    failures = summary["failures"]
    errors = summary["errors"]
    skipped = summary["skipped"]
    expected_failures = summary.get("expected_failures", 0)
    unexpected_successes = summary.get("unexpected_successes", 0)

    if total == 0:
        return "FAIL", "Zero tests discovered"

    if not allow_skipped and skipped > 0:
        return "FAIL", f"Disallowed skipped tests: {skipped}"

    if not allow_expected_failures and expected_failures > 0:
        return "FAIL", f"Unapproved expected failures: {expected_failures}"

    if unexpected_successes > 0:
        return "FAIL", f"Unexpected successes: {unexpected_successes}"

    if failures > 0 or errors > 0:
        return "FAIL", f"Failures: {failures}, Errors: {errors}"

    return "PASS", "All tests passed"

def validate_test_summary_integrity(summary: dict, exit_code: int) -> tuple[bool, str]:
    """
    Validates mathematical consistency of test execution counts and checks
    for contradictions with child process exit code.
    """
    if not isinstance(summary, dict):
        return False, "Test summary must be a dictionary"

    required_keys = ["total", "passed", "failures", "errors", "skipped"]
    for k in required_keys:
        if k not in summary:
            return False, f"Missing required test summary key '{k}'"

    for k, val in summary.items():
        err = _check_metric_strict(k, val)
        if err:
            return False, err

    total = summary["total"]
    passed = summary["passed"]
    failures = summary["failures"]
    errors = summary["errors"]
    skipped = summary["skipped"]
    expected_failures = summary.get("expected_failures", 0)
    unexpected_successes = summary.get("unexpected_successes", 0)

    computed_total = passed + failures + errors + skipped + expected_failures + unexpected_successes
    if total != computed_total:
        return False, f"Inconsistent execution counts: total={total} != sum of parts={computed_total}"

    has_defects = (failures > 0 or errors > 0 or total == 0 or unexpected_successes > 0)
    if exit_code == 0 and has_defects:
        return False, f"Exit-code contradiction: exit_code is 0 but test summary indicates failures/defects"

    if exit_code != 0 and (not has_defects and skipped == 0 and expected_failures == 0):
        return False, f"Exit-code contradiction: exit_code is {exit_code} but test summary indicates all tests passed"

    return True, "Integrity verified"

def extract_test_summary_from_output(output: bytes) -> Optional[dict[str, int]]:
    """
    Searches output bytes for TEST_SUMMARY_JSON: marker and parses the dictionary.
    """
    text = output.decode("utf-8", errors="replace")
    for line in text.splitlines():
        if line.startswith(SUMMARY_PREFIX):
            payload = line[len(SUMMARY_PREFIX):].strip()
            try:
                data = json.loads(payload)
                if isinstance(data, dict):
                    return data
            except json.JSONDecodeError:
                return None
    return None

def run_synthetic_suite(suite_path: Path) -> StructuredTestResult:
    """
    Loads and executes a unittest suite file directly in Python, returning StructuredTestResult.
    """
    module_name = suite_path.stem
    spec = importlib.util.spec_from_file_location(module_name, str(suite_path))
    if not spec or not spec.loader:
        raise ValueError(f"Could not load module from {suite_path}")

    mod = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = mod
    spec.loader.exec_module(mod)

    loader = unittest.TestLoader()
    suite = loader.loadTestsFromModule(mod)

    result = CountingTestResult()
    suite.run(result)

    total = result.num_total
    passed = result.passed
    failures = len(result.failures)
    errors = len(result.errors)
    skipped = len(result.skipped)
    expected_failures = len(result.expectedFailures)
    unexpected_successes = len(result.unexpectedSuccesses)

    has_failure = (failures > 0 or errors > 0 or total == 0 or unexpected_successes > 0 or skipped > 0)
    exit_code = 1 if has_failure else 0

    diag_lines = []
    for test, tb in result.failures:
        diag_lines.append(f"FAIL: {test.id()}\n{tb}")
    for test, tb in result.errors:
        diag_lines.append(f"ERROR: {test.id()}\n{tb}")
    diagnostic = "\n".join(diag_lines)

    return StructuredTestResult(
        total=total,
        passed=passed,
        failures=failures,
        errors=errors,
        skipped=skipped,
        expected_failures=expected_failures,
        unexpected_successes=unexpected_successes,
        exit_code=exit_code,
        diagnostic=diagnostic
    )

def main():
    parser = argparse.ArgumentParser(description="Structured unittest test runner adapter")
    parser.add_argument("--discover", type=str, default=".", help="Directory to discover tests from")
    parser.add_argument("--pattern", type=str, default="test_*.py", help="Test file pattern")
    parser.add_argument("--top-level", type=str, default=None, help="Top-level directory of project")
    parser.add_argument("--allow-skipped", action="store_true", help="Allow skipped tests")
    parser.add_argument("--allow-expected-failures", action="store_true", help="Allow expected failures")
    args = parser.parse_args()

    top_level = args.top_level or os.getcwd()
    top_level_path = str(Path(top_level).resolve())
    if top_level_path not in sys.path:
        sys.path.insert(0, top_level_path)

    loader = unittest.TestLoader()
    start_dir = args.discover
    pattern = args.pattern

    suite = loader.discover(start_dir=start_dir, pattern=pattern, top_level_dir=top_level_path)
    result = CountingTestResult()
    suite.run(result)

    total = result.num_total
    passed = result.passed
    failures = len(result.failures)
    errors = len(result.errors)
    skipped = len(result.skipped)
    expected_failures = len(result.expectedFailures)
    unexpected_successes = len(result.unexpectedSuccesses)

    summary = {
        "total": total,
        "passed": passed,
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
        "expected_failures": expected_failures,
        "unexpected_successes": unexpected_successes
    }

    # Print failures and errors detail if any
    for test, tb in result.failures:
        print(f"\n=======================================================\nFAIL: {test.id()}\n-------------------------------------------------------\n{tb}", file=sys.stderr)
    for test, tb in result.errors:
        print(f"\n=======================================================\nERROR: {test.id()}\n-------------------------------------------------------\n{tb}", file=sys.stderr)

    # Print human-readable summary
    print(f"Ran {total} tests: {passed} passed, {failures} failed, {errors} errors, {skipped} skipped")

    # Print machine-readable marker for runner
    print(f"{SUMMARY_PREFIX} {json.dumps(summary)}")

    status, diag = parse_test_summary(
        summary,
        allow_skipped=args.allow_skipped,
        allow_expected_failures=args.allow_expected_failures
    )

    if status != "PASS":
        print(f"Adapter Gate Blocked: {diag}", file=sys.stderr)
        sys.exit(1)

    sys.exit(0)

if __name__ == "__main__":
    main()
