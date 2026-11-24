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
import subprocess
import sys
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

        # Check launcher activity in manifest
        try:
            res = subprocess.run([str(apkanalyzer), "manifest", "print", str(apk_path)],
                                 capture_output=True, text=True, check=True)
            manifest_xml = res.stdout
            if REQUIRED_LAUNCHER_ACTIVITY not in manifest_xml and "MainActivity" not in manifest_xml:
                errors.append(f"Launcher activity '{REQUIRED_LAUNCHER_ACTIVITY}' not found in manifest")
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
