#!/usr/bin/env python3
"""
Gradle Wrapper Integrity & Checksum Validator.
Verifies gradle-wrapper.properties and gradle-wrapper.jar against official SHA-256 releases.
Standard library only. Zero third-party dependencies.
"""
import argparse
import hashlib
from pathlib import Path
import sys

EXPECTED_GRADLE_VERSION = "8.10.2"
EXPECTED_DISTRIBUTION_SHA256 = "31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26"
EXPECTED_WRAPPER_JAR_SHA256 = "2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046"

def parse_properties(content: str) -> dict[str, str]:
    props = {}
    for line in content.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        if "=" in line:
            key, val = line.split("=", 1)
            props[key.strip()] = val.strip().replace(r"\:", ":")
    return props

def validate_wrapper(repo_root: Path) -> tuple[bool, list[str]]:
    errors: list[str] = []
    wrapper_dir = repo_root / "gradle" / "wrapper"
    props_path = wrapper_dir / "gradle-wrapper.properties"
    jar_path = wrapper_dir / "gradle-wrapper.jar"

    if not props_path.is_file():
        errors.append(f"Missing gradle-wrapper.properties at {props_path}")
    else:
        try:
            content = props_path.read_text(encoding="utf-8")
            props = parse_properties(content)

            dist_url = props.get("distributionUrl", "")
            if not dist_url:
                errors.append("gradle-wrapper.properties missing 'distributionUrl'")
            else:
                if not dist_url.startswith("https://"):
                    errors.append(f"Insecure distributionUrl: '{dist_url}' (must use HTTPS)")
                if EXPECTED_GRADLE_VERSION not in dist_url:
                    errors.append(f"distributionUrl does not declare expected Gradle version {EXPECTED_GRADLE_VERSION}: '{dist_url}'")

            dist_sha = props.get("distributionSha256Sum", "").lower()
            if not dist_sha:
                errors.append("gradle-wrapper.properties missing 'distributionSha256Sum'")
            elif dist_sha != EXPECTED_DISTRIBUTION_SHA256.lower():
                errors.append(f"distributionSha256Sum mismatch: expected {EXPECTED_DISTRIBUTION_SHA256}, got {dist_sha}")

        except Exception as e:
            errors.append(f"Failed to read/parse gradle-wrapper.properties: {e}")

    if not jar_path.is_file():
        errors.append(f"Missing gradle-wrapper.jar at {jar_path}")
    else:
        try:
            jar_bytes = jar_path.read_bytes()
            actual_jar_sha = hashlib.sha256(jar_bytes).hexdigest().lower()
            if actual_jar_sha != EXPECTED_WRAPPER_JAR_SHA256.lower():
                errors.append(f"gradle-wrapper.jar SHA-256 mismatch: expected {EXPECTED_WRAPPER_JAR_SHA256}, got {actual_jar_sha}")
        except Exception as e:
            errors.append(f"Failed to read/hash gradle-wrapper.jar: {e}")

    return len(errors) == 0, errors

def main():
    parser = argparse.ArgumentParser(description="Gradle Wrapper integrity and checksum checker")
    parser.add_argument("--repo-root", type=str, default=".", help="Root path of repository")
    args = parser.parse_args()

    repo_root = Path(args.repo_root).resolve()
    valid, errors = validate_wrapper(repo_root)

    if valid:
        print("[SUCCESS] Gradle Wrapper configuration and binary checksum verified.")
        print(f"  Gradle version: {EXPECTED_GRADLE_VERSION}")
        print(f"  Distribution SHA-256: {EXPECTED_DISTRIBUTION_SHA256}")
        print(f"  Wrapper JAR SHA-256:  {EXPECTED_WRAPPER_JAR_SHA256}")
        sys.exit(0)
    else:
        print("[FAILURE] Gradle Wrapper integrity validation failed:")
        for err in errors:
            print(f"  [-] {err}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
