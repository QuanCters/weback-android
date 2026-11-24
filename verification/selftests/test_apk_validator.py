"""
Selftests for Debug APK Artifact Validator (apk_validator.py).
Standard library only. Zero third-party dependencies.
"""
from pathlib import Path
import tempfile
import unittest
import zipfile

from verification.checks.apk_validator import (
    PROHIBITED_BINARY_PATTERNS,
    compute_sha256,
    validate_apk,
    validate_apk_archive,
    validate_manifest_xml_structural,
    validate_dex_classes_in_apk,
    parse_dex_classes
)

class TestApkValidator(unittest.TestCase):

    def test_detect_missing_apk(self):
        valid, errors, _ = validate_apk(Path("/nonexistent/app-debug.apk"))
        self.assertFalse(valid)
        self.assertTrue(any("APK artifact not found" in e for e in errors))

    def test_detect_empty_apk(self):
        with tempfile.TemporaryDirectory() as td:
            apk_p = Path(td) / "empty.apk"
            apk_p.touch()
            valid, errors, _ = validate_apk(apk_p)
            self.assertFalse(valid)
            self.assertTrue(any("empty" in e for e in errors))

    def test_detect_corrupted_archive(self):
        with tempfile.TemporaryDirectory() as td:
            apk_p = Path(td) / "corrupt.apk"
            apk_p.write_bytes(b"NOT A VALID ZIP ARCHIVE")
            ok, errors = validate_apk_archive(apk_p)
            self.assertFalse(ok)
            self.assertTrue(any("Failed to inspect APK archive" in e for e in errors))

    def test_detect_missing_essential_entries(self):
        with tempfile.TemporaryDirectory() as td:
            apk_p = Path(td) / "missing_entries.apk"
            with zipfile.ZipFile(apk_p, "w") as zf:
                zf.writestr("AndroidManifest.xml", b"<manifest/>")
                # Missing classes.dex and resources.arsc
            ok, errors = validate_apk_archive(apk_p)
            self.assertFalse(ok)
            self.assertTrue(any("classes.dex" in e for e in errors))
            self.assertTrue(any("resources.arsc" in e for e in errors))

    def test_detect_prohibited_vendor_binary(self):
        with tempfile.TemporaryDirectory() as td:
            apk_p = Path(td) / "prohibited.apk"
            with zipfile.ZipFile(apk_p, "w") as zf:
                zf.writestr("AndroidManifest.xml", b"<manifest/>")
                zf.writestr("classes.dex", b"DEX DATA")
                zf.writestr("resources.arsc", b"ARSC DATA")
                zf.writestr("lib/arm64-v8a/libIOTCAPIs.so", b"ELF BINARY DATA")
            ok, errors = validate_apk_archive(apk_p)
            self.assertFalse(ok)
            self.assertTrue(any("Prohibited vendor binary detected" in e and "libIOTCAPIs.so" in e for e in errors))

    def test_valid_synthetic_apk_archive(self):
        with tempfile.TemporaryDirectory() as td:
            apk_p = Path(td) / "valid.apk"
            with zipfile.ZipFile(apk_p, "w") as zf:
                zf.writestr("AndroidManifest.xml", b"<manifest/>")
                zf.writestr("classes.dex", b"DEX DATA")
                zf.writestr("resources.arsc", b"ARSC DATA")
            ok, errors = validate_apk_archive(apk_p)
            self.assertTrue(ok, f"Expected valid archive, got errors: {errors}")
            self.assertEqual(len(errors), 0)

            # Test sha computation
            sha = compute_sha256(apk_p)
            self.assertEqual(len(sha), 64)

    def test_manifest_valid_structural_xml(self):
        valid_xml = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="com.weback.robot" android:versionName="0.1.0-alpha">\n'
            '  <uses-sdk android:minSdkVersion="31" android:targetSdkVersion="35" />\n'
            '  <application android:usesCleartextTraffic="false" android:allowBackup="false" '
            'android:networkSecurityConfig="@ref/0x7f0b0000">\n'
            '    <activity android:name="com.weback.robot.MainActivity" android:exported="true">\n'
            '      <intent-filter>\n'
            '        <action android:name="android.intent.action.MAIN" />\n'
            '        <category android:name="android.intent.category.LAUNCHER" />\n'
            '      </intent-filter>\n'
            '    </activity>\n'
            '  </application>\n'
            '</manifest>'
        )
        ok, errors = validate_manifest_xml_structural(valid_xml)
        self.assertTrue(ok, f"Expected valid manifest, got errors: {errors}")
        self.assertEqual(len(errors), 0)

    def test_manifest_negative_activity_missing_launcher_category(self):
        # Activity with MAIN action but missing LAUNCHER category
        invalid_xml = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="com.weback.robot" android:versionName="0.1.0-alpha">\n'
            '  <uses-sdk android:minSdkVersion="31" android:targetSdkVersion="35" />\n'
            '  <application android:usesCleartextTraffic="false" android:allowBackup="false" '
            'android:networkSecurityConfig="@ref/0x7f0b0000">\n'
            '    <activity android:name="com.weback.robot.MainActivity" android:exported="true">\n'
            '      <intent-filter>\n'
            '        <action android:name="android.intent.action.MAIN" />\n'
            '      </intent-filter>\n'
            '    </activity>\n'
            '  </application>\n'
            '</manifest>'
        )
        ok, errors = validate_manifest_xml_structural(invalid_xml)
        self.assertFalse(ok)
        self.assertTrue(any("missing intent-filter category" in e for e in errors))

    def test_manifest_negative_activity_not_exported(self):
        invalid_xml = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="com.weback.robot" android:versionName="0.1.0-alpha">\n'
            '  <uses-sdk android:minSdkVersion="31" android:targetSdkVersion="35" />\n'
            '  <application android:usesCleartextTraffic="false" android:allowBackup="false" '
            'android:networkSecurityConfig="@ref/0x7f0b0000">\n'
            '    <activity android:name="com.weback.robot.MainActivity" android:exported="false">\n'
            '      <intent-filter>\n'
            '        <action android:name="android.intent.action.MAIN" />\n'
            '        <category android:name="android.intent.category.LAUNCHER" />\n'
            '      </intent-filter>\n'
            '    </activity>\n'
            '  </application>\n'
            '</manifest>'
        )
        ok, errors = validate_manifest_xml_structural(invalid_xml)
        self.assertFalse(ok)
        self.assertTrue(any("must have exported='true'" in e for e in errors))

    def test_manifest_negative_cleartext_traffic_enabled(self):
        invalid_xml = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="com.weback.robot" android:versionName="0.1.0-alpha">\n'
            '  <uses-sdk android:minSdkVersion="31" android:targetSdkVersion="35" />\n'
            '  <application android:usesCleartextTraffic="true" android:allowBackup="false" '
            'android:networkSecurityConfig="@ref/0x7f0b0000">\n'
            '    <activity android:name="com.weback.robot.MainActivity" android:exported="true">\n'
            '      <intent-filter>\n'
            '        <action android:name="android.intent.action.MAIN" />\n'
            '        <category android:name="android.intent.category.LAUNCHER" />\n'
            '      </intent-filter>\n'
            '    </activity>\n'
            '  </application>\n'
            '</manifest>'
        )
        ok, errors = validate_manifest_xml_structural(invalid_xml)
        self.assertFalse(ok)
        self.assertTrue(any("usesCleartextTraffic must be strictly 'false'" in e for e in errors))

    def test_manifest_negative_prohibited_permission(self):
        invalid_xml = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="com.weback.robot" android:versionName="0.1.0-alpha">\n'
            '  <uses-permission android:name="android.permission.INTERNET" />\n'
            '  <uses-sdk android:minSdkVersion="31" android:targetSdkVersion="35" />\n'
            '  <application android:usesCleartextTraffic="false" android:allowBackup="false" '
            'android:networkSecurityConfig="@ref/0x7f0b0000">\n'
            '    <activity android:name="com.weback.robot.MainActivity" android:exported="true">\n'
            '      <intent-filter>\n'
            '        <action android:name="android.intent.action.MAIN" />\n'
            '        <category android:name="android.intent.category.LAUNCHER" />\n'
            '      </intent-filter>\n'
            '    </activity>\n'
            '  </application>\n'
            '</manifest>'
        )
        ok, errors = validate_manifest_xml_structural(invalid_xml)
        self.assertFalse(ok)
        self.assertTrue(any("Prohibited application permission detected" in e and "android.permission.INTERNET" in e for e in errors))

    def test_dex_classes_negative_prohibited_vendor_class(self):
        import struct
        header = bytearray(b"dex\n035\0" + b"\x00" * 104)
        header[56:64] = struct.pack("<II", 1, 112)
        header[64:72] = struct.pack("<II", 1, 116)
        header[96:104] = struct.pack("<II", 1, 120)
        string_ids = struct.pack("<I", 152)
        type_ids = struct.pack("<I", 0)
        class_defs = struct.pack("<I", 0) + b"\x00" * 28
        str_val = b"Lcom/yugong/Backome/DeviceActivity;"
        str_data = bytes([len(str_val)]) + str_val + b"\x00"
        dex_bytes = bytes(header) + string_ids + type_ids + class_defs + str_data

        with tempfile.TemporaryDirectory() as td:
            apk_p = Path(td) / "vendor.apk"
            with zipfile.ZipFile(apk_p, "w") as zf:
                zf.writestr("classes.dex", dex_bytes)
            ok, errors = validate_dex_classes_in_apk(apk_p)
            self.assertFalse(ok)
            self.assertTrue(any("Prohibited legacy vendor DEX class detected" in e and "Lcom/yugong/" in e for e in errors))

    def test_dex_classes_valid_clean_class(self):
        import struct
        header = bytearray(b"dex\n035\0" + b"\x00" * 104)
        header[56:64] = struct.pack("<II", 1, 112)
        header[64:72] = struct.pack("<II", 1, 116)
        header[96:104] = struct.pack("<II", 1, 120)
        string_ids = struct.pack("<I", 152)
        type_ids = struct.pack("<I", 0)
        class_defs = struct.pack("<I", 0) + b"\x00" * 28
        str_val = b"Lcom/weback/robot/MainActivity;"
        str_data = bytes([len(str_val)]) + str_val + b"\x00"
        dex_bytes = bytes(header) + string_ids + type_ids + class_defs + str_data

        with tempfile.TemporaryDirectory() as td:
            apk_p = Path(td) / "clean.apk"
            with zipfile.ZipFile(apk_p, "w") as zf:
                zf.writestr("classes.dex", dex_bytes)
            ok, errors = validate_dex_classes_in_apk(apk_p)
            self.assertTrue(ok, f"Expected clean DEX validation, got errors: {errors}")
            self.assertEqual(len(errors), 0)

if __name__ == "__main__":
    unittest.main()
