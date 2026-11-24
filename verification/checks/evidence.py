"""
Evidence Collector, Report Generator, Cryptographic Log Hasher, and Trust Auditor.
Standard-library only, zero external dependencies.
"""
from dataclasses import asdict
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import sys
import uuid
from typing import Optional

# Ensure root repository is importable
repo_root = Path(__file__).resolve().parent.parent.parent
if str(repo_root) not in sys.path:
    sys.path.insert(0, str(repo_root))

from verification.checks.runner import CheckResult

REPORT_SCHEMA_VERSION = 1

def compute_sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def verify_log_hash(content: bytes, expected_sha: str) -> bool:
    return hashlib.sha256(content).hexdigest().lower() == expected_sha.lower()

def recompute_overall_verdict(results: list[CheckResult], required_ids: Optional[set[str]] = None) -> str:
    """
    Recomputes run verdict from individual check results.
    If required_ids is specified, only required checks influence PASS/FAIL verdict.
    """
    verdict = "PASS"
    for r in results:
        is_required = True if required_ids is None else (r.id in required_ids)
        if is_required:
            if r.status == "ERROR":
                return "ERROR"
            elif r.status in ("FAIL", "TIMEOUT") or r.exit_code != 0:
                verdict = "FAIL"
    return verdict

def generate_evidence_report(
    results: list[CheckResult],
    tested_sha: str,
    base_sha: str,
    policy_sha256: str,
    policy_modified_from_base: bool,
    lane: str,
    output_dir: Path,
    started_at: Optional[str] = None,
    finished_at: Optional[str] = None,
    run_id: Optional[str] = None,
    required_ids: Optional[set[str]] = None
) -> Path:
    """
    Writes raw logs for each check to output_dir/<id>.log, computes hashes,
    and writes report.json.
    """
    output_dir.mkdir(parents=True, exist_ok=True)
    assigned_run_id = run_id or str(uuid.uuid4())
    start_ts = started_at or datetime.datetime.now(datetime.timezone.utc).isoformat()
    finish_ts = finished_at or datetime.datetime.now(datetime.timezone.utc).isoformat()

    checks_entries = []
    for r in results:
        log_filename = f"{r.id}.log"
        log_path = output_dir / log_filename
        log_path.write_bytes(r.output)
        log_sha = compute_sha256_bytes(r.output)

        entry = {
            "id": r.id,
            "status": r.status,
            "exit_code": r.exit_code,
            "duration_seconds": round(r.duration_seconds, 4),
            "log_file": log_filename,
            "log_sha256": log_sha
        }
        if r.test_summary:
            entry["test_summary"] = r.test_summary
        if r.cleanup_failed:
            entry["cleanup_failed"] = True
        checks_entries.append(entry)

    verdict = recompute_overall_verdict(results, required_ids)

    report_data = {
        "schema_version": REPORT_SCHEMA_VERSION,
        "run_id": assigned_run_id,
        "tested_sha": tested_sha,
        "base_sha": base_sha,
        "policy_sha256": policy_sha256,
        "policy_modified_from_base": policy_modified_from_base,
        "lane": lane,
        "started_at": start_ts,
        "finished_at": finish_ts,
        "environment": {
            "python_version": sys.version.split()[0],
            "platform": sys.platform,
            "os": platform.platform()
        },
        "verdict": verdict,
        "checks": checks_entries
    }

    report_path = output_dir / "report.json"
    report_path.write_text(json.dumps(report_data, indent=2))
    return report_path

def validate_report_schema_dict(data: dict) -> list[str]:
    """
    Structural validation of report dictionary matching report.schema.json.
    """
    errors = []
    if not isinstance(data, dict):
        return ["Schema validation failed: root must be an object"]

    required_keys = [
        "schema_version", "run_id", "tested_sha", "base_sha",
        "policy_sha256", "policy_modified_from_base", "lane",
        "started_at", "finished_at", "environment", "verdict", "checks"
    ]
    for k in required_keys:
        if k not in data:
            errors.append(f"Schema validation failed: missing required property '{k}'")

    if errors:
        return errors

    if not isinstance(data.get("checks"), list):
        errors.append("Schema validation failed: 'checks' must be an array")

    return errors

