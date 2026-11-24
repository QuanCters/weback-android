"""
Selftests for Gradle Structured Test Execution Adapter (gradle_test_adapter.py).
Standard library only. Zero third-party dependencies.
"""
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest

from verification.checks.gradle_test_adapter import (
    collect_and_parse_results,
    parse_junit_xml_file,
    run_adapter
)
from verification.checks.test_adapter import SUMMARY_PREFIX

class TestGradleTestAdapter(unittest.TestCase):
    def test_valid_junit_xml_parsing(self):
        with tempfile.TemporaryDirectory() as td:
            xml_p = Path(td) / "TEST-com.weback.robot.domain.RobotStateTest.xml"
            xml_p.write_text("""<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.weback.robot.domain.RobotStateTest" tests="3" skipped="0" failures="0" errors="0" time="0.05">
  <testcase name="testDefaultValues" classname="com.weback.robot.domain.RobotStateTest" time="0.01"/>
  <testcase name="testStateCopyImmutability" classname="com.weback.robot.domain.RobotStateTest" time="0.02"/>
  <testcase name="testIsDockedPredicate" classname="com.weback.robot.domain.RobotStateTest" time="0.01"/>
</testsuite>
""", encoding="utf-8")
            summary = collect_and_parse_results(Path(td))
            self.assertEqual(summary.total, 3)
            self.assertEqual(summary.passed, 3)
            self.assertEqual(summary.failures, 0)
            self.assertEqual(summary.errors, 0)
            self.assertEqual(summary.skipped, 0)
            self.assertEqual(len(summary.diagnostics), 0)

    def test_multiple_suites_in_testsuites_root(self):
        with tempfile.TemporaryDirectory() as td:
            xml_p = Path(td) / "TEST-aggregated.xml"
            xml_p.write_text("""<?xml version="1.0" encoding="UTF-8"?>
<testsuites>
  <testsuite name="SuiteA" tests="1" failures="0" errors="0" skipped="0">
    <testcase name="testOne" classname="com.weback.SuiteA"/>
  </testsuite>
  <testsuite name="SuiteB" tests="2" failures="0" errors="0" skipped="0">
    <testcase name="testTwo" classname="com.weback.SuiteB"/>
    <testcase name="testThree" classname="com.weback.SuiteB"/>
  </testsuite>
</testsuites>
""", encoding="utf-8")
            summary = collect_and_parse_results(Path(td))
            self.assertEqual(summary.total, 3)
            self.assertEqual(summary.passed, 3)

    def test_detect_test_failure_and_diagnostics(self):
        with tempfile.TemporaryDirectory() as td:
            xml_p = Path(td) / "TEST-com.weback.robot.FailedTest.xml"
            xml_p.write_text("""<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.weback.robot.FailedTest" tests="2" skipped="0" failures="1" errors="0" time="0.05">
  <testcase name="testPass" classname="com.weback.robot.FailedTest" time="0.01"/>
  <testcase name="testFail" classname="com.weback.robot.FailedTest" time="0.02">
    <failure message="expected 100 but got 0" type="java.lang.AssertionError">
java.lang.AssertionError: expected 100 but got 0
    at com.weback.robot.FailedTest.testFail(FailedTest.kt:42)
    </failure>
  </testcase>
</testsuite>
""", encoding="utf-8")
            summary = collect_and_parse_results(Path(td))
            self.assertEqual(summary.total, 2)
            self.assertEqual(summary.passed, 1)
            self.assertEqual(summary.failures, 1)
            self.assertEqual(len(summary.diagnostics), 1)
            self.assertIn("expected 100 but got 0", summary.diagnostics[0])
            self.assertIn("FailedTest.kt:42", summary.diagnostics[0])

    def test_detect_test_error_and_diagnostics(self):
        with tempfile.TemporaryDirectory() as td:
            xml_p = Path(td) / "TEST-com.weback.robot.ErrorTest.xml"
            xml_p.write_text("""<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.weback.robot.ErrorTest" tests="1" skipped="0" failures="0" errors="1" time="0.01">
  <testcase name="testCrash" classname="com.weback.robot.ErrorTest" time="0.01">
    <error message="NullPointerException" type="java.lang.NullPointerException">
java.lang.NullPointerException: container was null
    </error>
  </testcase>
</testsuite>
""", encoding="utf-8")
            summary = collect_and_parse_results(Path(td))
            self.assertEqual(summary.total, 1)
            self.assertEqual(summary.errors, 1)
            self.assertEqual(summary.passed, 0)
            self.assertIn("NullPointerException", summary.diagnostics[0])

    def test_detect_skipped_test(self):
        with tempfile.TemporaryDirectory() as td:
            xml_p = Path(td) / "TEST-com.weback.robot.SkippedTest.xml"
            xml_p.write_text("""<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.weback.robot.SkippedTest" tests="2" skipped="1" failures="0" errors="0" time="0.01">
  <testcase name="testValid" classname="com.weback.robot.SkippedTest" time="0.01"/>
  <testcase name="testIgnored" classname="com.weback.robot.SkippedTest" time="0.00">
    <skipped message="Disabled on CI"/>
  </testcase>
</testsuite>
""", encoding="utf-8")
            summary = collect_and_parse_results(Path(td))
            self.assertEqual(summary.total, 2)
            self.assertEqual(summary.passed, 1)
            self.assertEqual(summary.skipped, 1)

    def test_detect_duplicate_test_identity(self):
        with tempfile.TemporaryDirectory() as td:
            # Two XML files or two testcases reporting same classname and test name
            (Path(td) / "TEST-file1.xml").write_text("""<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="Suite" tests="1" failures="0" errors="0" skipped="0">
  <testcase name="testDuplicate" classname="com.weback.robot.SameClass"/>
</testsuite>
""", encoding="utf-8")
            (Path(td) / "TEST-file2.xml").write_text("""<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="Suite2" tests="1" failures="0" errors="0" skipped="0">
  <testcase name="testDuplicate" classname="com.weback.robot.SameClass"/>
</testsuite>
""", encoding="utf-8")
            with self.assertRaises(ValueError) as ctx:
                collect_and_parse_results(Path(td))
            self.assertIn("Duplicate test identity detected", str(ctx.exception))

    def test_detect_contradictory_test_count(self):
        with tempfile.TemporaryDirectory() as td:
            xml_p = Path(td) / "TEST-contradictory.xml"
            xml_p.write_text("""<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="Suite" tests="5" failures="0" errors="0" skipped="0">
  <testcase name="testOne" classname="com.weback.Suite"/>
</testsuite>
""", encoding="utf-8")
            with self.assertRaises(ValueError) as ctx:
                collect_and_parse_results(Path(td))
            self.assertIn("Contradictory test count", str(ctx.exception))

    def test_detect_contradictory_failure_count(self):
        with tempfile.TemporaryDirectory() as td:
            xml_p = Path(td) / "TEST-contradictory-fail.xml"
            xml_p.write_text("""<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="Suite" tests="1" failures="0" errors="0" skipped="0">
  <testcase name="testOne" classname="com.weback.Suite">
    <failure message="oops"/>
  </testcase>
</testsuite>
""", encoding="utf-8")
            with self.assertRaises(ValueError) as ctx:
                collect_and_parse_results(Path(td))
            self.assertIn("Contradictory failure count", str(ctx.exception))

    def test_detect_malformed_xml(self):
        with tempfile.TemporaryDirectory() as td:
            xml_p = Path(td) / "TEST-malformed.xml"
            xml_p.write_text("<testsuite>unclosed tag", encoding="utf-8")
            with self.assertRaises(ValueError) as ctx:
                collect_and_parse_results(Path(td))
            self.assertIn("Malformed JUnit XML", str(ctx.exception))

    def test_detect_missing_test_results_directory(self):
        with self.assertRaises(FileNotFoundError):
            collect_and_parse_results(Path("/nonexistent/directory/path"))

    def test_detect_zero_tests_discovered(self):
        with tempfile.TemporaryDirectory() as td:
            xml_p = Path(td) / "TEST-empty.xml"
            xml_p.write_text("""<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="Empty" tests="0" failures="0" errors="0" skipped="0"/>
""", encoding="utf-8")
            with self.assertRaises(ValueError) as ctx:
                collect_and_parse_results(Path(td))
            self.assertIn("Zero tests discovered", str(ctx.exception))

    def test_detect_stale_results(self):
        with tempfile.TemporaryDirectory() as td:
            xml_p = Path(td) / "TEST-stale.xml"
            xml_p.write_text("""<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="Stale" tests="1" failures="0" errors="0" skipped="0">
  <testcase name="testOld" classname="com.weback.Stale"/>
</testsuite>
""", encoding="utf-8")
            # Set mtime to 100 seconds in past
            past_time = time.time() - 100.0
            os.utime(xml_p, (past_time, past_time))
            # Require min_mtime of current time
            with self.assertRaises(ValueError) as ctx:
                collect_and_parse_results(Path(td), min_mtime=time.time())
            self.assertIn("Stale test result detected", str(ctx.exception))

    def test_adapter_emits_structured_test_summary_marker_and_exits_zero(self):
        with tempfile.TemporaryDirectory() as td:
            xml_p = Path(td) / "TEST-com.weback.Valid.xml"
            xml_p.write_text("""<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.weback.Valid" tests="2" failures="0" errors="0" skipped="0">
  <testcase name="testA" classname="com.weback.Valid"/>
  <testcase name="testB" classname="com.weback.Valid"/>
</testsuite>
""", encoding="utf-8")
            # Run adapter with no_exec=True and capture stdout
            old_stdout = sys.stdout
            try:
                captured = io.StringIO()
                sys.stdout = captured
                code = run_adapter(Path(td), no_exec=True)
                sys.stdout = old_stdout
                output = captured.getvalue()
                self.assertEqual(code, 0)
                self.assertIn(f"{SUMMARY_PREFIX} ", output)
                # Parse emitted summary
                for line in output.splitlines():
                    if line.startswith(SUMMARY_PREFIX):
                        summary_json = json.loads(line[len(SUMMARY_PREFIX):].strip())
                        self.assertEqual(summary_json["total"], 2)
                        self.assertEqual(summary_json["passed"], 2)
                        self.assertEqual(summary_json["failures"], 0)
            finally:
                sys.stdout = old_stdout

    def test_adapter_preserves_nonzero_exit_code_on_failure(self):
        with tempfile.TemporaryDirectory() as td:
            # Script that exits 42
            code = run_adapter(Path(td), cmd=[sys.executable, "-c", "import sys; sys.exit(42)"])
            self.assertEqual(code, 42)

if __name__ == "__main__":
    unittest.main()
