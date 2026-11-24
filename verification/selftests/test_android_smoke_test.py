"""
Selftests for Android Smoke Test Runner (android_smoke_test.py).
Standard library only. Zero third-party dependencies.
"""
import io
import os
from pathlib import Path
import sys
import tempfile
import unittest

from verification.checks.android_smoke_test import run_smoke_test

class TestAndroidSmokeTest(unittest.TestCase):

    def test_smoke_test_skipped_when_no_serial_provided(self):
        # Ensure env var is unset
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
        code = run_smoke_test(Path("/nonexistent/app-debug.apk"), device_serial="AUTHORIZED_SERIAL_999")
        # Should fail because device cannot be contacted or APK is missing
        self.assertNotEqual(code, 0)

if __name__ == "__main__":
    unittest.main()
