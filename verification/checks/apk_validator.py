#!/usr/bin/env python3
"""
APK Artifact & Security Validator.
Supports explicit --connectivity-policy {offline,pilot} (offline default), binding each mode
to its expected APK path, application ID, permission allowlist, provider authority scope,
cleartext/backup flags, signature verification, DEX class inventory, and vendor binary exclusion.
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

EXPECTED_MIN_SDK = "31"
EXPECTED_TARGET_SDK = "35"
EXPECTED_VERSION_NAME = "0.1.0-alpha"
REQUIRED_LAUNCHER_ACTIVITY = "com.weback.robot.MainActivity"

CONNECTIVITY_POLICY_CONFIG = {
    "offline": {
        "expected_apk_path": Path("app/build/outputs/apk/debug/app-debug.apk"),
        "expected_package_id": "com.weback.robot",
        "required_external_permissions": set(),
        "allowed_external_permissions": set(),
        "forbidden_variant_tokens": ("pilot", "app-pilot.apk"),
    },
    "pilot": {
        "expected_apk_path": Path("app/build/outputs/apk/pilot/app-pilot.apk"),
        "expected_package_id": "com.weback.robot.pilot",
        "required_external_permissions": {"android.permission.INTERNET"},
        "allowed_external_permissions": {"android.permission.INTERNET"},
        "forbidden_variant_tokens": ("debug", "app-debug.apk"),
    },
}

# Preserve backward-compatible module constant for offline default
EXPECTED_PACKAGE_ID = CONNECTIVITY_POLICY_CONFIG["offline"]["expected_package_id"]

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

def get_policy_config(connectivity_policy: str) -> dict:
    if connectivity_policy not in CONNECTIVITY_POLICY_CONFIG:
        raise ValueError(f"Unsupported connectivity policy: '{connectivity_policy}'")
    return CONNECTIVITY_POLICY_CONFIG[connectivity_policy]

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

    declared_file_size = struct.unpack_from("<I", data, 32)[0]
    if declared_file_size != len(data):
        raise ValueError(
            f"DEX file size mismatch in '{dex_name}': header declares {declared_file_size} bytes, "
            f"actual file size is {len(data)} bytes"
        )

    string_ids_size, string_ids_off = struct.unpack_from("<II", data, 56)
    type_ids_size, type_ids_off = struct.unpack_from("<II", data, 64)
    class_defs_size, class_defs_off = struct.unpack_from("<II", data, 96)

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
                for cls in all_classes:
                    for prohibited in PROHIBITED_DEX_PACKAGES:
                        if cls.startswith(prohibited):
                            errors.append(f"Prohibited legacy vendor DEX class detected: {cls}")

                has_main = any(c == "Lcom/weback/robot/MainActivity;" for c in all_classes)
                if not has_main:
                    errors.append("Required MainActivity class descriptor 'Lcom/weback/robot/MainActivity;' not found in APK DEX inventory")
    except Exception as e:
        errors.append(f"Failed to inspect APK DEX archive: {e}")

    return len(errors) == 0, errors

def validate_manifest_xml_structural(
    manifest_xml: str,
    connectivity_policy: str = "offline"
) -> tuple[bool, list[str]]:
    """
    Structurally validates the printed AndroidManifest.xml ElementTree against the
    selected connectivity policy ('offline' or 'pilot').
    """
    try:
        cfg = get_policy_config(connectivity_policy)
    except ValueError as ve:
        return False, [str(ve)]

    expected_package_id = cfg["expected_package_id"]
    required_external_perms = cfg["required_external_permissions"]
    allowed_external_perms = cfg["allowed_external_permissions"]

    errors: list[str] = []
    try:
        root = ET.fromstring(manifest_xml)
    except Exception as e:
        return False, [f"Failed to parse manifest XML: {e}"]

    # 1. Package ID
    pkg = root.attrib.get("package") or get_android_attr(root, "package")
    if pkg != expected_package_id:
        errors.append(
            f"Manifest package mismatch for '{connectivity_policy}' policy: "
            f"expected '{expected_package_id}', got '{pkg}'"
        )

    # 2. Version name (required non-empty exact match)
    ver_name = get_android_attr(root, "versionName")
    if not ver_name or ver_name != EXPECTED_VERSION_NAME:
        errors.append(f"Manifest versionName mismatch: expected '{EXPECTED_VERSION_NAME}', got '{ver_name}'")

    # 3. uses-sdk (required element with non-empty minSdkVersion and targetSdkVersion)
    uses_sdk = root.find("uses-sdk")
    if uses_sdk is None:
        errors.append("Missing <uses-sdk> element in manifest XML")
    else:
        min_sdk = get_android_attr(uses_sdk, "minSdkVersion")
        target_sdk = get_android_attr(uses_sdk, "targetSdkVersion")
        if not min_sdk or min_sdk != EXPECTED_MIN_SDK:
            errors.append(f"Manifest minSdk mismatch: expected '{EXPECTED_MIN_SDK}', got '{min_sdk}'")
        if not target_sdk or target_sdk != EXPECTED_TARGET_SDK:
            errors.append(f"Manifest targetSdk mismatch: expected '{EXPECTED_TARGET_SDK}', got '{target_sdk}'")

    # 4. application tag, security settings, launcher activity & provider authorities
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

        for provider in app.findall("provider"):
            authorities = get_android_attr(provider, "authorities") or ""
            for auth in [a.strip() for a in authorities.split(";") if a.strip()]:
                if not auth.startswith(f"{expected_package_id}."):
                    errors.append(
                        f"Provider authority '{auth}' does not match expected package namespace '{expected_package_id}.*'"
                    )

        # 5. Launcher Activity verification with exact intent-filter
        launcher_found = False
        for act in app.findall("activity"):
            name = get_android_attr(act, "name")
            exported = get_android_attr(act, "exported")
            if name in (REQUIRED_LAUNCHER_ACTIVITY, ".MainActivity", "MainActivity"):
                if exported != "true":
                    errors.append(f"Launcher activity '{name}' must have exported='true', got '{exported}'")
                    continue

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

    # 6. Approved permissions boundary verification per connectivity policy
    allowed_internal_perm = f"{expected_package_id}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    allowed_permissions = {allowed_internal_perm} | allowed_external_perms

    uses_permission_tags = {"uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"}
    effective_declared_uses_permissions: set[str] = set()

    for elem in root.iter():
        local_tag = elem.tag.rsplit("}", 1)[-1]
        if local_tag == "permission":
            declared_name = get_android_attr(elem, "name")
            if declared_name != allowed_internal_perm:
                errors.append(
                    f"Unexpected custom <permission> '{declared_name}' for '{connectivity_policy}' policy; "
                    f"expected only '{allowed_internal_perm}'"
                )
        elif local_tag in ("permission-tree", "permission-group"):
            declared_name = get_android_attr(elem, "name")
            errors.append(
                f"Unexpected custom <{local_tag}> '{declared_name}' for '{connectivity_policy}' policy"
            )
        elif local_tag in uses_permission_tags:
            pname = get_android_attr(elem, "name")
            if not pname:
                errors.append(
                    f"Permission declaration <{local_tag}> is missing required android:name attribute"
                )
                continue
            if pname not in allowed_permissions:
                if pname in PROHIBITED_PERMISSIONS:
                    errors.append(f"Prohibited application permission detected in manifest: '{pname}'")
                elif connectivity_policy == "offline":
                    errors.append(
                        f"Unauthorized application permission detected in manifest: '{pname}'. "
                        f"FCT-001 authorizes zero external application permissions."
                    )
                else:
                    errors.append(
                        f"Unauthorized application permission detected in manifest for '{connectivity_policy}' policy: '{pname}'."
                    )
                continue

            max_sdk_raw = get_android_attr(elem, "maxSdkVersion")
            is_effective = True
            if max_sdk_raw is not None:
                try:
                    max_sdk_val = int(max_sdk_raw)
                    if max_sdk_val < int(EXPECTED_TARGET_SDK):
                        is_effective = False
                except ValueError:
                    is_effective = False
            if is_effective:
                effective_declared_uses_permissions.add(pname)
            elif pname in required_external_perms:
                errors.append(
                    f"Required permission '{pname}' in <{local_tag}> is not effective up to targetSdk {EXPECTED_TARGET_SDK} (maxSdkVersion='{max_sdk_raw}')"
                )

    for req_perm in sorted(required_external_perms):
        if req_perm not in effective_declared_uses_permissions:
            errors.append(
                f"Missing required permission '{req_perm}' for '{connectivity_policy}' connectivity policy"
            )

    return len(errors) == 0, errors

def validate_apk_path_for_policy(apk_path: Path, connectivity_policy: str) -> tuple[bool, list[str]]:
    """
    Rejects obvious cross-variant APK artifact substitution when an explicit --apk path
    targets a build output path belonging to the opposite connectivity policy.
    """
    cfg = get_policy_config(connectivity_policy)
    expected_path = cfg["expected_apk_path"]
    parts_lower = [p.lower() for p in apk_path.parts]
    name_lower = apk_path.name.lower()
    errors: list[str] = []

    if name_lower in ("app-debug.apk", "app-pilot.apk") and name_lower != expected_path.name:
        errors.append(
            f"Artifact substitution rejected: '{apk_path}' cannot be validated under "
            f"'--connectivity-policy {connectivity_policy}' (expected '{expected_path}')"
        )
    elif "outputs" in parts_lower and "apk" in parts_lower:
        if any(tok in parts_lower for tok in cfg["forbidden_variant_tokens"]):
            errors.append(
                f"Artifact path '{apk_path}' targets opposite variant for "
                f"'--connectivity-policy {connectivity_policy}' (expected '{expected_path}')"
            )
    return len(errors) == 0, errors

def find_sdk_tool(tool_name: str) -> Path | None:
    p = shutil.which(tool_name)
    if p:
        return Path(p)

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
            corrupted = zf.testzip()
            if corrupted:
                errors.append(f"Corrupted entry in APK archive: {corrupted}")

            names = set(zf.namelist())

            for req in ["AndroidManifest.xml", "classes.dex", "resources.arsc"]:
                if req not in names:
                    errors.append(f"Missing essential file in APK archive: {req}")

            for name in names:
                for prohibited in PROHIBITED_BINARY_PATTERNS:
                    if prohibited in name:
                        errors.append(f"Prohibited vendor binary detected in APK: {name}")

    except Exception as e:
        errors.append(f"Failed to inspect APK archive: {e}")

    return len(errors) == 0, errors

def validate_apk_with_tools(
    apk_path: Path,
    connectivity_policy: str = "offline"
) -> tuple[bool, list[str]]:
    # Validate policy upfront so invalid connectivity_policy fails closed consistently
    get_policy_config(connectivity_policy)
    errors: list[str] = []
    apkanalyzer = find_sdk_tool("apkanalyzer")
    apksigner = find_sdk_tool("apksigner")

    if not apkanalyzer:
        errors.append("apkanalyzer binary not found in PATH or ANDROID_HOME")
    else:
        # Single apkanalyzer manifest print call validates package, versionName,
        # minSdkVersion, targetSdkVersion, permissions, components, and security flags.
        try:
            res = subprocess.run([str(apkanalyzer), "manifest", "print", str(apk_path)],
                                 capture_output=True, text=True, check=True)
            manifest_xml = res.stdout
            _, m_errs = validate_manifest_xml_structural(manifest_xml, connectivity_policy=connectivity_policy)
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

def validate_apk(
    apk_path: Path,
    connectivity_policy: str = "offline"
) -> tuple[bool, list[str], str]:
    try:
        get_policy_config(connectivity_policy)
    except ValueError as ve:
        return False, [str(ve)], ""

    path_ok, path_errs = validate_apk_path_for_policy(apk_path, connectivity_policy)
    if not path_ok:
        return False, path_errs, ""

    errors: list[str] = []
    if not apk_path.is_file():
        return False, [f"APK artifact not found at {apk_path}"], ""

    size = apk_path.stat().st_size
    if size == 0:
        return False, [f"APK artifact is empty (0 bytes) at {apk_path}"], ""

    sha256 = compute_sha256(apk_path)

    _, arch_errs = validate_apk_archive(apk_path)
    errors.extend(arch_errs)

    _, dex_errs = validate_dex_classes_in_apk(apk_path)
    errors.extend(dex_errs)

    _, tool_errs = validate_apk_with_tools(apk_path, connectivity_policy=connectivity_policy)
    errors.extend(tool_errs)

    return len(errors) == 0, errors, sha256

def main():
    parser = argparse.ArgumentParser(description="APK Artifact & Security Validator")
    parser.add_argument(
        "--connectivity-policy",
        choices=["offline", "pilot"],
        default="offline",
        help="Target connectivity policy mode ('offline' default or 'pilot')"
    )
    parser.add_argument(
        "--apk",
        type=str,
        default=None,
        help="Optional APK path (defaults to the exact artifact bound to --connectivity-policy)"
    )
    args = parser.parse_args()

    cfg = get_policy_config(args.connectivity_policy)
    apk_path = Path(args.apk) if args.apk else cfg["expected_apk_path"]
    valid, errors, sha256 = validate_apk(apk_path, connectivity_policy=args.connectivity_policy)

    if valid:
        print("[SUCCESS] APK Artifact and Security validation passed.")
        print(f"  Policy Mode:    {args.connectivity_policy}")
        print(f"  Artifact Path:  {apk_path}")
        print(f"  Size:           {apk_path.stat().st_size} bytes")
        print(f"  SHA-256 Digest: {sha256}")
        print(f"  Application ID: {cfg['expected_package_id']}")
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
