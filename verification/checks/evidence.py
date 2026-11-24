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
import re
import sys
import uuid
from typing import Optional

# Ensure root repository is importable
repo_root = Path(__file__).resolve().parent.parent.parent
if str(repo_root) not in sys.path:
    sys.path.insert(0, str(repo_root))

from verification.checks.runner import CheckResult

REPORT_SCHEMA_VERSION = 1
HEX_SHA40_RE = re.compile(r'^[0-9a-fA-F]{40}$')
HEX_SHA256_RE = re.compile(r'^[0-9a-fA-F]{64}$')

def compute_sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def verify_log_hash(content: bytes, expected_sha: str) -> bool:
    return hashlib.sha256(content).hexdigest().lower() == expected_sha.lower()

def validate_test_summary_deep(ts: dict, check_status: str, exit_code: int) -> list[str]:
    """
    Validates mathematical consistency and metric types of test suite summaries.
    Rejects bool values, negative counts, string numbers, and zero total.
    """
    errs = []
    required_keys = ["total", "passed", "failures", "errors", "skipped"]
    for k in required_keys:
        if k not in ts:
            errs.append(f"missing required key '{k}'")
    if errs:
        return errs

    for k, val in ts.items():
        if isinstance(val, bool) or not isinstance(val, int):
            errs.append(f"metric '{k}' must be an integer, got {type(val).__name__} ({val})")
        elif val < 0:
            errs.append(f"metric '{k}' cannot be negative ({val})")

    if errs:
        return errs

    total = ts["total"]
    passed = ts["passed"]
    failures = ts["failures"]
    errors = ts["errors"]
    skipped = ts["skipped"]
    expected_failures = ts.get("expected_failures", 0)
    unexpected_successes = ts.get("unexpected_successes", 0)

    if total == 0:
        errs.append("total tests executed is zero")

    computed_total = passed + failures + errors + skipped + expected_failures + unexpected_successes
    if total != computed_total:
        errs.append(f"inconsistent execution counts: total={total} != sum of parts={computed_total}")

    if check_status == "PASS":
        if failures > 0 or errors > 0 or unexpected_successes > 0 or skipped > 0 or expected_failures > 0:
            errs.append(f"PASS check has failing/skipped/unexpected metrics: failures={failures}, errors={errors}, skipped={skipped}")

    return errs

def load_approved_checks_from_policy(policy_path: Path, expected_policy_sha256: Optional[str] = None) -> tuple[Optional[dict[str, dict]], list[str]]:
    """
    Loads approved check definitions from policy JSON file and verifies policy SHA-256 digest.
    """
    errs = []
    if not policy_path.is_file():
        return None, [f"Policy file not found: {policy_path}"]

    data_bytes = policy_path.read_bytes()
    actual_hash = compute_sha256_bytes(data_bytes)
    if expected_policy_sha256 and actual_hash.lower() != expected_policy_sha256.lower():
        errs.append(f"Policy file hash mismatch: expected {expected_policy_sha256}, found {actual_hash}")
        return None, errs

    try:
        pdata = json.loads(data_bytes.decode("utf-8"))
    except Exception as e:
        return None, [f"Failed to parse policy JSON: {e}"]

    raw_checks = pdata.get("checks", [])
    approved = {}
    for c in raw_checks:
        cid = c.get("id")
        if cid:
            approved[cid] = {
                "type": c.get("type", "command"),
                "required": c.get("required", False)
            }
    return approved, []

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
            elif r.status in ("FAIL", "TIMEOUT") or r.exit_code != 0 or r.cleanup_failed:
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

    checks = data.get("checks")
    if not isinstance(checks, list):
        errors.append("Schema validation failed: 'checks' must be an array")
        return errors

    for idx, c in enumerate(checks):
        if not isinstance(c, dict):
            errors.append(f"Schema validation failed: checks[{idx}] must be an object")
            continue
        for req in ["id", "status", "exit_code", "duration_seconds", "log_file", "log_sha256"]:
            if req not in c:
                errors.append(f"Schema validation failed: missing required property '{req}' in checks[{idx}]")
        if "status" in c and c["status"] not in ("PASS", "FAIL", "ERROR", "TIMEOUT"):
            errors.append(f"Schema validation failed: Unknown or unsupported status '{c['status']}' in checks[{idx}]")
        if "exit_code" in c and (not isinstance(c["exit_code"], int) or isinstance(c["exit_code"], bool)):
            errors.append(f"Schema validation failed: exit_code must be an integer in checks[{idx}]")
        if "log_sha256" in c and (not isinstance(c["log_sha256"], str) or not HEX_SHA256_RE.match(c["log_sha256"])):
            errors.append(f"Schema validation failed: invalid log_sha256 pattern in checks[{idx}]")

    return errors

