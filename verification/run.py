#!/usr/bin/env python3
"""
Verification Runner & Lane Orchestrator.
Standard library only. Dispatches fast local lane or candidate verification lane.
"""
import argparse
import datetime
import os
from pathlib import Path
import sys
import uuid

# Ensure verification package is importable
repo_root = Path(__file__).resolve().parent.parent
if str(repo_root) not in sys.path:
    sys.path.insert(0, str(repo_root))

from verification.checks.registry import (
    load_and_validate_registry,
    validate_check_selection,
    CheckRegistry,
    CheckDefinition
)
from verification.checks.runner import execute_check, CheckResult
from verification.checks.git_auditor import (
    is_clean_checkout,
    get_head_sha,
    get_base_sha,
    check_policy_modified_from_base
)
from verification.checks.evidence import generate_evidence_report, compute_sha256_bytes

CANONICAL_POLICY_PATH = "policies/checks.json"

def main():
    parser = argparse.ArgumentParser(
        description="WeBack Verification Runner & Lane Dispatcher",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter
    )
    parser.add_argument(
        "--lane",
        choices=["candidate", "fast"],
        default="candidate",
        help="Operational lane: candidate (clean-tree strict gate) or fast (local diagnostic)"
    )
    parser.add_argument(
        "--check",
        type=str,
        default=None,
        help="Target a specific check ID (strictly prohibited in candidate lane)"
    )
    parser.add_argument(
        "--config",
        type=str,
        default=CANONICAL_POLICY_PATH,
        help="Path to check registry JSON file"
    )
    parser.add_argument(
        "--output-dir",
        type=str,
        default="artifacts/verification",
        help="Root directory for verification artifacts"
    )
    parser.add_argument(
        "--base-ref",
        type=str,
        default="main",
        help="Git base reference for candidate diff evaluation"
    )
    parser.add_argument(
        "--run-id",
        type=str,
        default=None,
        help="Run identifier (safe alphanumeric, '-', '_', '.' only)"
    )
    parser.add_argument(
        "--repo-dir",
        type=str,
        default=".",
        help="Repository working directory"
    )

    args = parser.parse_args()
    repo_path = Path(args.repo_dir).resolve()

    if "ANDROID_HOME" not in os.environ:
        default_sdk = Path.home() / ".android" / "sdk"
        if default_sdk.is_dir():
            os.environ["ANDROID_HOME"] = str(default_sdk)

    # AC-003.5: Targeted check execution prohibited in candidate lane
    if args.lane == "candidate" and args.check:
        print("Error: Partial check execution is prohibited in candidate lane.", file=sys.stderr)
        sys.exit(2)

    # Candidate lane output directory integrity
    if args.lane == "candidate":
        out_p = Path(args.output_dir)
        if ".." in out_p.parts:
            print("Error: Path traversal in --output-dir is prohibited in candidate lane.", file=sys.stderr)
            sys.exit(2)

        resolved_out = (repo_path / out_p).resolve() if not out_p.is_absolute() else out_p.resolve()
        canonical_evidence_dir = (repo_path / "artifacts/verification").resolve()

        try:
            resolved_out.relative_to(repo_path)
            # If inside repo, it MUST be strictly under (or equal to) artifacts/verification
            try:
                resolved_out.relative_to(canonical_evidence_dir)
            except ValueError:
                print(f"Error: Candidate lane output directory must be within '{canonical_evidence_dir}' or external, got '{resolved_out}'", file=sys.stderr)
                sys.exit(2)
        except ValueError:
            # External directory outside repo is allowed as safe external location
            pass

    # AC-003.4: Candidate lane must execute against canonical approved policy
    if args.lane == "candidate":
        canonical_p = repo_path / CANONICAL_POLICY_PATH
        config_p = Path(args.config)
        if not config_p.is_absolute():
            config_p = repo_path / config_p

        try:
            is_same = (config_p.resolve() == canonical_p.resolve())
        except Exception:
            is_same = False

        if not is_same:
            if not canonical_p.is_file() or not config_p.is_file() or config_p.read_bytes() != canonical_p.read_bytes():
                print("Error: Alternate configuration not approved for candidate lane.", file=sys.stderr)
                sys.exit(2)

    # AC-006.1: Clean checkout verification in candidate lane
    if args.lane == "candidate":
        clean, details = is_clean_checkout(repo_path)
        if not clean:
            print(f"Error: Working tree is not clean before execution:\n{details}", file=sys.stderr)
            sys.exit(2)

    # Load and validate registry
    config_file = Path(args.config)
    if not config_file.is_absolute():
        config_file = repo_path / config_file

    try:
        registry = load_and_validate_registry(str(config_file))
    except ValueError as e:
        print(f"Error: Registry validation failed: {e}", file=sys.stderr)
        sys.exit(2)

    # Determine checks to run
    checks_to_run: list[CheckDefinition] = []
    if args.check:
        try:
            target_check = validate_check_selection(registry, args.check)
            checks_to_run = [target_check]
        except KeyError as e:
            print(f"Error: Unknown check ID: {args.check}", file=sys.stderr)
            sys.exit(2)
    else:
        checks_to_run = registry.checks

    # Git metadata
    try:
        tested_sha = get_head_sha(repo_path)
    except Exception as e:
        print(f"Error: Could not resolve HEAD SHA: {e}", file=sys.stderr)
        sys.exit(2)

    try:
        base_sha = get_base_sha(repo_path, args.base_ref)
    except Exception as e:
        print(f"Error: Could not resolve base SHA for '{args.base_ref}': {e}", file=sys.stderr)
        sys.exit(2)

    pre_tested_sha = tested_sha
    pre_policy_sha256 = compute_sha256_bytes(config_file.read_bytes())
    policy_modified = check_policy_modified_from_base(repo_path, args.base_ref)

    # Set working directory context for all checks
    for check in checks_to_run:
        if check.cwd is None:
            check.cwd = repo_path

    # Execute checks
    if args.run_id:
        import re
        if not re.match(r"^[a-zA-Z0-9_\-\.]+$", args.run_id) or ".." in args.run_id:
            print(f"Error: Invalid run-id format: {args.run_id}", file=sys.stderr)
            sys.exit(2)
        run_id = args.run_id
    else:
        run_id = str(uuid.uuid4())
    started_at = datetime.datetime.now(datetime.timezone.utc).isoformat()
    out_dir = Path(args.output_dir)
    if not out_dir.is_absolute():
        out_dir = repo_path / out_dir
    run_artifacts_dir = out_dir / run_id

    print(f"Starting verification run {run_id} (lane: {args.lane}, checks: {len(checks_to_run)})...")
    results: list[CheckResult] = []

    for check in checks_to_run:
        print(f"  -> Executing [{check.id}]... ", end="", flush=True)
        res = execute_check(check)
        print(f"{res.status} ({res.duration_seconds:.2f}s, code: {res.exit_code})")
        results.append(res)

    finished_at = datetime.datetime.now(datetime.timezone.utc).isoformat()

    # Post-run HEAD and policy immutability checks
    try:
        post_tested_sha = get_head_sha(repo_path)
    except Exception as e:
        print(f"Error: Post-run HEAD SHA could not be resolved: {e}", file=sys.stderr)
        sys.exit(2)

    post_policy_sha256 = compute_sha256_bytes(config_file.read_bytes())

    if pre_tested_sha != post_tested_sha:
        print(f"Error: Repository HEAD mutated during verification run (pre: {pre_tested_sha}, post: {post_tested_sha})", file=sys.stderr)
        sys.exit(2)

    if pre_policy_sha256 != post_policy_sha256:
        print(f"Error: Policy file mutated during verification run (pre: {pre_policy_sha256}, post: {post_policy_sha256})", file=sys.stderr)
        sys.exit(2)

    # AC-006.2: Post-run cleanliness check in candidate lane
    if args.lane == "candidate":
        # Cleanliness exclusion must NEVER be derived from arbitrary user input
        clean, details = is_clean_checkout(repo_path, ignored_prefixes=["artifacts/verification/"])
        if not clean:
            print(f"Error: Repository mutated during execution:\n{details}", file=sys.stderr)
            sys.exit(2)

    # Generate evidence report
    required_ids = {c.id for c in registry.checks if c.required}
    report_file = generate_evidence_report(
        results=results,
        tested_sha=tested_sha,
        base_sha=base_sha,
        policy_sha256=registry.policy_sha256,
        policy_modified_from_base=policy_modified,
        lane=args.lane,
        output_dir=run_artifacts_dir,
        started_at=started_at,
        finished_at=finished_at,
        run_id=run_id,
        required_ids=required_ids
    )

    print(f"\nEvidence written to {report_file}")

    # Determine exit code
    has_error = any(r.status == "ERROR" for r in results if r.id in required_ids)
    has_fail = any(r.status in ("FAIL", "TIMEOUT") or r.exit_code != 0 for r in results if r.id in required_ids)

    if has_error:
        print("Verdict: ERROR", file=sys.stderr)
        sys.exit(2)
    elif has_fail:
        print("Verdict: FAIL", file=sys.stderr)
        sys.exit(1)
    else:
        print("Verdict: PASS")
        sys.exit(0)

if __name__ == "__main__":
    main()