def verify_evidence_report(
    report_path: Path,
    expected_sha: Optional[str] = None,
    expected_base_sha: Optional[str] = None,
    expected_policy_sha256: Optional[str] = None,
    required_check_ids: Optional[list[str]] = None,
    require_candidate: bool = False,
    bootstrap_approval_path: Optional[Path] = None
) -> tuple[bool, list[str]]:
    """
    Independent Evidence Auditor.
    Validates structural schema, cryptographic log hashes, SHA matching,
    duplicate check detection, status/exit-code consistency, and recomputed verdict.
    """
    errors: list[str] = []

    if not report_path.is_file():
        return False, [f"Report file not found: {report_path}"]

    try:
        raw_text = report_path.read_text(encoding="utf-8")
        data = json.loads(raw_text)
    except Exception as e:
        return False, [f"Failed to parse report JSON: {e}"]

    # 1. Structural schema validation
    schema_errs = validate_report_schema_dict(data)
    if schema_errs:
        return False, schema_errs

    # 2. Candidate lane requirement
    lane = data.get("lane")
    if require_candidate and lane != "candidate":
        errors.append(f"Fast lane report cannot be used for candidate gate evaluation (found lane: '{lane}')")

    # 3. Tested SHA equality
    tested_sha = data.get("tested_sha")
    if expected_sha and tested_sha != expected_sha:
        errors.append(f"Report tested_sha mismatch: expected {expected_sha}, found {tested_sha}")

    # 4. Base SHA equality
    base_sha = data.get("base_sha")
    if expected_base_sha and base_sha != expected_base_sha:
        errors.append(f"Report base_sha mismatch: expected {expected_base_sha}, found {base_sha}")

    # 5. Policy SHA-256 digest equality
    policy_sha = data.get("policy_sha256")
    if expected_policy_sha256 and policy_sha != expected_policy_sha256:
        errors.append(f"Policy hash mismatch against expected context: expected {expected_policy_sha256}, found {policy_sha}")

    # 6. Checks evaluation
    checks = data.get("checks", [])
    seen_ids: set[str] = set()
    parent_dir = report_path.parent

    for c in checks:
        cid = c.get("id")
        if cid in seen_ids:
            errors.append(f"Duplicate check ID in report: {cid}")
        seen_ids.add(cid)

        # Check status and code consistency
        status = c.get("status")
        exit_code = c.get("exit_code")
        if status == "PASS" and exit_code != 0:
            errors.append(f"Inconsistent check status and exit code in check '{cid}': status={status}, exit_code={exit_code}")
        elif status in ("FAIL", "TIMEOUT", "ERROR") and exit_code == 0:
            errors.append(f"Inconsistent check status and exit code in check '{cid}': status={status}, exit_code={exit_code}")

        # Log file verification
        log_name = c.get("log_file")
        if not log_name:
            errors.append(f"Missing log_file in check result: {cid}")
        else:
            log_path = parent_dir / log_name
            if not log_path.is_file():
                errors.append(f"Referenced log file missing: {log_name}")
            else:
                expected_log_hash = c.get("log_sha256")
                actual_log_hash = compute_sha256_bytes(log_path.read_bytes())
                if expected_log_hash != actual_log_hash:
                    errors.append(f"Log integrity violation in {log_name}: expected {expected_log_hash}, found {actual_log_hash}")

    # 7. Required checks presence
    if required_check_ids:
        for req_id in required_check_ids:
            if req_id not in seen_ids:
                errors.append(f"Missing result for required check: {req_id}")

    # 8. Recomputed verdict vs recorded verdict
    declared_verdict = data.get("verdict")
    recomputed = "PASS"
    req_set = set(required_check_ids) if required_check_ids else seen_ids

    for c in checks:
        cid = c.get("id")
        if cid in req_set:
            c_status = c.get("status")
            c_code = c.get("exit_code")
            if c_status == "ERROR":
                recomputed = "ERROR"
                break
            elif c_status in ("FAIL", "TIMEOUT") or c_code != 0:
                recomputed = "FAIL"

    if declared_verdict == "PASS" and recomputed != "PASS":
        errors.append(f"Forged PASS verdict rejected: declared '{declared_verdict}', recomputed '{recomputed}'")

    return len(errors) == 0, errors

def main():
    parser = argparse.ArgumentParser(description="Evidence Report Auditor & Verifier")
    parser.add_argument("--report", type=str, default=None, help="Path to report.json")
    parser.add_argument("--audit-latest", action="store_true", help="Audit latest report in artifacts/verification")
    parser.add_argument("--require-candidate", action="store_true", help="Require report to be from candidate lane")
    parser.add_argument("--expected-sha", type=str, default=None, help="Expected candidate HEAD SHA")
    parser.add_argument("--expected-base-sha", type=str, default=None, help="Expected target base SHA")
    parser.add_argument("--expected-policy-sha256", type=str, default=None, help="Expected policy SHA-256")
    parser.add_argument("--expected-required-checks", nargs="*", default=None, help="Expected required check IDs")
    args = parser.parse_args()

    report_path: Optional[Path] = None
    if args.report:
        report_path = Path(args.report)
    elif args.audit_latest:
        art_dir = Path("artifacts/verification")
        if art_dir.is_dir():
            subdirs = sorted([d for d in art_dir.iterdir() if d.is_dir() and (d / "report.json").is_file()], key=lambda p: p.stat().st_mtime, reverse=True)
            if subdirs:
                report_path = subdirs[0] / "report.json"

    if not report_path or not report_path.is_file():
        print("Error: No valid report file specified or found.", file=sys.stderr)
        sys.exit(2)

    ok, errors = verify_evidence_report(
        report_path,
        expected_sha=args.expected_sha,
        expected_base_sha=args.expected_base_sha,
        expected_policy_sha256=args.expected_policy_sha256,
        required_check_ids=args.expected_required_checks,
        require_candidate=args.require_candidate
    )

    if not ok:
        print(f"Evidence Audit FAILED for {report_path}:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        sys.exit(1)

    print(f"Evidence Audit PASSED for {report_path}")
    sys.exit(0)

if __name__ == "__main__":
    main()