def resolve_transition_context(
    expected_sha: Optional[str],
    expected_base_sha: Optional[str],
    expected_policy_sha256: Optional[str],
    required_check_ids: Optional[list[str]],
    policy_path: Optional[Path],
    transition_auth: Optional[dict]
) -> tuple[Optional[str], Optional[list[str]], Optional[Path], set[str], list[str]]:
    """
    Evaluates the controlled policy transition state machine.
    Returns: (effective_policy_sha256, effective_required_checks, effective_policy_path, retired_policy_hashes, errors)
    """
    errors: list[str] = []
    if not transition_auth:
        return expected_policy_sha256, required_check_ids, policy_path, set(), errors

    if not isinstance(transition_auth, dict):
        return expected_policy_sha256, required_check_ids, policy_path, set(), ["Invalid transition_auth: must be an object"]

    req_keys = ["candidate_sha", "base_sha", "canonical_policy_sha256", "required_checks"]
    for k in req_keys:
        if k not in transition_auth:
            errors.append(f"Missing required transition_auth property: '{k}'")
    if errors:
        return expected_policy_sha256, required_check_ids, policy_path, set(), errors

    auth_cand = transition_auth.get("candidate_sha")
    auth_base = transition_auth.get("base_sha")
    auth_policy_sha = transition_auth.get("canonical_policy_sha256")
    auth_reqs = transition_auth.get("required_checks")
    auth_policy_path = transition_auth.get("policy_path")
    retired = set(transition_auth.get("retired_policies", []))

    if not isinstance(auth_cand, str) or not HEX_SHA40_RE.match(auth_cand):
        errors.append(f"Invalid candidate_sha format in transition_auth: '{auth_cand}'")
    if not isinstance(auth_base, str) or not HEX_SHA40_RE.match(auth_base):
        errors.append(f"Invalid base_sha format in transition_auth: '{auth_base}'")
    if not isinstance(auth_policy_sha, str) or not HEX_SHA256_RE.match(auth_policy_sha):
        errors.append(f"Invalid canonical_policy_sha256 format in transition_auth: '{auth_policy_sha}'")
    if not isinstance(auth_reqs, list) or len(auth_reqs) == 0:
        errors.append("required_checks in transition_auth must be a non-empty list of check IDs")
    else:
        # Mandatory checks preservation: transition cannot drop existing mandatory baseline checks
        if required_check_ids:
            missing_mandatory = set(required_check_ids) - set(auth_reqs)
            if missing_mandatory:
                errors.append(f"Transition policy removes mandatory baseline checks: {sorted(missing_mandatory)}")

    if errors:
        return expected_policy_sha256, required_check_ids, policy_path, set(), errors

    # Strict candidate and base SHA matching against authorized transition tuple
    if expected_sha != auth_cand:
        errors.append(
            f"Candidate SHA does not match authorized transition candidate: "
            f"expected '{auth_cand}', got '{expected_sha}'"
        )
    if expected_base_sha != auth_base:
        errors.append(
            f"Base SHA does not match authorized transition target base: "
            f"expected '{auth_base}', got '{expected_base_sha}'"
        )

    if errors:
        return expected_policy_sha256, required_check_ids, policy_path, set(), errors

    target_path = Path(auth_policy_path) if auth_policy_path else policy_path
    return auth_policy_sha, auth_reqs, target_path, retired, []

