"""
Selftests for Debug APK Artifact Validator (apk_validator.py).
Standard library only. Zero third-party dependencies.
"""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

from unittest.mock import patch

from verification.checks.apk_validator import (
    PROHIBITED_BINARY_PATTERNS,
    compute_sha256,
    validate_apk,
    validate_apk_archive,
    validate_apk_path_for_policy,
    validate_apk_with_tools,
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

    def _build_manifest_xml(
        self,
        package_id: str = "com.weback.robot",
        permissions: list[str] | None = None,
        custom_permission: str | None = None,
        cleartext: str = "false",
        allow_backup: str = "false",
        provider_authority: str | None = None
    ) -> str:
        perm_decl = (
            f'  <permission android:name="{custom_permission}" android:protectionLevel="0x2" />\n'
            if custom_permission else ""
        )
        uses_perms = "".join(
            f'  <uses-permission android:name="{p}" />\n' for p in (permissions or [])
        )
        provider_tag = (
            f'    <provider android:name="androidx.startup.InitializationProvider" '
            f'android:exported="false" android:authorities="{provider_authority}" />\n'
            if provider_authority else ""
        )
        return (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            f'package="{package_id}" android:versionName="0.1.0-alpha">\n'
            '  <uses-sdk android:minSdkVersion="31" android:targetSdkVersion="35" />\n'
            f'{perm_decl}{uses_perms}'
            f'  <application android:usesCleartextTraffic="{cleartext}" android:allowBackup="{allow_backup}" '
            'android:networkSecurityConfig="@ref/0x7f0b0000">\n'
            '    <activity android:name="com.weback.robot.MainActivity" android:exported="true">\n'
            '      <intent-filter>\n'
            '        <action android:name="android.intent.action.MAIN" />\n'
            '        <category android:name="android.intent.category.LAUNCHER" />\n'
            '      </intent-filter>\n'
            '    </activity>\n'
            f'{provider_tag}'
            '  </application>\n'
            '</manifest>'
        )

    def test_pilot_mode_accepts_valid_pilot_manifest_with_internet(self):
        pilot_xml = self._build_manifest_xml(
            package_id="com.weback.robot.pilot",
            custom_permission="com.weback.robot.pilot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            permissions=[
                "android.permission.INTERNET",
                "com.weback.robot.pilot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            ],
            provider_authority="com.weback.robot.pilot.androidx-startup",
        )
        ok, errors = validate_manifest_xml_structural(pilot_xml, connectivity_policy="pilot")
        self.assertTrue(ok, f"Expected valid pilot manifest, got errors: {errors}")

    def test_pilot_mode_negative_missing_internet_permission(self):
        pilot_no_net = self._build_manifest_xml(
            package_id="com.weback.robot.pilot",
            custom_permission="com.weback.robot.pilot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            permissions=["com.weback.robot.pilot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"],
        )
        ok, errors = validate_manifest_xml_structural(pilot_no_net, connectivity_policy="pilot")
        self.assertFalse(ok)
        self.assertTrue(any("Missing required permission 'android.permission.INTERNET'" in e for e in errors))

    def test_pilot_mode_negative_rejects_offline_package_substitution(self):
        offline_xml = self._build_manifest_xml(
            package_id="com.weback.robot",
            custom_permission="com.weback.robot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            permissions=["com.weback.robot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"],
        )
        ok, errors = validate_manifest_xml_structural(offline_xml, connectivity_policy="pilot")
        self.assertFalse(ok)
        self.assertTrue(any("expected 'com.weback.robot.pilot'" in e for e in errors))

    def test_offline_mode_negative_rejects_pilot_package_and_internet(self):
        pilot_xml = self._build_manifest_xml(
            package_id="com.weback.robot.pilot",
            custom_permission="com.weback.robot.pilot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            permissions=[
                "android.permission.INTERNET",
                "com.weback.robot.pilot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            ],
        )
        ok, errors = validate_manifest_xml_structural(pilot_xml, connectivity_policy="offline")
        self.assertFalse(ok)
        self.assertTrue(any("expected 'com.weback.robot'" in e for e in errors))
        self.assertTrue(any("android.permission.INTERNET" in e for e in errors))

    def test_pilot_mode_negative_rejects_extra_permissions_and_backup_and_cleartext(self):
        for bad_perm in (
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
        ):
            bad_xml = self._build_manifest_xml(
                package_id="com.weback.robot.pilot",
                permissions=[
                    "android.permission.INTERNET",
                    "com.weback.robot.pilot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                    bad_perm,
                ],
            )
            ok, errors = validate_manifest_xml_structural(bad_xml, connectivity_policy="pilot")
            self.assertFalse(ok)
            self.assertTrue(any(bad_perm in e for e in errors))

        backup_xml = self._build_manifest_xml(
            package_id="com.weback.robot.pilot",
            permissions=["android.permission.INTERNET"],
            allow_backup="true",
        )
        ok_b, errs_b = validate_manifest_xml_structural(backup_xml, connectivity_policy="pilot")
        self.assertFalse(ok_b)
        self.assertTrue(any("allowBackup must be strictly 'false'" in e for e in errs_b))

        cleartext_xml = self._build_manifest_xml(
            package_id="com.weback.robot.pilot",
            permissions=["android.permission.INTERNET"],
            cleartext="true",
        )
        ok_c, errs_c = validate_manifest_xml_structural(cleartext_xml, connectivity_policy="pilot")
        self.assertFalse(ok_c)
        self.assertTrue(any("usesCleartextTraffic must be strictly 'false'" in e for e in errs_c))

    def test_pilot_mode_negative_rejects_provider_authority_collision(self):
        colliding_xml = self._build_manifest_xml(
            package_id="com.weback.robot.pilot",
            permissions=["android.permission.INTERNET"],
            provider_authority="com.weback.robot.androidx-startup",
        )
        ok, errors = validate_manifest_xml_structural(colliding_xml, connectivity_policy="pilot")
        self.assertFalse(ok)
        self.assertTrue(any("Provider authority" in e for e in errors))

    def test_path_substitution_and_cli_default_offline_binding(self):
        ok_sub_1, errs_1 = validate_apk_path_for_policy(
            Path("app/build/outputs/apk/pilot/app-pilot.apk"), "offline"
        )
        self.assertFalse(ok_sub_1)
        self.assertTrue(any("Artifact substitution rejected" in e for e in errs_1))

        ok_sub_2, errs_2 = validate_apk_path_for_policy(
            Path("app/build/outputs/apk/debug/app-debug.apk"), "pilot"
        )
        self.assertFalse(ok_sub_2)
        self.assertTrue(any("Artifact substitution rejected" in e for e in errs_2))

        res_cli = subprocess.run(
            [
                sys.executable,
                "verification/checks/apk_validator.py",
                "--apk",
                "app/build/outputs/apk/pilot/app-pilot.apk",
            ],
            capture_output=True,
            text=True,
        )
        self.assertEqual(res_cli.returncode, 1)
        self.assertIn("Artifact substitution rejected", res_cli.stderr)

    def test_offline_mode_negative_rejects_alternate_permission_forms(self):
        # uses-permission-sdk-23 with INTERNET or LOCATION must fail in offline mode
        for bad_perm in ("android.permission.INTERNET", "android.permission.ACCESS_FINE_LOCATION", "android.permission.CAMERA"):
            xml_sdk23 = (
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                'package="com.weback.robot" android:versionName="0.1.0-alpha">\n'
                '  <uses-sdk android:minSdkVersion="31" android:targetSdkVersion="35" />\n'
                f'  <uses-permission-sdk-23 android:name="{bad_perm}" />\n'
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
            ok, errors = validate_manifest_xml_structural(xml_sdk23, connectivity_policy="offline")
            self.assertFalse(ok, f"Expected offline rejection for uses-permission-sdk-23 '{bad_perm}'")
            self.assertTrue(any(bad_perm in e for e in errors), f"Expected '{bad_perm}' in errors: {errors}")

        # Nested uses-permission inside <application> must fail
        nested_xml = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="com.weback.robot" android:versionName="0.1.0-alpha">\n'
            '  <uses-sdk android:minSdkVersion="31" android:targetSdkVersion="35" />\n'
            '  <application android:usesCleartextTraffic="false" android:allowBackup="false" '
            'android:networkSecurityConfig="@ref/0x7f0b0000">\n'
            '    <uses-permission android:name="android.permission.INTERNET" />\n'
            '    <activity android:name="com.weback.robot.MainActivity" android:exported="true">\n'
            '      <intent-filter>\n'
            '        <action android:name="android.intent.action.MAIN" />\n'
            '        <category android:name="android.intent.category.LAUNCHER" />\n'
            '      </intent-filter>\n'
            '    </activity>\n'
            '  </application>\n'
            '</manifest>'
        )
        ok_nested, errs_nested = validate_manifest_xml_structural(nested_xml, connectivity_policy="offline")
        self.assertFalse(ok_nested)
        self.assertTrue(any("android.permission.INTERNET" in e for e in errs_nested))

    def test_pilot_mode_negative_rejects_sdk23_extra_and_ineffective_internet(self):
        # uses-permission-sdk-23 with unauthorized external permission must fail in pilot mode
        for bad_perm in ("android.permission.ACCESS_FINE_LOCATION", "android.permission.BLUETOOTH_SCAN", "android.permission.CAMERA"):
            xml_sdk23 = (
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                'package="com.weback.robot.pilot" android:versionName="0.1.0-alpha">\n'
                '  <uses-sdk android:minSdkVersion="31" android:targetSdkVersion="35" />\n'
                '  <uses-permission android:name="android.permission.INTERNET" />\n'
                f'  <uses-permission-sdk-23 android:name="{bad_perm}" />\n'
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
            ok, errors = validate_manifest_xml_structural(xml_sdk23, connectivity_policy="pilot")
            self.assertFalse(ok, f"Expected pilot rejection for uses-permission-sdk-23 '{bad_perm}'")
            self.assertTrue(any(bad_perm in e for e in errors), f"Expected '{bad_perm}' in errors: {errors}")

        # INTERNET with maxSdkVersion < targetSdk is not effective and must fail in pilot mode
        xml_capped_net = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="com.weback.robot.pilot" android:versionName="0.1.0-alpha">\n'
            '  <uses-sdk android:minSdkVersion="31" android:targetSdkVersion="35" />\n'
            '  <uses-permission android:name="android.permission.INTERNET" android:maxSdkVersion="30" />\n'
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
        ok_cap, errs_cap = validate_manifest_xml_structural(xml_capped_net, connectivity_policy="pilot")
        self.assertFalse(ok_cap)
        self.assertTrue(any("INTERNET" in e for e in errs_cap))

        # Unexpected permission-tree / permission-group must fail
        xml_perm_group = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="com.weback.robot.pilot" android:versionName="0.1.0-alpha">\n'
            '  <uses-sdk android:minSdkVersion="31" android:targetSdkVersion="35" />\n'
            '  <uses-permission android:name="android.permission.INTERNET" />\n'
            '  <permission-group android:name="com.weback.robot.pilot.CUSTOM_GROUP" />\n'
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
        ok_pg, errs_pg = validate_manifest_xml_structural(xml_perm_group, connectivity_policy="pilot")
        self.assertFalse(ok_pg)
        self.assertTrue(any("permission-group" in e for e in errs_pg))

    def test_manifest_negative_missing_required_metadata_attributes(self):
        # Missing versionName must fail closed
        no_ver_xml = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="com.weback.robot">\n'
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
        ok, errs = validate_manifest_xml_structural(no_ver_xml)
        self.assertFalse(ok)
        self.assertTrue(any("versionName mismatch" in e for e in errs), errs)

        # Missing <uses-sdk> must fail closed
        no_sdk_xml = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="com.weback.robot" android:versionName="0.1.0-alpha">\n'
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
        ok_sdk, errs_sdk = validate_manifest_xml_structural(no_sdk_xml)
        self.assertFalse(ok_sdk)
        self.assertTrue(any("Missing <uses-sdk>" in e for e in errs_sdk), errs_sdk)

        # Missing minSdkVersion / targetSdkVersion inside <uses-sdk> must fail closed
        empty_sdk_xml = no_sdk_xml.replace(
            "<application",
            "<uses-sdk />\n  <application",
        )
        ok_esdk, errs_esdk = validate_manifest_xml_structural(empty_sdk_xml)
        self.assertFalse(ok_esdk)
        self.assertTrue(any("minSdk mismatch" in e for e in errs_esdk), errs_esdk)
        self.assertTrue(any("targetSdk mismatch" in e for e in errs_esdk), errs_esdk)

        # Missing package attribute must fail closed
        no_pkg_xml = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'android:versionName="0.1.0-alpha">\n'
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
        ok_pkg, errs_pkg = validate_manifest_xml_structural(no_pkg_xml)
        self.assertFalse(ok_pkg)
        self.assertTrue(any("Manifest package mismatch" in e for e in errs_pkg), errs_pkg)

    def test_validate_apk_with_tools_single_apkanalyzer_call_and_parity(self):
        valid_offline_xml = self._build_manifest_xml(
            package_id="com.weback.robot",
            custom_permission="com.weback.robot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            permissions=["com.weback.robot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"],
            provider_authority="com.weback.robot.androidx-startup",
        )
        valid_pilot_xml = self._build_manifest_xml(
            package_id="com.weback.robot.pilot",
            custom_permission="com.weback.robot.pilot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            permissions=[
                "android.permission.INTERNET",
                "com.weback.robot.pilot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            ],
            provider_authority="com.weback.robot.pilot.androidx-startup",
        )

        def run_with_mocked_tools(manifest_stdout: str, policy: str = "offline", apksigner_rc: int = 0):
            calls = []

            def fake_find_tool(name: str):
                return Path(f"/mock/sdk/{name}")

            def fake_run(argv, capture_output=True, text=True, check=False):
                calls.append(list(argv))
                if argv[0].endswith("apkanalyzer"):
                    return subprocess.CompletedProcess(argv, 0, stdout=manifest_stdout, stderr="")
                return subprocess.CompletedProcess(argv, apksigner_rc, stdout="", stderr="sig bad" if apksigner_rc else "")

            with patch("verification.checks.apk_validator.find_sdk_tool", side_effect=fake_find_tool), \
                 patch("verification.checks.apk_validator.subprocess.run", side_effect=fake_run):
                ok, errs = validate_apk_with_tools(Path("app-test.apk"), connectivity_policy=policy)
            return ok, errs, calls

        # Valid offline: exactly 1 apkanalyzer manifest print + 1 apksigner verify
        ok, errs, calls = run_with_mocked_tools(valid_offline_xml, policy="offline")
        self.assertTrue(ok, errs)
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0][:3], ["/mock/sdk/apkanalyzer", "manifest", "print"])
        self.assertEqual(calls[1][:2], ["/mock/sdk/apksigner", "verify"])

        # Valid pilot
        ok_p, errs_p, calls_p = run_with_mocked_tools(valid_pilot_xml, policy="pilot")
        self.assertTrue(ok_p, errs_p)
        self.assertEqual(len(calls_p), 2)

        # Negative parity cases caught via single manifest print
        bad_min_sdk = valid_offline_xml.replace('minSdkVersion="31"', 'minSdkVersion="29"')
        ok_ms, errs_ms, _ = run_with_mocked_tools(bad_min_sdk, policy="offline")
        self.assertFalse(ok_ms)
        self.assertTrue(any("minSdk mismatch" in e for e in errs_ms))

        bad_target_sdk = valid_offline_xml.replace('targetSdkVersion="35"', 'targetSdkVersion="34"')
        ok_ts, errs_ts, _ = run_with_mocked_tools(bad_target_sdk, policy="offline")
        self.assertFalse(ok_ts)
        self.assertTrue(any("targetSdk mismatch" in e for e in errs_ts))

        bad_ver = valid_offline_xml.replace('versionName="0.1.0-alpha"', 'versionName="9.9.9"')
        ok_v, errs_v, _ = run_with_mocked_tools(bad_ver, policy="offline")
        self.assertFalse(ok_v)
        self.assertTrue(any("versionName mismatch" in e for e in errs_v))

        # Signature failure still caught
        ok_sig, errs_sig, _ = run_with_mocked_tools(valid_offline_xml, policy="offline", apksigner_rc=1)
        self.assertFalse(ok_sig)
        self.assertTrue(any("apksigner verification failed" in e for e in errs_sig))

if __name__ == "__main__":
    unittest.main()
