"""
Selftests for Android Smoke Test Runner (android_smoke_test.py).
Standard library only. Zero third-party dependencies.
"""
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch, MagicMock

from verification.checks.android_smoke_test import run_smoke_test, EXPECTED_COMPONENT

class TestAndroidSmokeTest(unittest.TestCase):

    def test_smoke_test_skipped_when_no_serial_provided(self):
        old_env = os.environ.get("WEBACK_TEST_DEVICE_SERIAL")
        if "WEBACK_TEST_DEVICE_SERIAL" in os.environ:
            del os.environ["WEBACK_TEST_DEVICE_SERIAL"]

        try:
            captured = io.StringIO()
            old_stdout = sys.stdout
            sys.stdout = captured
            code = run_smoke_test(Path("dummy.apk"), device_serial=None)
            sys.stdout = old_stdout
            out = captured.getvalue()

            self.assertEqual(code, 0)
            self.assertIn("SMOKE TEST SKIPPED", out)
            self.assertIn("NOT_RUN", out)
        finally:
            if old_env is not None:
                os.environ["WEBACK_TEST_DEVICE_SERIAL"] = old_env

    def test_smoke_test_fails_if_apk_missing_when_serial_provided(self):
        code = run_smoke_test(
            Path("/nonexistent/app-debug.apk"),
            device_serial="AUTHORIZED_SERIAL_999",
            adb_path=Path("/bin/true")
        )
        self.assertNotEqual(code, 0)

    def test_exact_adb_argv_and_successful_smoke_lifecycle(self):
        with tempfile.NamedTemporaryFile(suffix=".apk") as f:
            apk_path = Path(f.name)
            serial = "EMULATOR_5554"
            executed_commands = []

            def fake_subprocess_run(cmd, capture_output=True, text=True):
                executed_commands.append(list(cmd))
                res = MagicMock()
                res.returncode = 0
                if "get-state" in cmd:
                    res.stdout = "device\n"
                    res.stderr = ""
                elif "install" in cmd:
                    res.stdout = "Success\n"
                    res.stderr = ""
                elif "am" in cmd:
                    res.stdout = "Starting: Intent { cmp=com.weback.robot/.MainActivity }\nStatus: ok\nComplete\n"
                    res.stderr = ""
                elif "pidof" in cmd:
                    res.stdout = "4321\n"
                    res.stderr = ""
                elif "logcat" in cmd:
                    res.stdout = "I/WeBack: App running smoothly\n"
                    res.stderr = ""
                return res

            mock_sleep = MagicMock()

            with patch("subprocess.run", side_effect=fake_subprocess_run):
                code = run_smoke_test(
                    apk_path,
                    device_serial=serial,
                    sleep_fn=mock_sleep,
                    adb_path=Path("/mock/adb")
                )

            self.assertEqual(code, 0)
            mock_sleep.assert_called_once_with(5)

            # Verify exact ADB argv executed in order
            expected_argvs = [
                ["/mock/adb", "-s", serial, "get-state"],
                ["/mock/adb", "-s", serial, "install", "-r", str(apk_path)],
                ["/mock/adb", "-s", serial, "shell", "am", "start", "-W", "-n", "com.weback.robot/com.weback.robot.MainActivity"],
                ["/mock/adb", "-s", serial, "shell", "pidof", "com.weback.robot"],
                ["/mock/adb", "-s", serial, "logcat", "-d", "--pid=4321"]
            ]
            self.assertEqual(executed_commands, expected_argvs)
            self.assertEqual(EXPECTED_COMPONENT, "com.weback.robot/com.weback.robot.MainActivity")

    def test_activity_start_error_detected_despite_zero_exit_code(self):
        with tempfile.NamedTemporaryFile(suffix=".apk") as f:
            apk_path = Path(f.name)
            serial = "EMULATOR_5554"

            def fake_subprocess_run(cmd, capture_output=True, text=True):
                res = MagicMock()
                res.returncode = 0
                if "get-state" in cmd:
                    res.stdout = "device\n"
                elif "install" in cmd:
                    res.stdout = "Success\n"
                elif "am" in cmd:
                    res.stdout = "Error: Activity class {com.weback.robot/com.weback.robot.MainActivity} does not exist.\n"
                return res

            with patch("subprocess.run", side_effect=fake_subprocess_run):
                code = run_smoke_test(
                    apk_path,
                    device_serial=serial,
                    sleep_fn=MagicMock(),
                    adb_path=Path("/mock/adb")
                )

            self.assertEqual(code, 1)

    def test_process_death_within_5_seconds_detected(self):
        with tempfile.NamedTemporaryFile(suffix=".apk") as f:
            apk_path = Path(f.name)
            serial = "EMULATOR_5554"

            def fake_subprocess_run(cmd, capture_output=True, text=True):
                res = MagicMock()
                res.returncode = 0
                if "get-state" in cmd:
                    res.stdout = "device\n"
                elif "install" in cmd:
                    res.stdout = "Success\n"
                elif "am" in cmd:
                    res.stdout = "Status: ok\n"
                elif "pidof" in cmd:
                    res.stdout = ""  # Process died
                return res

            with patch("subprocess.run", side_effect=fake_subprocess_run):
                code = run_smoke_test(
                    apk_path,
                    device_serial=serial,
                    sleep_fn=MagicMock(),
                    adb_path=Path("/mock/adb")
                )

            self.assertEqual(code, 1)

    def test_fatal_crash_in_logcat_detected(self):
        with tempfile.NamedTemporaryFile(suffix=".apk") as f:
            apk_path = Path(f.name)
            serial = "EMULATOR_5554"

            def fake_subprocess_run(cmd, capture_output=True, text=True):
                res = MagicMock()
                res.returncode = 0
                if "get-state" in cmd:
                    res.stdout = "device\n"
                elif "install" in cmd:
                    res.stdout = "Success\n"
                elif "am" in cmd:
                    res.stdout = "Status: ok\n"
                elif "pidof" in cmd:
                    res.stdout = "9999\n"
                elif "logcat" in cmd:
                    res.stdout = "E/AndroidRuntime: FATAL EXCEPTION: main\nProcess: com.weback.robot\njava.lang.NullPointerException\n"
                return res

            with patch("subprocess.run", side_effect=fake_subprocess_run):
                code = run_smoke_test(
                    apk_path,
                    device_serial=serial,
                    sleep_fn=MagicMock(),
                    adb_path=Path("/mock/adb")
                )

            self.assertEqual(code, 1)

if __name__ == "__main__":
    unittest.main()