def verify_evidence_report(
    report_path: Path,
    expected_sha: Optional[str] = None,
    expected_base_sha: Optional[str] = None,
    expected_policy_sha256: Optional[str] = None,
    required_check_ids: Optional[list[str]] = None,
    require_candidate: bool = False,
    expected_run_id: Optional[str] = None,
    approved_checks: Optional[dict[str, dict]] = None,
    policy_path: Optional[Path] = None,
    transition_auth: Optional[dict] = None
) -> tuple[bool, list[str]]:
    """
    Independent Evidence Auditor.
    Validates structural schema, cryptographic log hashes, SHA matching,
    duplicate check detection, status/exit-code consistency, test metrics,
    and recomputed verdict.
    """
    errors: list[str] = []

    # Controlled Policy Transition evaluation
    effective_policy_sha256 = expected_policy_sha256
    effective_required_ids = required_check_ids
    effective_policy_path = policy_path
    retired_policy_hashes: set[str] = set()

    if transition_auth:
        t_sha, t_reqs, t_path, t_retired, t_errs = resolve_transition_context(
            expected_sha=expected_sha,
            expected_base_sha=expected_base_sha,
            expected_policy_sha256=expected_policy_sha256,
            required_check_ids=required_check_ids,
            policy_path=policy_path,
            transition_auth=transition_auth
        )
        if t_errs:
            errors.extend(t_errs)
        else:
            effective_policy_sha256 = t_sha
            effective_required_ids = t_reqs
            effective_policy_path = t_path
            retired_policy_hashes = t_retired

    # 1. Authoritative candidate validation requirements
    if require_candidate:
        if not expected_sha:
            errors.append("Missing required context: expected_sha")
        elif not HEX_SHA40_RE.match(expected_sha):
            errors.append(f"Invalid SHA format for expected_sha: '{expected_sha}'")

        if not expected_base_sha:
            errors.append("Missing required context: expected_base_sha")
        elif not HEX_SHA40_RE.match(expected_base_sha):
            errors.append(f"Invalid SHA format for expected_base_sha: '{expected_base_sha}'")

        if not effective_policy_sha256:
            errors.append("Missing required context: expected_policy_sha256")
        elif not HEX_SHA256_RE.match(effective_policy_sha256):
            errors.append(f"Invalid SHA-256 digest format for expected_policy_sha256: '{effective_policy_sha256}'")

        if not effective_required_ids or len(effective_required_ids) == 0:
            errors.append("Missing required context: required_check_ids (must be a non-empty list of check IDs)")

        if effective_policy_path is None and approved_checks is None:
            errors.append("Missing required approved policy definitions in candidate mode")

    # 2. Policy resolution if provided
    loaded_approved = None
    p_checks = None
    if effective_policy_path:
        p_checks, p_errs = load_approved_checks_from_policy(effective_policy_path, effective_policy_sha256)
        if p_errs:
            errors.extend(p_errs)
        else:
            loaded_approved = p_checks

    if approved_checks is not None:
        if p_checks is not None and approved_checks != p_checks:
            errors.append("Conflicting approved_checks and policy definitions")
        if loaded_approved is None:
            loaded_approved = dict(approved_checks)

    # In candidate mode, verify expected required checks set matches approved policy required checks exactly
    if require_candidate and loaded_approved and effective_required_ids:
        approved_required = {cid for cid, defn in loaded_approved.items() if defn.get("required")}
        expected_set = set(effective_required_ids)
        if expected_set != approved_required:
            errors.append(f"Expected required checks set does not match approved policy required checks (expected: {sorted(expected_set)}, approved: {sorted(approved_required)})")

    if not report_path.is_file():
        return False, [f"Report file not found: {report_path}"] + errors

    try:
        raw_text = report_path.read_text(encoding="utf-8")
        data = json.loads(raw_text)
    except Exception as e:
        return False, [f"Failed to parse report JSON: {e}"] + errors

    # 3. Structural schema validation
    schema_errs = validate_report_schema_dict(data)
    if schema_errs:
        return False, schema_errs + errors

    # 4. Candidate lane requirement
    lane = data.get("lane")
    if require_candidate and lane != "candidate":
        errors.append(f"Fast lane report cannot be used for candidate gate evaluation (found lane: '{lane}')")

    # 5. Tested SHA equality & format
    tested_sha = data.get("tested_sha")
    if expected_sha and tested_sha != expected_sha:
        errors.append(f"Report tested_sha mismatch: expected {expected_sha}, found {tested_sha}")

    # 6. Base SHA equality & format
    base_sha = data.get("base_sha")
    if expected_base_sha and base_sha != expected_base_sha:
        errors.append(f"Report base_sha mismatch: expected {expected_base_sha}, found {base_sha}")

    # 7. Policy SHA-256 digest equality & retirement check
    policy_sha = data.get("policy_sha256")
    if policy_sha and policy_sha.lower() in {h.lower() for h in retired_policy_hashes}:
        errors.append(f"Report references retired policy digest: {policy_sha}. Selecting retired or weaker policies is prohibited.")
    elif effective_policy_sha256 and policy_sha != effective_policy_sha256:
        errors.append(f"Policy hash mismatch against expected context: expected {effective_policy_sha256}, found {policy_sha}")

    # 8. Run ID equality if specified
    run_id = data.get("run_id")
    if expected_run_id and run_id != expected_run_id:
        errors.append(f"Report run_id mismatch: expected '{expected_run_id}', found '{run_id}'")

    # 9. Checks array presence
    checks = data.get("checks", [])
    if len(checks) == 0:
        errors.append("Report contains zero checks")

    # 10. Individual checks evaluation
    seen_ids: set[str] = set()
    parent_dir = report_path.parent
    allowed_statuses = {"PASS", "FAIL", "ERROR", "TIMEOUT"}

    for c in checks:
        cid = c.get("id")
        if cid in seen_ids:
            errors.append(f"Duplicate check ID in report: {cid}")
        seen_ids.add(cid)

        if loaded_approved and cid not in loaded_approved:
            errors.append(f"Unexpected unapproved check ID in report: {cid}")

        # Check status and code consistency
        status = c.get("status")
        if status not in allowed_statuses:
            errors.append(f"Unknown or unsupported status '{status}' in check '{cid}'")

        exit_code = c.get("exit_code")
        if status == "PASS" and exit_code != 0:
            errors.append(f"Inconsistent check status and exit code in check '{cid}': status={status}, exit_code={exit_code}")
        elif status in ("FAIL", "TIMEOUT", "ERROR") and exit_code == 0:
            errors.append(f"Inconsistent check status and exit code in check '{cid}': status={status}, exit_code={exit_code}")

        # Cleanup failed check
        if c.get("cleanup_failed"):
            if require_candidate:
                errors.append(f"Check '{cid}' marked cleanup_failed=true")

        # Log file verification with path containment and symlink checks
        log_name = c.get("log_file")
        if not log_name or not isinstance(log_name, str):
            errors.append(f"Missing log_file in check result: {cid}")
        else:
            norm_parts = log_name.replace("\\", "/").split("/")
            if ".." in norm_parts:
                errors.append(f"Log path traversal detected in check '{cid}': {log_name}")
            elif Path(log_name).is_absolute():
                errors.append(f"Absolute log path prohibited in check '{cid}': {log_name}")
            else:
                raw_log_path = parent_dir / log_name
                if raw_log_path.is_symlink():
                    errors.append(f"Unsafe symlink log reference detected in check '{cid}': {log_name}")
                else:
                    try:
                        resolved_log = raw_log_path.resolve()
                        resolved_parent = parent_dir.resolve()
                        resolved_log.relative_to(resolved_parent)
                    except ValueError:
                        errors.append(f"Log path traversal detected in check '{cid}': {log_name}")
                        resolved_log = None

                    if resolved_log and not raw_log_path.is_file():
                        errors.append(f"Referenced log file missing: {log_name}")
                    elif resolved_log:
                        expected_log_hash = c.get("log_sha256")
                        actual_log_hash = compute_sha256_bytes(raw_log_path.read_bytes())
                        if expected_log_hash != actual_log_hash:
                            errors.append(f"Log integrity violation in {log_name}: expected {expected_log_hash}, found {actual_log_hash}")

        # Check type distinction (strictly resolved from approved checks; never infer from untrusted report fields)
        check_type = "command"
        if loaded_approved and cid in loaded_approved:
            check_type = loaded_approved[cid].get("type", "command")

        if check_type == "test_suite":
            ts = c.get("test_summary")
            if not ts or not isinstance(ts, dict):
                errors.append(f"Missing required test_summary for test_suite check '{cid}'")
            else:
                ts_errs = validate_test_summary_deep(ts, status, exit_code)
                for te in ts_errs:
                    errors.append(f"Test summary integrity violation in check '{cid}': {te}")
        elif "test_summary" in c and c["test_summary"] is not None:
            ts_errs = validate_test_summary_deep(c["test_summary"], status, exit_code)
            for te in ts_errs:
                errors.append(f"Test summary integrity violation in check '{cid}': {te}")

    # 11. Required checks presence
    if effective_required_ids:
        for req_id in effective_required_ids:
            if req_id not in seen_ids:
                errors.append(f"Missing result for required check: {req_id}")

    # In candidate lane, every required check must have passed
    if require_candidate:
        for c in checks:
            cid = c.get("id")
            is_req = (cid in effective_required_ids) if effective_required_ids else True
            if is_req:
                c_status = c.get("status")
                if c_status != "PASS":
                    errors.append(f"Required check '{cid}' did not pass: status={c_status}")

    # 12. Recomputed verdict vs recorded verdict
    declared_verdict = data.get("verdict")
    recomputed = "PASS"
    req_set = set(effective_required_ids) if effective_required_ids else seen_ids

    for c in checks:
        cid = c.get("id")
        if cid in req_set:
            c_status = c.get("status")
            c_code = c.get("exit_code")
            if c_status == "ERROR":
                recomputed = "ERROR"
                break
            elif c_status in ("FAIL", "TIMEOUT") or c_code != 0 or c.get("cleanup_failed"):
                recomputed = "FAIL"

    if declared_verdict == "PASS" and recomputed != "PASS":
        errors.append(f"Forged PASS verdict rejected: declared '{declared_verdict}', recomputed '{recomputed}'")

    if require_candidate and declared_verdict != "PASS":
        errors.append(f"Candidate acceptance rejected: verdict is {declared_verdict}")

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
    parser.add_argument("--expected-run-id", type=str, default=None, help="Expected run ID")
    parser.add_argument("--policy", type=str, default=None, help="Path to approved checks policy JSON")
    parser.add_argument("--transition-auth", type=str, default=None, help="JSON string or file path for authorized policy transition grant")
    parser.add_argument("--transition-auth-file", type=str, default=None, help="Path to JSON file containing authorized policy transition grant")
    args = parser.parse_args()

    # Reject unanchored --audit-latest in candidate mode without explicit expected run id
    if args.require_candidate and args.audit_latest and not args.expected_run_id:
        print("Error: --audit-latest cannot be used as an authoritative run selector without explicit --expected-run-id binding.", file=sys.stderr)
        sys.exit(2)

    if args.require_candidate and not args.policy:
        print("Error: --policy is required in candidate mode.", file=sys.stderr)
        sys.exit(2)

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

    # Parse transition authorization if supplied
    transition_auth_data = None
    raw_auth = args.transition_auth
    auth_file = args.transition_auth_file
    if auth_file:
        p = Path(auth_file)
        if not p.is_file():
            print(f"Error: Transition auth file not found: {auth_file}", file=sys.stderr)
            sys.exit(2)
        try:
            transition_auth_data = json.loads(p.read_text(encoding="utf-8"))
        except Exception as e:
            print(f"Error: Failed to parse transition auth JSON from file {auth_file}: {e}", file=sys.stderr)
            sys.exit(2)
    elif raw_auth:
        p = Path(raw_auth)
        if p.is_file():
            try:
                transition_auth_data = json.loads(p.read_text(encoding="utf-8"))
            except Exception as e:
                print(f"Error: Failed to parse transition auth JSON from file {raw_auth}: {e}", file=sys.stderr)
                sys.exit(2)
        else:
            try:
                transition_auth_data = json.loads(raw_auth)
            except Exception as e:
                print(f"Error: Failed to parse transition auth JSON string: {e}", file=sys.stderr)
                sys.exit(2)
    elif os.environ.get("TRANSITION_AUTHORIZATION"):
        env_auth = os.environ.get("TRANSITION_AUTHORIZATION", "").strip()
        if env_auth:
            try:
                transition_auth_data = json.loads(env_auth)
            except Exception as e:
                print(f"Error: Failed to parse TRANSITION_AUTHORIZATION environment JSON: {e}", file=sys.stderr)
                sys.exit(2)

    policy_p = Path(args.policy) if args.policy else None

    ok, errors = verify_evidence_report(
        report_path,
        expected_sha=args.expected_sha,
        expected_base_sha=args.expected_base_sha,
        expected_policy_sha256=args.expected_policy_sha256,
        required_check_ids=args.expected_required_checks,
        require_candidate=args.require_candidate,
        expected_run_id=args.expected_run_id,
        policy_path=policy_p,
        transition_auth=transition_auth_data
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
