"""
Selftests for Gradle Wrapper Integrity Checker (gradle_wrapper_check.py).
Standard library only. Zero third-party dependencies.
"""
import hashlib
from pathlib import Path
import tempfile
import unittest

from verification.checks.gradle_wrapper_check import (
    EXPECTED_DISTRIBUTION_SHA256,
    EXPECTED_GRADLE_VERSION,
    EXPECTED_WRAPPER_JAR_SHA256,
    parse_properties,
    validate_wrapper
)

class TestGradleWrapperCheck(unittest.TestCase):

    def test_parse_properties_escaped_colons(self):
        content = r"distributionUrl=https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip"
        props = parse_properties(content)
        self.assertEqual(props["distributionUrl"], "https://services.gradle.org/distributions/gradle-8.10.2-bin.zip")

    def test_valid_wrapper_configuration(self):
        repo_root = Path(__file__).resolve().parent.parent.parent
        valid, errors = validate_wrapper(repo_root)
        self.assertTrue(valid, f"Expected valid wrapper in repo root, got errors: {errors}")
        self.assertEqual(len(errors), 0)

    def test_detect_missing_properties_file(self):
        with tempfile.TemporaryDirectory() as td:
            valid, errors = validate_wrapper(Path(td))
            self.assertFalse(valid)
            self.assertTrue(any("Missing gradle-wrapper.properties" in e for e in errors))

    def test_detect_insecure_http_url(self):
        with tempfile.TemporaryDirectory() as td:
            wdir = Path(td) / "gradle" / "wrapper"
            wdir.mkdir(parents=True)
            (wdir / "gradle-wrapper.properties").write_text(
                f"distributionUrl=http://services.gradle.org/distributions/gradle-{EXPECTED_GRADLE_VERSION}-bin.zip\n"
                f"distributionSha256Sum={EXPECTED_DISTRIBUTION_SHA256}\n",
                encoding="utf-8"
            )
            (wdir / "gradle-wrapper.jar").write_bytes(b"dummy")
            valid, errors = validate_wrapper(Path(td))
            self.assertFalse(valid)
            self.assertTrue(any("must use HTTPS" in e for e in errors))

    def test_detect_distribution_sha_mismatch(self):
        with tempfile.TemporaryDirectory() as td:
            wdir = Path(td) / "gradle" / "wrapper"
            wdir.mkdir(parents=True)
            (wdir / "gradle-wrapper.properties").write_text(
                f"distributionUrl=https://services.gradle.org/distributions/gradle-{EXPECTED_GRADLE_VERSION}-bin.zip\n"
                f"distributionSha256Sum=badbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbad\n",
                encoding="utf-8"
            )
            (wdir / "gradle-wrapper.jar").write_bytes(b"dummy")
            valid, errors = validate_wrapper(Path(td))
            self.assertFalse(valid)
            self.assertTrue(any("distributionSha256Sum mismatch" in e for e in errors))

    def test_detect_wrapper_jar_sha_mismatch(self):
        with tempfile.TemporaryDirectory() as td:
            wdir = Path(td) / "gradle" / "wrapper"
            wdir.mkdir(parents=True)
            (wdir / "gradle-wrapper.properties").write_text(
                f"distributionUrl=https://services.gradle.org/distributions/gradle-{EXPECTED_GRADLE_VERSION}-bin.zip\n"
                f"distributionSha256Sum={EXPECTED_DISTRIBUTION_SHA256}\n",
                encoding="utf-8"
            )
            # Create a jar with non-matching content
            (wdir / "gradle-wrapper.jar").write_bytes(b"tampered jar bytes")
            valid, errors = validate_wrapper(Path(td))
            self.assertFalse(valid)
            self.assertTrue(any("gradle-wrapper.jar SHA-256 mismatch" in e for e in errors))

if __name__ == "__main__":
    unittest.main()
