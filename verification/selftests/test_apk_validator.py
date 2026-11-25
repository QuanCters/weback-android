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

def make_synthetic_dex(class_names: list[str]) -> bytes:
    import struct
    num = len(class_names)
    header = bytearray(b"dex\n035\0" + b"\x00" * 104)
    string_ids_off = 112
    type_ids_off = string_ids_off + num * 4
    class_defs_off = type_ids_off + num * 4
    str_data_off = class_defs_off + num * 32

    header[56:64] = struct.pack("<II", num, string_ids_off)
    header[64:72] = struct.pack("<II", num, type_ids_off)
    header[96:104] = struct.pack("<II", num, class_defs_off)

    str_data_chunks = []
    str_offsets = []
    curr_off = str_data_off
    for cname in class_names:
        str_offsets.append(curr_off)
        raw = cname.encode("utf-8")
        chunk = bytes([len(raw)]) + raw + b"\x00"
        str_data_chunks.append(chunk)
        curr_off += len(chunk)

    string_ids = b"".join(struct.pack("<I", o) for o in str_offsets)
    type_ids = b"".join(struct.pack("<I", i) for i in range(num))
    class_defs = b"".join(struct.pack("<I", i) + b"\x00" * 28 for i in range(num))
    str_data = b"".join(str_data_chunks)

    total_len = len(header) + len(string_ids) + len(type_ids) + len(class_defs) + len(str_data)
    header[32:36] = struct.pack("<I", total_len)
    return bytes(header) + string_ids + type_ids + class_defs + str_data

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
        self.assertTrue(any("is missing an intent-filter declaring both action" in e for e in errors))

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

    def test_manifest_negative_main_and_launcher_in_separate_filters(self):
        # Activity with MAIN and LAUNCHER in separate intent-filters must FAIL
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
            '      <intent-filter>\n'
            '        <category android:name="android.intent.category.LAUNCHER" />\n'
            '      </intent-filter>\n'
            '    </activity>\n'
            '  </application>\n'
            '</manifest>'
        )
        ok, errors = validate_manifest_xml_structural(invalid_xml)
        self.assertFalse(ok)
        self.assertTrue(any("within the same intent-filter" in e for e in errors))

    def test_manifest_negative_unauthorized_permission(self):
        invalid_xml = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="com.weback.robot" android:versionName="0.1.0-alpha">\n'
            '  <uses-permission android:name="android.permission.CAMERA" />\n'
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
        self.assertTrue(any("FCT-001 authorizes zero external application permissions" in e for e in errors))

    def test_dex_classes_negative_prohibited_vendor_class(self):
        dex_bytes = make_synthetic_dex(["Lcom/yugong/Backome/DeviceActivity;"])
        with tempfile.TemporaryDirectory() as td:
            apk_p = Path(td) / "vendor.apk"
            with zipfile.ZipFile(apk_p, "w") as zf:
                zf.writestr("classes.dex", dex_bytes)
            ok, errors = validate_dex_classes_in_apk(apk_p)
            self.assertFalse(ok)
            self.assertTrue(any("Prohibited legacy vendor DEX class detected" in e and "Lcom/yugong/" in e for e in errors))

    def test_dex_classes_valid_clean_class(self):
        dex_bytes = make_synthetic_dex(["Lcom/weback/robot/MainActivity;"])
        with tempfile.TemporaryDirectory() as td:
            apk_p = Path(td) / "clean.apk"
            with zipfile.ZipFile(apk_p, "w") as zf:
                zf.writestr("classes.dex", dex_bytes)
            ok, errors = validate_dex_classes_in_apk(apk_p)
            self.assertTrue(ok, f"Expected clean DEX validation, got errors: {errors}")
            self.assertEqual(len(errors), 0)

    def test_dex_negative_malformed_header(self):
        with self.assertRaises(ValueError) as ctx:
            parse_dex_classes(b"TOO_SHORT")
        self.assertIn("Malformed DEX header", str(ctx.exception))

        with self.assertRaises(ValueError) as ctx:
            parse_dex_classes(b"NOT_DEX\0" + b"\x00" * 104)
        self.assertIn("Invalid DEX magic", str(ctx.exception))

    def test_dex_negative_file_size_mismatch(self):
        dex_bytes = bytearray(make_synthetic_dex(["Lcom/weback/robot/MainActivity;"]))
        import struct
        dex_bytes[32:36] = struct.pack("<I", len(dex_bytes) + 100)
        with self.assertRaises(ValueError) as ctx:
            parse_dex_classes(bytes(dex_bytes))
        self.assertIn("DEX file size mismatch", str(ctx.exception))

    def test_dex_negative_invalid_table_offsets(self):
        dex_bytes = bytearray(make_synthetic_dex(["Lcom/weback/robot/MainActivity;"]))
        import struct
        dex_bytes[56:64] = struct.pack("<II", 10, len(dex_bytes) + 500)
        with self.assertRaises(ValueError) as ctx:
            parse_dex_classes(bytes(dex_bytes))
        self.assertIn("Invalid string_ids table bounds", str(ctx.exception))

    def test_dex_negative_empty_class_inventory(self):
        dex_bytes = bytearray(make_synthetic_dex(["Lcom/weback/robot/MainActivity;"]))
        import struct
        dex_bytes[96:104] = struct.pack("<II", 0, 112)
        with self.assertRaises(ValueError) as ctx:
            parse_dex_classes(bytes(dex_bytes))
        self.assertIn("Empty class inventory", str(ctx.exception))

    def test_dex_negative_missing_main_activity(self):
        dex_bytes = make_synthetic_dex(["Lcom/weback/robot/ui/DashboardViewModel;"])
        with tempfile.TemporaryDirectory() as td:
            apk_p = Path(td) / "missing_main.apk"
            with zipfile.ZipFile(apk_p, "w") as zf:
                zf.writestr("classes.dex", dex_bytes)
            ok, errors = validate_dex_classes_in_apk(apk_p)
            self.assertFalse(ok)
            self.assertTrue(any("Required MainActivity class descriptor" in e for e in errors))

    def test_dex_negative_multidex_one_corrupt(self):
        clean_dex = make_synthetic_dex(["Lcom/weback/robot/MainActivity;"])
        with tempfile.TemporaryDirectory() as td:
            apk_p = Path(td) / "multidex_bad.apk"
            with zipfile.ZipFile(apk_p, "w") as zf:
                zf.writestr("classes.dex", clean_dex)
                zf.writestr("classes2.dex", b"CORRUPTED_DEX_BYTES")
            ok, errors = validate_dex_classes_in_apk(apk_p)
            self.assertFalse(ok)
            self.assertTrue(any("DEX inspection failure in 'classes2.dex'" in e for e in errors))

    def test_dex_valid_multidex(self):
        dex1 = make_synthetic_dex(["Lcom/weback/robot/domain/RobotState;"])
        dex2 = make_synthetic_dex(["Lcom/weback/robot/MainActivity;"])
        with tempfile.TemporaryDirectory() as td:
            apk_p = Path(td) / "multidex_ok.apk"
            with zipfile.ZipFile(apk_p, "w") as zf:
                zf.writestr("classes.dex", dex1)
                zf.writestr("classes2.dex", dex2)
            ok, errors = validate_dex_classes_in_apk(apk_p)
            self.assertTrue(ok, f"Expected multidex valid, got errors: {errors}")
            self.assertEqual(len(errors), 0)

if __name__ == "__main__":
    unittest.main()
