import unittest
import time
import os
import signal
import tempfile
from pathlib import Path
from verification.checks.registry import CheckDefinition
from verification.checks.runner import execute_check

class TestRunnerExecution(unittest.TestCase):
    def test_successful_command_TEST_RUN_001(self):
        c = CheckDefinition(id="c1", argv=["python3", "-c", "print('hello'); exit(0)"], required=True, timeout_seconds=5)
        res = execute_check(c)
        self.assertEqual(res.exit_code, 0)
        self.assertEqual(res.status, "PASS")
        self.assertIn(b"hello", res.output)

    def test_intentional_required_failure_TEST_RUN_002(self):
        c = CheckDefinition(id="c2", argv=["python3", "-c", "exit(1)"], required=True, timeout_seconds=5)
        res = execute_check(c)
        self.assertEqual(res.exit_code, 1)
        self.assertEqual(res.status, "FAIL")

    def test_optional_check_failure_recorded_TEST_RUN_003(self):
        c = CheckDefinition(id="c3", argv=["python3", "-c", "exit(1)"], required=False, timeout_seconds=5)
        res = execute_check(c)
        self.assertEqual(res.exit_code, 1)
        self.assertEqual(res.status, "FAIL")

    def test_missing_executable_error_127_TEST_RUN_004(self):
        c = CheckDefinition(id="c4", argv=["non_existent_binary_xyz123"], required=True, timeout_seconds=5)
        res = execute_check(c)
        self.assertEqual(res.exit_code, 127)
        self.assertEqual(res.status, "ERROR")
        self.assertIn(b"[EXECUTABLE NOT FOUND", res.output)

    def test_permission_denied_error_126_TEST_RUN_011(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "unexecutable.sh"
            p.write_text("#!/bin/sh\nexit 0")
            p.chmod(0o000)
            c = CheckDefinition(id="c_perm", argv=[str(p)], required=True, timeout_seconds=5)
            res = execute_check(c)
            self.assertEqual(res.exit_code, 126)
            self.assertEqual(res.status, "ERROR")
            self.assertIn(b"[PERMISSION DENIED", res.output)

    def test_timeout_and_process_cleanup_TEST_RUN_005(self):
        with tempfile.TemporaryDirectory() as td:
            pid_file = Path(td) / "child.pid"
            script = f"import time, subprocess; p = subprocess.Popen(['sleep', '100']); open('{pid_file}', 'w').write(str(p.pid)); time.sleep(10)"
            c = CheckDefinition(id="c5", argv=["python3", "-c", script], required=True, timeout_seconds=1)
            res = execute_check(c)
            self.assertEqual(res.status, "TIMEOUT")
            self.assertEqual(res.exit_code, 124)
            # Verify grandchild is killed
            time.sleep(0.5)
            grandchild_pid = int(pid_file.read_text())
            with self.assertRaises(ProcessLookupError):
                os.kill(grandchild_pid, 0)

    def test_parent_exit_with_surviving_descendants_TEST_RUN_012(self):
        with tempfile.TemporaryDirectory() as td:
            pid_file = Path(td) / "descendant.pid"
            script = f"import time, subprocess; p = subprocess.Popen(['sleep', '50']); open('{pid_file}', 'w').write(str(p.pid)); exit(0)"
            c = CheckDefinition(id="c_desc", argv=["python3", "-c", script], required=True, timeout_seconds=5)
            res = execute_check(c)
            self.assertEqual(res.status, "PASS")
            self.assertEqual(res.exit_code, 0)
            time.sleep(0.5)
            pid = int(pid_file.read_text())
            # Descendant process should either be reaped or cleanly accessible for kill
            try:
                os.kill(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass

    def test_sigterm_resistant_child_escalation_TEST_RUN_013(self):
        with tempfile.TemporaryDirectory() as td:
            script = "import signal, time; signal.signal(signal.SIGTERM, signal.SIG_IGN); open('ready.tmp', 'w').write('ok'); time.sleep(10)"
            c = CheckDefinition(id="c_sig", argv=["python3", "-c", script], required=True, timeout_seconds=1, cwd=Path(td))
            t0 = time.time()
            res = execute_check(c)
            elapsed = time.time() - t0
            self.assertEqual(res.status, "TIMEOUT")
            self.assertEqual(res.exit_code, 124)
            self.assertLess(elapsed, 4.0)

    def test_large_output_buffer_TEST_RUN_014(self):
        script = "import sys; sys.stdout.write('A' * (5 * 1024 * 1024)); sys.stdout.flush()"
        c = CheckDefinition(id="c_large", argv=["python3", "-c", script], required=True, timeout_seconds=10)
        res = execute_check(c)
        self.assertEqual(res.status, "PASS")
        self.assertEqual(res.exit_code, 0)
        self.assertEqual(len(res.output), 5 * 1024 * 1024)

    def test_cleanup_failed_sets_status_fail_TEST_RUN_015(self):
        from unittest.mock import patch
        c = CheckDefinition(id="c_clean_fail", argv=["python3", "-c", "print('ok'); exit(0)"], required=True, timeout_seconds=5)
        with patch("verification.checks.runner._kill_process_group", return_value=False):
            res = execute_check(c)
            self.assertTrue(res.cleanup_failed)
            self.assertEqual(res.status, "FAIL")


if __name__ == "__main__":
    unittest.main()
