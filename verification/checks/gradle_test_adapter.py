"""
Gradle Test Execution Adapter & Structured JUnit Metric Extractor.
Executes Gradle JVM unit test tasks, parses fresh JUnit XML reports,
validates test counts and outcomes, and emits the structured test-summary contract.
Standard library only. Zero third-party dependencies.
"""
from dataclasses import dataclass
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from typing import Optional
import xml.etree.ElementTree as ET

# Ensure repository root is on sys.path for direct script execution without PYTHONPATH
_repo_root = Path(__file__).resolve().parent.parent.parent
if str(_repo_root) not in sys.path:
    sys.path.insert(0, str(_repo_root))

from verification.checks.test_adapter import SUMMARY_PREFIX, parse_test_summary

@dataclass
class TestCaseResult:
    classname: str
    name: str
    outcome: str  # "PASS", "FAIL", "ERROR", "SKIPPED"
    message: str = ""
    diagnostic: str = ""

@dataclass
class SuiteSummary:
    total: int
    passed: int
    failures: int
    errors: int
    skipped: int
    test_cases: list[TestCaseResult]
    diagnostics: list[str]

def parse_junit_xml_file(xml_path: Path, seen_test_ids: set[str]) -> SuiteSummary:
    """
    Parses a single JUnit XML file.
    Validates XML integrity, extracts test cases, enforces count consistency,
    and detects duplicate test identities across executions.
    """
    try:
        content = xml_path.read_text(encoding="utf-8")
        root = ET.fromstring(content)
    except Exception as e:
        raise ValueError(f"Malformed JUnit XML in {xml_path.name}: {e}")

    # Root can be <testsuite> or <testsuites>
    testsuites = []
    if root.tag == "testsuite":
        testsuites.append(root)
    elif root.tag == "testsuites":
        testsuites.extend(root.findall("testsuite"))
    else:
        raise ValueError(f"Invalid root tag '<{root.tag}>' in {xml_path.name}, expected <testsuite> or <testsuites>")

    total = 0
    passed = 0
    failures = 0
    errors = 0
    skipped = 0
    test_cases: list[TestCaseResult] = []
    diagnostics: list[str] = []

    for suite in testsuites:
        # Check declared counts if present
        decl_tests = suite.attrib.get("tests")
        decl_failures = suite.attrib.get("failures")
        decl_errors = suite.attrib.get("errors")
        decl_skipped = suite.attrib.get("skipped") or suite.attrib.get("ignored")

        suite_total = 0
        suite_passed = 0
        suite_failures = 0
        suite_errors = 0
        suite_skipped = 0

        for tc in suite.findall("testcase"):
            classname = tc.attrib.get("classname", "").strip()
            name = tc.attrib.get("name", "").strip()
            if not classname or not name:
                raise ValueError(f"Testcase missing classname or name in {xml_path.name}")

            test_id = f"{classname}#{name}"
            if test_id in seen_test_ids:
                raise ValueError(f"Duplicate test identity detected: '{test_id}' in {xml_path.name}")
            seen_test_ids.add(test_id)

            suite_total += 1

            # Check child elements for failure, error, skipped
            fail_el = tc.find("failure")
            err_el = tc.find("error")
            skip_el = tc.find("skipped")

            if fail_el is not None:
                suite_failures += 1
                msg = fail_el.attrib.get("message", "")
                text = (fail_el.text or "").strip()
                diag = f"FAIL: {test_id} - {msg}\n{text}".strip()
                diagnostics.append(diag)
                test_cases.append(TestCaseResult(classname=classname, name=name, outcome="FAIL", message=msg, diagnostic=diag))
            elif err_el is not None:
                suite_errors += 1
                msg = err_el.attrib.get("message", "")
                text = (err_el.text or "").strip()
                diag = f"ERROR: {test_id} - {msg}\n{text}".strip()
                diagnostics.append(diag)
                test_cases.append(TestCaseResult(classname=classname, name=name, outcome="ERROR", message=msg, diagnostic=diag))
            elif skip_el is not None:
                suite_skipped += 1
                msg = skip_el.attrib.get("message", "Skipped test")
                diag = f"SKIPPED: {test_id} - {msg}".strip()
                diagnostics.append(diag)
                test_cases.append(TestCaseResult(classname=classname, name=name, outcome="SKIPPED", message=msg, diagnostic=diag))
            else:
                suite_passed += 1
                test_cases.append(TestCaseResult(classname=classname, name=name, outcome="PASS"))

        # Verify declared vs child element counts
        if decl_tests is not None and int(decl_tests) != suite_total:
            raise ValueError(f"Contradictory test count in {xml_path.name}: declared tests={decl_tests} != parsed {suite_total}")
        if decl_failures is not None and int(decl_failures) != suite_failures:
            raise ValueError(f"Contradictory failure count in {xml_path.name}: declared failures={decl_failures} != parsed {suite_failures}")
        if decl_errors is not None and int(decl_errors) != suite_errors:
            raise ValueError(f"Contradictory error count in {xml_path.name}: declared errors={decl_errors} != parsed {suite_errors}")
        if decl_skipped is not None and int(decl_skipped) != suite_skipped:
            raise ValueError(f"Contradictory skipped count in {xml_path.name}: declared skipped={decl_skipped} != parsed {suite_skipped}")

        total += suite_total
        passed += suite_passed
        failures += suite_failures
        errors += suite_errors
        skipped += suite_skipped

    return SuiteSummary(
        total=total,
        passed=passed,
        failures=failures,
        errors=errors,
        skipped=skipped,
        test_cases=test_cases,
        diagnostics=diagnostics
    )

