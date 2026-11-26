"""
Selftests for Gradle Structured Test Execution Adapter (gradle_test_adapter.py).
Standard library only. Zero third-party dependencies.
"""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
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

    def test_cleanup_failure_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            locked_dir = Path(td) / "results"
            locked_dir.mkdir()
            xml_file = locked_dir / "TEST-locked.xml"
            xml_file.write_text("<testsuite name='s' tests='1'/>", encoding="utf-8")
            try:
                os.chmod(locked_dir, 0o555)
                from verification.checks.gradle_test_adapter import execute_gradle_task
                code, out, _ = execute_gradle_task(["true"], locked_dir, clean_before=True)
                self.assertEqual(code, 1)
                self.assertIn(b"Cleanup failure", out)
            finally:
                os.chmod(locked_dir, 0o755)

    def test_e2e_cli_invocation_without_pythonpath(self):
        clean_env = os.environ.copy()
        clean_env.pop("PYTHONPATH", None)

        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)
            results_dir = tmp_path / "results"
            results_dir.mkdir(parents=True, exist_ok=True)

            # Executable fixture simulating test task generating JUnit XML
            mock_task_script = tmp_path / "mock_test_task.py"
            mock_xml_content = (
                '<?xml version="1.0" encoding="UTF-8"?>\n'
                '<testsuite name="com.weback.robot.FixtureTest" tests="3" skipped="0" failures="0" errors="0" timestamp="2026-01-01T00:00:00" hostname="localhost" time="0.05">\n'
                '  <properties/>\n'
                '  <testcase name="testOne" classname="com.weback.robot.FixtureTest" time="0.01"/>\n'
                '  <testcase name="testTwo" classname="com.weback.robot.FixtureTest" time="0.02"/>\n'
                '  <testcase name="testThree" classname="com.weback.robot.FixtureTest" time="0.02"/>\n'
                '</testsuite>\n'
            )
            mock_task_script.write_text(
                "import sys, os, time\n"
                "from pathlib import Path\n"
                f"out_dir = Path({repr(str(results_dir))})\n"
                "out_dir.mkdir(parents=True, exist_ok=True)\n"
                f"(out_dir / 'TEST-fixture.xml').write_text({repr(mock_xml_content)}, encoding='utf-8')\n"
                "sys.exit(0)\n",
                encoding="utf-8"
            )

            repo_root = Path(__file__).resolve().parent.parent.parent
            res = subprocess.run(
                [
                    sys.executable,
                    "-m",
                    "verification.checks.gradle_test_adapter",
                    "--results-dir",
                    str(results_dir),
                    "--gradle-bin",
                    sys.executable,
                    "--task",
                    str(mock_task_script)
                ],
                cwd=str(repo_root),
                env=clean_env,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True
            )
            self.assertEqual(res.returncode, 0, f"Adapter CLI failed with stderr: {res.stderr}\nstdout: {res.stdout}")
            self.assertIn(SUMMARY_PREFIX, res.stdout)

            summary_found = False
            for line in res.stdout.splitlines():
                if line.startswith(SUMMARY_PREFIX):
                    summary_data = json.loads(line[len(SUMMARY_PREFIX):].strip())
                    self.assertEqual(summary_data["total"], 3)
                    self.assertEqual(summary_data["passed"], 3)
                    self.assertEqual(summary_data["failures"], 0)
                    self.assertEqual(summary_data["errors"], 0)
                    self.assertEqual(summary_data["skipped"], 0)
                    summary_found = True
                    break
            self.assertTrue(summary_found, "SUMMARY_PREFIX line not found in CLI output")

    @staticmethod
    def _run_adapter_captured(*args, **kwargs) -> tuple[int, str, str]:
        raw_out = io.BytesIO()
        out_buf = io.TextIOWrapper(raw_out, encoding="utf-8")
        err_buf = io.StringIO()
        with contextlib.redirect_stdout(out_buf), contextlib.redirect_stderr(err_buf):
            code = run_adapter(*args, **kwargs)
            out_buf.flush()
        return code, raw_out.getvalue().decode("utf-8", errors="replace"), err_buf.getvalue()

    def test_missing_xml_after_successful_gradle_exit_fails_closed(self):
        with tempfile.TemporaryDirectory() as td:
            results_dir = Path(td)
            code, _, err = self._run_adapter_captured(results_dir, cmd=[sys.executable, "-c", "import sys; sys.exit(0)"])
            self.assertEqual(code, 1)
            self.assertIn("No JUnit XML test result files discovered", err)

    def test_skipped_test_blocked_by_adapter_even_if_gradle_exits_zero(self):
        with tempfile.TemporaryDirectory() as td:
            results_dir = Path(td)
            xml_content = (
                '<?xml version="1.0" encoding="UTF-8"?>\n'
                '<testsuite name="com.weback.Skipped" tests="2" skipped="1" failures="0" errors="0">\n'
                '  <testcase name="testOk" classname="com.weback.Skipped"/>\n'
                '  <testcase name="testSkip" classname="com.weback.Skipped"><skipped message="ignored"/></testcase>\n'
                '</testsuite>\n'
            )
            cmd = [
                sys.executable, "-c",
                f"from pathlib import Path; (Path({repr(str(results_dir))}) / 'TEST-skip.xml').write_text({repr(xml_content)}, encoding='utf-8')"
            ]
            code, _, err = self._run_adapter_captured(results_dir, cmd=cmd, allow_skipped=False)
            self.assertEqual(code, 1)
            self.assertIn("Adapter Gate Blocked", err)

    def test_failing_test_blocked_by_adapter_even_if_gradle_exits_zero(self):
        with tempfile.TemporaryDirectory() as td:
            results_dir = Path(td)
            xml_content = (
                '<?xml version="1.0" encoding="UTF-8"?>\n'
                '<testsuite name="com.weback.Fail" tests="1" skipped="0" failures="1" errors="0">\n'
                '  <testcase name="testBad" classname="com.weback.Fail"><failure message="boom">trace</failure></testcase>\n'
                '</testsuite>\n'
            )
            cmd = [
                sys.executable, "-c",
                f"from pathlib import Path; (Path({repr(str(results_dir))}) / 'TEST-fail.xml').write_text({repr(xml_content)}, encoding='utf-8')"
            ]
            code, _, err = self._run_adapter_captured(results_dir, cmd=cmd)
            self.assertEqual(code, 1)
            self.assertIn("Adapter Gate Blocked", err)

    def test_consecutive_runs_prove_non_cached_fresh_execution_and_clean_all_xmls(self):
        with tempfile.TemporaryDirectory() as td:
            results_dir = Path(td)
            # Plant a non-TEST- xml file to verify clean_before removes all *.xml
            (results_dir / "stale-non-prefix.xml").write_text(
                '<testsuite name="Stale" tests="1" failures="0" errors="0" skipped="0">'
                '<testcase name="testOld" classname="com.weback.Old"/></testsuite>',
                encoding="utf-8",
            )
            pass_xml = (
                '<?xml version="1.0" encoding="UTF-8"?>\n'
                '<testsuite name="com.weback.Pass" tests="1" skipped="0" failures="0" errors="0">\n'
                '  <testcase name="testOne" classname="com.weback.Pass"/>\n'
                '</testsuite>\n'
            )
            run1_cmd = [
                sys.executable, "-c",
                f"from pathlib import Path; (Path({repr(str(results_dir))}) / 'TEST-pass.xml').write_text({repr(pass_xml)}, encoding='utf-8')"
            ]
            code1, _, _ = self._run_adapter_captured(results_dir, cmd=run1_cmd)
            self.assertEqual(code1, 0)
            self.assertFalse((results_dir / "stale-non-prefix.xml").exists())

            # Run 2 simulates a cached/UP-TO-DATE Gradle exit 0 without emitting fresh XML:
            # Adapter must delete Run 1's XML before invocation and fail closed.
            code2, _, err2 = self._run_adapter_captured(results_dir, cmd=[sys.executable, "-c", "import sys; sys.exit(0)"])
            self.assertEqual(code2, 1)
            self.assertIn("No JUnit XML test result files discovered", err2)
            self.assertFalse((results_dir / "TEST-pass.xml").exists())

    def test_cli_tests_flag_forwarded_to_gradle_command(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)
            results_dir = tmp_path / "results"
            results_dir.mkdir(parents=True, exist_ok=True)
            argv_log = tmp_path / "argv.json"
            mock_gradle = tmp_path / "mock_gradle.py"
            xml_content = (
                '<?xml version="1.0" encoding="UTF-8"?>\n'
                '<testsuite name="com.weback.Filtered" tests="1" skipped="0" failures="0" errors="0">\n'
                '  <testcase name="testTarget" classname="com.weback.Filtered"/>\n'
                '</testsuite>\n'
            )
            mock_gradle.write_text(
                "import json, sys\n"
                "from pathlib import Path\n"
                f"Path({repr(str(argv_log))}).write_text(json.dumps(sys.argv[1:]), encoding='utf-8')\n"
                f"(Path({repr(str(results_dir))}) / 'TEST-filtered.xml').write_text({repr(xml_content)}, encoding='utf-8')\n",
                encoding="utf-8",
            )
            repo_root = Path(__file__).resolve().parent.parent.parent
            res = subprocess.run(
                [
                    sys.executable,
                    "-m",
                    "verification.checks.gradle_test_adapter",
                    "--results-dir",
                    str(results_dir),
                    "--gradle-bin",
                    sys.executable,
                    "--task",
                    str(mock_gradle),
                    "--tests",
                    "com.weback.Filtered",
                ],
                cwd=str(repo_root),
                capture_output=True,
                text=True,
            )
            self.assertEqual(res.returncode, 0, res.stderr)
            recorded = json.loads(argv_log.read_text(encoding="utf-8"))
            self.assertIn("--rerun", recorded)
            self.assertNotIn("--rerun-tasks", recorded)
            self.assertIn("--tests", recorded)
            idx = recorded.index("--tests")
            self.assertEqual(recorded[idx + 1], "com.weback.Filtered")

if __name__ == "__main__":
    unittest.main()
