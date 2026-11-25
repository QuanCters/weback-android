#!/usr/bin/env python3
"""
Debug APK Artifact & Security Validator.
Verifies APK existence, SHA-256 checksum, archive structure, package ID, SDK levels,
version name, launcher activity, valid signature, and absence of prohibited vendor binaries.
Standard library only. Zero third-party dependencies.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

EXPECTED_PACKAGE_ID = "com.weback.robot"
EXPECTED_MIN_SDK = "31"
EXPECTED_TARGET_SDK = "35"
EXPECTED_VERSION_NAME = "0.1.0-alpha"
REQUIRED_LAUNCHER_ACTIVITY = "com.weback.robot.MainActivity"

PROHIBITED_BINARY_PATTERNS = [
    "libIOTCAPIs.so",
    "libAVAPIs.so",
    "libsCHL.so",
    "libtutk_ffmpeg.so"
]

PROHIBITED_DEX_PACKAGES = [
    "Lcom/yugong/",
    "Lcom/tutk/",
    "Lcom/weback/cleaner/"
]

PROHIBITED_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.BLUETOOTH",
    "android.permission.BLUETOOTH_ADMIN",
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.CHANGE_WIFI_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_NETWORK_STATE"
}

def get_android_attr(elem: ET.Element, attr_name: str) -> str | None:
    for k, v in elem.attrib.items():
        if k == attr_name or k.endswith(f"}}{attr_name}") or k == f"android:{attr_name}":
            return v
    return None

def parse_dex_classes(data: bytes, dex_name: str = "classes.dex") -> list[str]:
    """
    Parses class definitions from a raw DEX binary byte stream.
    Validates DEX header, declared file size, table offsets and table boundaries.
    Fails closed with ValueError on corruption, invalid bounds, or missing definitions.
    """
    if len(data) < 112:
        raise ValueError(f"Malformed DEX header in '{dex_name}': size {len(data)} is less than minimum header 112 bytes")

    if not (data.startswith(b"dex\n035\x00") or data.startswith(b"dex\n037\x00") or
            data.startswith(b"dex\n038\x00") or data.startswith(b"dex\n039\x00") or
            data.startswith(b"dex\n")):
        raise ValueError(f"Invalid DEX magic in '{dex_name}': {data[:8]!r}")

    # Header file_size check at byte 32 (uint32)
    declared_file_size = struct.unpack_from("<I", data, 32)[0]
    if declared_file_size != len(data):
        raise ValueError(
            f"DEX file size mismatch in '{dex_name}': header declares {declared_file_size} bytes, "
            f"actual file size is {len(data)} bytes"
        )

    # String, type, and class_defs offsets and counts
    string_ids_size, string_ids_off = struct.unpack_from("<II", data, 56)
    type_ids_size, type_ids_off = struct.unpack_from("<II", data, 64)
    class_defs_size, class_defs_off = struct.unpack_from("<II", data, 96)

    # Bounds validation against data length
    if string_ids_size < 0 or string_ids_off < 112 or (string_ids_off + string_ids_size * 4) > len(data):
        raise ValueError(f"Invalid string_ids table bounds in '{dex_name}': off={string_ids_off}, size={string_ids_size}")
    if type_ids_size < 0 or type_ids_off < 112 or (type_ids_off + type_ids_size * 4) > len(data):
        raise ValueError(f"Invalid type_ids table bounds in '{dex_name}': off={type_ids_off}, size={type_ids_size}")
    if class_defs_size < 0 or class_defs_off < 112 or (class_defs_off + class_defs_size * 32) > len(data):
        raise ValueError(f"Invalid class_defs table bounds in '{dex_name}': off={class_defs_off}, size={class_defs_size}")

    if class_defs_size == 0:
        raise ValueError(f"Empty class inventory in '{dex_name}': class_defs_size is 0")

    string_offsets = [struct.unpack_from("<I", data, string_ids_off + i * 4)[0] for i in range(string_ids_size)]

    def get_string(idx: int) -> str:
        if idx < 0 or idx >= len(string_offsets):
            raise ValueError(f"String index {idx} out of range (total: {len(string_offsets)}) in '{dex_name}'")
        off = string_offsets[idx]
        if off >= len(data):
            raise ValueError(f"String offset {off} out of file bounds in '{dex_name}'")
        # Skip UTF-8 length ULEB128
        while off < len(data) and (data[off] & 0x80):
            off += 1
        off += 1
        if off >= len(data):
            raise ValueError(f"String data offset out of bounds in '{dex_name}'")
        end = data.find(b"\x00", off)
        if end == -1:
            raise ValueError(f"Unterminated string data in '{dex_name}' at offset {off}")
        return data[off:end].decode("utf-8", errors="replace")

    type_string_indices = [struct.unpack_from("<I", data, type_ids_off + i * 4)[0] for i in range(type_ids_size)]

    classes = []
    for i in range(class_defs_size):
        class_idx = struct.unpack_from("<I", data, class_defs_off + i * 32)[0]
        if class_idx >= len(type_string_indices):
            raise ValueError(f"Class def {i} type index {class_idx} exceeds type table size {len(type_string_indices)} in '{dex_name}'")
        str_idx = type_string_indices[class_idx]
        classes.append(get_string(str_idx))
    return classes

def validate_dex_classes_in_apk(apk_path: Path) -> tuple[bool, list[str]]:
    """
    Inspects all DEX files in APK archive and asserts:
    1. Every DEX file is well-formed with valid header, size, offsets, and bounds.
    2. Zero prohibited legacy vendor classes exist across all DEX files.
    3. Required MainActivity class descriptor is present across the DEX class inventory.
    """
    errors: list[str] = []
    try:
        with zipfile.ZipFile(apk_path, "r") as zf:
            dex_names = sorted([n for n in zf.namelist() if n.endswith(".dex")])
            if not dex_names:
                errors.append("No DEX files found in APK archive")
                return False, errors

            all_classes: list[str] = []
            for dname in dex_names:
                try:
                    classes = parse_dex_classes(zf.read(dname), dex_name=dname)
                    all_classes.extend(classes)
                except Exception as e:
                    errors.append(f"DEX inspection failure in '{dname}': {e}")

            if not all_classes:
                errors.append("Empty or uninspectable class inventory across APK DEX files")
            else:
                # Check for prohibited legacy vendor classes
                for cls in all_classes:
                    for prohibited in PROHIBITED_DEX_PACKAGES:
                        if cls.startswith(prohibited):
                            errors.append(f"Prohibited legacy vendor DEX class detected: {cls}")

                # Verify presence of clean MainActivity class
                has_main = any(c == "Lcom/weback/robot/MainActivity;" for c in all_classes)
                if not has_main:
                    errors.append("Required MainActivity class descriptor 'Lcom/weback/robot/MainActivity;' not found in APK DEX inventory")
    except Exception as e:
        errors.append(f"Failed to inspect APK DEX archive: {e}")

    return len(errors) == 0, errors

def validate_manifest_xml_structural(manifest_xml: str) -> tuple[bool, list[str]]:
    """
    Structurally validates the printed AndroidManifest.xml ElementTree.
    """
    errors: list[str] = []
    try:
        root = ET.fromstring(manifest_xml)
    except Exception as e:
        return False, [f"Failed to parse manifest XML: {e}"]

    # 1. Package ID
    pkg = root.attrib.get("package") or get_android_attr(root, "package")
    if pkg != EXPECTED_PACKAGE_ID:
        errors.append(f"Manifest package mismatch: expected '{EXPECTED_PACKAGE_ID}', got '{pkg}'")

    # 2. Version name
    ver_name = get_android_attr(root, "versionName")
    if ver_name and ver_name != EXPECTED_VERSION_NAME:
        errors.append(f"Manifest versionName mismatch: expected '{EXPECTED_VERSION_NAME}', got '{ver_name}'")

    # 3. uses-sdk
    uses_sdk = root.find("uses-sdk")
    if uses_sdk is not None:
        min_sdk = get_android_attr(uses_sdk, "minSdkVersion")
        target_sdk = get_android_attr(uses_sdk, "targetSdkVersion")
        if min_sdk and min_sdk != EXPECTED_MIN_SDK:
            errors.append(f"Manifest minSdk mismatch: expected '{EXPECTED_MIN_SDK}', got '{min_sdk}'")
        if target_sdk and target_sdk != EXPECTED_TARGET_SDK:
            errors.append(f"Manifest targetSdk mismatch: expected '{EXPECTED_TARGET_SDK}', got '{target_sdk}'")

    # 4. application tag & security settings
    app = root.find("application")
    if app is None:
        errors.append("Missing <application> element in manifest XML")
    else:
        cleartext = get_android_attr(app, "usesCleartextTraffic")
        if cleartext != "false":
            errors.append(f"usesCleartextTraffic must be strictly 'false', got '{cleartext}'")

        allow_backup = get_android_attr(app, "allowBackup")
        if allow_backup != "false":
            errors.append(f"allowBackup must be strictly 'false', got '{allow_backup}'")

        sec_config = get_android_attr(app, "networkSecurityConfig")
        if not sec_config:
            errors.append("networkSecurityConfig attribute missing from <application>")

        # 5. Launcher Activity verification with exact intent-filter
        launcher_found = False
        for act in app.findall("activity"):
            name = get_android_attr(act, "name")
            exported = get_android_attr(act, "exported")
            if name in (REQUIRED_LAUNCHER_ACTIVITY, ".MainActivity", "MainActivity"):
                if exported != "true":
                    errors.append(f"Launcher activity '{name}' must have exported='true', got '{exported}'")
                    continue

                # Require MAIN action and LAUNCHER category within the SAME intent-filter
                matching_filter = False
                for ifilter in act.findall("intent-filter"):
                    has_main_in_filter = any(
                        get_android_attr(action, "name") == "android.intent.action.MAIN"
                        for action in ifilter.findall("action")
                    )
                    has_launcher_in_filter = any(
                        get_android_attr(cat, "name") == "android.intent.category.LAUNCHER"
                        for cat in ifilter.findall("category")
                    )
                    if has_main_in_filter and has_launcher_in_filter:
                        matching_filter = True
                        break

                if matching_filter:
                    launcher_found = True
                    break
                else:
                    errors.append(
                        f"Launcher activity '{name}' is missing an intent-filter declaring both "
                        f"action 'android.intent.action.MAIN' and category 'android.intent.category.LAUNCHER' "
                        f"within the same intent-filter"
                    )

        if not launcher_found and not any("missing an intent-filter" in e or "must have exported" in e for e in errors):
            errors.append(f"Required launcher activity '{REQUIRED_LAUNCHER_ACTIVITY}' not found in manifest")

    # 6. Approved permissions boundary verification (Zero external application permissions authorized for FCT-001)
    ALLOWED_INTERNAL_PERMISSIONS = {
        f"{EXPECTED_PACKAGE_ID}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    }
    for up in root.findall("uses-permission"):
        pname = get_android_attr(up, "name")
        if pname in PROHIBITED_PERMISSIONS:
            errors.append(f"Prohibited application permission detected in manifest: '{pname}'")
        elif pname not in ALLOWED_INTERNAL_PERMISSIONS:
            errors.append(f"Unauthorized application permission detected in manifest: '{pname}'. FCT-001 authorizes zero external application permissions.")

    return len(errors) == 0, errors

def find_sdk_tool(tool_name: str) -> Path | None:
    # 1. PATH lookup
    p = shutil.which(tool_name)
    if p:
        return Path(p)

    # 2. ANDROID_HOME lookup
    android_home = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/.android/sdk")
    home_path = Path(android_home)

    candidates = [
        home_path / "cmdline-tools" / "latest" / "bin" / tool_name,
        home_path / "platform-tools" / tool_name,
        home_path / "build-tools" / "35.0.0" / tool_name,
        home_path / "build-tools" / "34.0.0" / tool_name,
    ]
    for c in candidates:
        if c.is_file() and os.access(c, os.X_OK):
            return c
    return None

def compute_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def validate_apk_archive(apk_path: Path) -> tuple[bool, list[str]]:
    errors: list[str] = []
    try:
        with zipfile.ZipFile(apk_path, "r") as zf:
            # 1. Test zip integrity
            corrupted = zf.testzip()
            if corrupted:
                errors.append(f"Corrupted entry in APK archive: {corrupted}")

            names = set(zf.namelist())

            # 2. Essential files
            for req in ["AndroidManifest.xml", "classes.dex", "resources.arsc"]:
                if req not in names:
                    errors.append(f"Missing essential file in APK archive: {req}")

            # 3. Prohibited vendor binaries in lib/
            for name in names:
                for prohibited in PROHIBITED_BINARY_PATTERNS:
                    if prohibited in name:
                        errors.append(f"Prohibited vendor binary detected in APK: {name}")

    except Exception as e:
        errors.append(f"Failed to inspect APK archive: {e}")

    return len(errors) == 0, errors

def validate_apk_with_tools(apk_path: Path) -> tuple[bool, list[str]]:
    errors: list[str] = []
    apkanalyzer = find_sdk_tool("apkanalyzer")
    apksigner = find_sdk_tool("apksigner")

    if not apkanalyzer:
        errors.append("apkanalyzer binary not found in PATH or ANDROID_HOME")
    else:
        # Check application ID
        try:
            res = subprocess.run([str(apkanalyzer), "manifest", "application-id", str(apk_path)],
                                 capture_output=True, text=True, check=True)
            app_id = res.stdout.strip()
            if app_id != EXPECTED_PACKAGE_ID:
                errors.append(f"Application ID mismatch: expected '{EXPECTED_PACKAGE_ID}', got '{app_id}'")
        except Exception as e:
            errors.append(f"Failed to query application-id via apkanalyzer: {e}")

        # Check minSdk
        try:
            res = subprocess.run([str(apkanalyzer), "manifest", "min-sdk", str(apk_path)],
                                 capture_output=True, text=True, check=True)
            min_sdk = res.stdout.strip()
            if min_sdk != EXPECTED_MIN_SDK:
                errors.append(f"minSdk mismatch: expected '{EXPECTED_MIN_SDK}', got '{min_sdk}'")
        except Exception as e:
            errors.append(f"Failed to query min-sdk via apkanalyzer: {e}")

        # Check targetSdk
        try:
            res = subprocess.run([str(apkanalyzer), "manifest", "target-sdk", str(apk_path)],
                                 capture_output=True, text=True, check=True)
            target_sdk = res.stdout.strip()
            if target_sdk != EXPECTED_TARGET_SDK:
                errors.append(f"targetSdk mismatch: expected '{EXPECTED_TARGET_SDK}', got '{target_sdk}'")
        except Exception as e:
            errors.append(f"Failed to query target-sdk via apkanalyzer: {e}")

        # Check version-name
        try:
            res = subprocess.run([str(apkanalyzer), "manifest", "version-name", str(apk_path)],
                                 capture_output=True, text=True, check=True)
            version_name = res.stdout.strip()
            if version_name != EXPECTED_VERSION_NAME:
                errors.append(f"version-name mismatch: expected '{EXPECTED_VERSION_NAME}', got '{version_name}'")
        except Exception as e:
            errors.append(f"Failed to query version-name via apkanalyzer: {e}")

        # Check structural manifest XML via apkanalyzer
        try:
            res = subprocess.run([str(apkanalyzer), "manifest", "print", str(apk_path)],
                                 capture_output=True, text=True, check=True)
            manifest_xml = res.stdout
            m_ok, m_errs = validate_manifest_xml_structural(manifest_xml)
            errors.extend(m_errs)
        except Exception as e:
            errors.append(f"Failed to print manifest via apkanalyzer: {e}")

    if not apksigner:
        errors.append("apksigner binary not found in PATH or ANDROID_HOME")
    else:
        try:
            res = subprocess.run([str(apksigner), "verify", "--verbose", str(apk_path)],
                                 capture_output=True, text=True)
            if res.returncode != 0:
                errors.append(f"apksigner verification failed with code {res.returncode}: {res.stderr.strip() or res.stdout.strip()}")
        except Exception as e:
            errors.append(f"Failed to execute apksigner verify: {e}")

    return len(errors) == 0, errors

def validate_apk(apk_path: Path) -> tuple[bool, list[str], str]:
    errors: list[str] = []
    if not apk_path.is_file():
        return False, [f"APK artifact not found at {apk_path}"], ""

    size = apk_path.stat().st_size
    if size == 0:
        return False, [f"APK artifact is empty (0 bytes) at {apk_path}"], ""

    sha256 = compute_sha256(apk_path)

    # Archive checks
    arch_ok, arch_errs = validate_apk_archive(apk_path)
    errors.extend(arch_errs)

    # DEX bytecode class inspection
    dex_ok, dex_errs = validate_dex_classes_in_apk(apk_path)
    errors.extend(dex_errs)

    # Tool checks
    tool_ok, tool_errs = validate_apk_with_tools(apk_path)
    errors.extend(tool_errs)

    return len(errors) == 0, errors, sha256

def main():
    parser = argparse.ArgumentParser(description="Debug APK Artifact & Security Validator")
    parser.add_argument("--apk", type=str, default="app/build/outputs/apk/debug/app-debug.apk",
                        help="Path to debug APK file")
    args = parser.parse_args()

    apk_path = Path(args.apk)
    valid, errors, sha256 = validate_apk(apk_path)

    if valid:
        print("[SUCCESS] APK Artifact and Security validation passed.")
        print(f"  Artifact Path:  {apk_path}")
        print(f"  Size:           {apk_path.stat().st_size} bytes")
        print(f"  SHA-256 Digest: {sha256}")
        print(f"  Application ID: {EXPECTED_PACKAGE_ID}")
        print(f"  minSdk:         {EXPECTED_MIN_SDK}")
        print(f"  targetSdk:      {EXPECTED_TARGET_SDK}")
        print(f"  versionName:    {EXPECTED_VERSION_NAME}")
        sys.exit(0)
    else:
        print("[FAILURE] APK validation failed:", file=sys.stderr)
        for err in errors:
            print(f"  [-] {err}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
