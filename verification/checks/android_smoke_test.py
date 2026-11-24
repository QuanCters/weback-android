#!/usr/bin/env python3
"""
Android Installation & Runtime Smoke Test Runner.
Executes adb install, launches MainActivity, verifies process survival (5s),
and checks for application crashes in logcat on an explicitly authorized test device.
Reports SKIPPED / NOT_RUN when no authorized device is configured.
Standard library only. Zero third-party dependencies.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

EXPECTED_PACKAGE = "com.weback.robot"
EXPECTED_ACTIVITY = "com.weback.robot.MainActivity"

def find_adb() -> Path | None:
    p = shutil.which("adb")
    if p:
        return Path(p)
    android_home = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/.android/sdk")
    adb_cand = Path(android_home) / "platform-tools" / "adb"
    if adb_cand.is_file() and os.access(adb_cand, os.X_OK):
        return adb_cand
    return None

def run_smoke_test(apk_path: Path, device_serial: str | None) -> int:
    if not device_serial:
        # Fall back to env var
        device_serial = os.environ.get("WEBACK_TEST_DEVICE_SERIAL")

    if not device_serial or not device_serial.strip():
        print("[SMOKE TEST SKIPPED: No authorized test device specified]")
        print("Status: NOT_RUN (Set WEBACK_TEST_DEVICE_SERIAL to authorize runtime smoke test on a specific device)")
        # Clean skip does not masquerade as PASS; exits 0 with NOT_RUN status classification
        return 0

    serial = device_serial.strip()
    adb = find_adb()
    if not adb:
        sys.stderr.write("[-] adb executable not found\n")
        return 1

    if not apk_path.is_file():
        sys.stderr.write(f"[-] APK artifact not found at {apk_path}\n")
        return 1

    print(f"Executing runtime smoke test on authorized device: {serial}")

    # 1. Verify device state
    res = subprocess.run([str(adb), "-s", serial, "get-state"], capture_output=True, text=True)
    if res.returncode != 0 or "device" not in res.stdout:
        sys.stderr.write(f"[-] Device {serial} not in ready state: {res.stdout.strip()} {res.stderr.strip()}\n")
        return 1

    # 2. Install APK
    print(f"Installing {apk_path.name}...")
    install_res = subprocess.run([str(adb), "-s", serial, "install", "-r", str(apk_path)],
                                 capture_output=True, text=True)
    if install_res.returncode != 0 or "Success" not in install_res.stdout:
        sys.stderr.write(f"[-] Installation failed: {install_res.stdout.strip()} {install_res.stderr.strip()}\n")
        return 1
    print("[+] Installation successful.")

    # 3. Launch MainActivity
    print(f"Launching {EXPECTED_PACKAGE}/.{EXPECTED_ACTIVITY}...")
    launch_res = subprocess.run(
        [str(adb), "-s", serial, "shell", "am", "start", "-W", "-n", f"{EXPECTED_PACKAGE}/.{EXPECTED_ACTIVITY}"],
        capture_output=True, text=True
    )
    if launch_res.returncode != 0:
        sys.stderr.write(f"[-] Activity start failed: {launch_res.stdout.strip()} {launch_res.stderr.strip()}\n")
        return 1
    print("[+] Activity launch command accepted.")

    # 4. Process survival check (5 seconds)
    print("Verifying process survival for 5 seconds...")
    time.sleep(5)
    pid_res = subprocess.run(
        [str(adb), "-s", serial, "shell", "pidof", EXPECTED_PACKAGE],
        capture_output=True, text=True
    )
    pid = pid_res.stdout.strip()
    if not pid:
        sys.stderr.write(f"[-] Process {EXPECTED_PACKAGE} is not running after 5 seconds!\n")
        return 1
    print(f"[+] Process {EXPECTED_PACKAGE} is alive (PID: {pid}).")

    # 5. Check logcat for fatal crashes
    log_res = subprocess.run(
        [str(adb), "-s", serial, "logcat", "-d", f"--pid={pid}"],
        capture_output=True, text=True
    )
    log_out = log_res.stdout
    if "FATAL EXCEPTION" in log_out or "AndroidRuntime: FATAL" in log_out:
        sys.stderr.write("[-] Fatal crash exception detected in application logcat!\n")
        sys.stderr.write(log_out[:2000] + "\n")
        return 1

    print("[SUCCESS] Runtime smoke test passed: installed, launched, process survived, zero fatal crashes.")
    return 0

def main():
    parser = argparse.ArgumentParser(description="Android installation and runtime smoke test")
    parser.add_argument("--apk", type=str, default="app/build/outputs/apk/debug/app-debug.apk",
                        help="Path to debug APK file")
    parser.add_argument("--serial", type=str, default=None,
                        help="Authorized device serial (or WEBACK_TEST_DEVICE_SERIAL env var)")
    args = parser.parse_args()

    sys.exit(run_smoke_test(Path(args.apk), args.serial))

if __name__ == "__main__":
    main()