def collect_and_parse_results(
    results_dir: Path,
    min_mtime: Optional[float] = None
) -> SuiteSummary:
    """
    Finds all JUnit XML files in results_dir, validates freshness against min_mtime,
    and aggregates parsed suite summaries.
    """
    if not results_dir.is_dir():
        raise FileNotFoundError(f"Test results directory does not exist: {results_dir}")

    xml_files = sorted(results_dir.glob("TEST-*.xml"))
    if not xml_files:
        xml_files = sorted(results_dir.glob("*.xml"))

    if not xml_files:
        raise ValueError(f"No JUnit XML test result files discovered in {results_dir}")

    total = 0
    passed = 0
    failures = 0
    errors = 0
    skipped = 0
    all_cases: list[TestCaseResult] = []
    all_diags: list[str] = []
    seen_test_ids: set[str] = set()

    for xf in xml_files:
        if min_mtime is not None:
            # File modification time check (with 1.0s clock tolerance)
            mtime = xf.stat().st_mtime
            if mtime < (min_mtime - 1.0):
                raise ValueError(f"Stale test result detected: '{xf.name}' modified at {mtime} before execution start {min_mtime}")

        summary = parse_junit_xml_file(xf, seen_test_ids)
        total += summary.total
        passed += summary.passed
        failures += summary.failures
        errors += summary.errors
        skipped += summary.skipped
        all_cases.extend(summary.test_cases)
        all_diags.extend(summary.diagnostics)

    if total == 0:
        raise ValueError(f"Zero tests discovered across {len(xml_files)} result file(s) in {results_dir}")

    return SuiteSummary(
        total=total,
        passed=passed,
        failures=failures,
        errors=errors,
        skipped=skipped,
        test_cases=all_cases,
        diagnostics=all_diags
    )

def execute_gradle_task(
    cmd: list[str],
    results_dir: Path,
    clean_before: bool = True
) -> tuple[int, bytes, float]:
    """
    Cleans previous test results (if requested), records start timestamp,
    executes the Gradle command preserving its exit code, and returns (exit_code, output, start_time).
    """
    if clean_before and results_dir.is_dir():
        for old_xml in results_dir.glob("TEST-*.xml"):
            try:
                old_xml.unlink()
            except OSError as e:
                sys.stderr.write(f"[ADAPTER ERROR] Cleanup failure removing {old_xml}: {e}\n")
                return 1, f"Cleanup failure: {e}".encode("utf-8"), time.time()

    start_time = time.time()
    task_env = os.environ.copy()
    if "ANDROID_HOME" not in task_env:
        default_sdk = Path.home() / ".android" / "sdk"
        if default_sdk.is_dir():
            task_env["ANDROID_HOME"] = str(default_sdk)

    try:
        proc = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            shell=False,
            env=task_env
        )
        return proc.returncode, proc.stdout, start_time
    except FileNotFoundError as e:
        sys.stderr.write(f"[ADAPTER ERROR] Executable not found: {cmd[0]} ({e})\n")
        return 127, str(e).encode("utf-8"), start_time
    except Exception as e:
        sys.stderr.write(f"[ADAPTER ERROR] Failed to execute {cmd}: {e}\n")
        return 1, str(e).encode("utf-8"), start_time

def run_adapter(
    results_dir: Path,
    cmd: Optional[list[str]] = None,
    allow_skipped: bool = False,
    no_exec: bool = False,
    min_mtime: Optional[float] = None
) -> int:
    """
    Core orchestrator: executes task (unless no_exec), parses XMLs,
    emits structured metrics, and exits with preserved status.
    """
    gradle_exit_code = 0
    exec_start_time = min_mtime

    if not no_exec:
        if not cmd:
            cmd = ["./gradlew", "testDebugUnitTest", "--no-daemon", "--rerun-tasks"]
        gradle_exit_code, output, exec_start_time = execute_gradle_task(cmd, results_dir)
        sys.stdout.buffer.write(output)
        sys.stdout.buffer.flush()

    try:
        summary = collect_and_parse_results(results_dir, min_mtime=exec_start_time)
    except Exception as e:
        sys.stderr.write(f"[ADAPTER ERROR] {e}\n")
        # If gradle failed with non-zero exit code, preserve it; otherwise exit 1
        return gradle_exit_code if gradle_exit_code != 0 else 1

    # Print failure/error diagnostics to stderr
    for diag in summary.diagnostics:
        sys.stderr.write(f"\n{diag}\n")
    sys.stderr.flush()

    # Emit standard human-readable summary
    print(f"\nRan {summary.total} tests: {summary.passed} passed, {summary.failures} failed, {summary.errors} errors, {summary.skipped} skipped")

    # Emit standard machine-readable marker
    summary_dict = {
        "total": summary.total,
        "passed": summary.passed,
        "failures": summary.failures,
        "errors": summary.errors,
        "skipped": summary.skipped,
        "expected_failures": 0,
        "unexpected_successes": 0
    }
    print(f"{SUMMARY_PREFIX} {json.dumps(summary_dict)}")
    sys.stdout.flush()

    # Verify summary integrity via existing harness contract
    status, diag = parse_test_summary(summary_dict, allow_skipped=allow_skipped)
    if status != "PASS":
        sys.stderr.write(f"Adapter Gate Blocked: {diag}\n")
        return gradle_exit_code if gradle_exit_code != 0 else 1

    if gradle_exit_code != 0:
        return gradle_exit_code

    return 0

def main():
    parser = argparse.ArgumentParser(description="Gradle structured test adapter & metric extractor")
    parser.add_argument("--results-dir", type=str, default="app/build/test-results/testDebugUnitTest", help="Path to JUnit XML test results directory")
    parser.add_argument("--task", type=str, default=None, help="Gradle task name to execute (e.g. testDebugUnitTest)")
    parser.add_argument("--gradle-bin", type=str, default="./gradlew", help="Path to Gradle wrapper executable")
    parser.add_argument("--allow-skipped", action="store_true", help="Permit skipped tests")
    parser.add_argument("--no-exec", action="store_true", help="Parse results directory directly without executing command")
    parser.add_argument("cmd_args", nargs="*", help="Optional command arguments to execute instead of default task")

    args = parser.parse_args()

    results_dir = Path(args.results_dir)

    if args.no_exec:
        exit_code = run_adapter(results_dir, no_exec=True, allow_skipped=args.allow_skipped)
        sys.exit(exit_code)

    if args.cmd_args:
        cmd = args.cmd_args
    elif args.task:
        cmd = [args.gradle_bin, args.task, "--no-daemon", "--rerun-tasks"]
    else:
        cmd = [args.gradle_bin, "testDebugUnitTest", "--no-daemon", "--rerun-tasks"]

    exit_code = run_adapter(results_dir, cmd=cmd, allow_skipped=args.allow_skipped)
    sys.exit(exit_code)

if __name__ == "__main__":
    main()
