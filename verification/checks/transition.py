#!/usr/bin/env python3
"""
Policy Transition State Machine, Provenance Auditor, and Execution Context Resolver.
Standard library only. Enforces fail-closed policy transition governance between
approved milestone baselines, PR candidates, and post-squash-merge push revisions.
"""
from dataclasses import dataclass, asdict
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Optional

HEX_SHA40_RE = re.compile(r'^[0-9a-fA-F]{40}$')
HEX_SHA256_RE = re.compile(r'^[0-9a-fA-F]{64}$')

CANONICAL_POLICY_PATH = "policies/checks.json"
BASELINE_POLICY_PATH = "policies/checks.baseline.json"
HISTORICAL_BASELINE_SHA256 = "4a004ac8d07178c4164193d827983dfa5a7d86c5245fbcb6df69497e976137fd"

class TransitionError(Exception):
    def __init__(self, errors: list[str]):
        super().__init__("; ".join(errors))
        self.errors = errors

@dataclass
class ResolvedContext:
    run_id: str
    report_path: str
    candidate_sha: str
    base_sha: str
    base_ref: str
    policy_file: str
    expected_policy_sha256: str
    expected_required_checks: list[str]
    is_transition: bool
    tree_sha: str = ""
    parent_shas: tuple[str, ...] = ()

def compute_sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def get_tree_sha(repo_path: Path, ref: str = "HEAD") -> str:
    try:
        res = subprocess.run(
            ["git", "rev-parse", f"{ref}^{{tree}}"],
            cwd=str(repo_path),
            capture_output=True,
            text=True,
            check=True
        )
        return res.stdout.strip()
    except Exception as e:
        raise ValueError(f"Could not resolve git tree SHA for '{ref}': {e}")

def get_commit_parents(repo_path: Path, ref: str = "HEAD") -> list[str]:
    try:
        res = subprocess.run(
            ["git", "rev-parse", f"{ref}^@"],
            cwd=str(repo_path),
            capture_output=True,
            text=True,
            check=True
        )
        return [line.strip() for line in res.stdout.splitlines() if line.strip()]
    except Exception:
        return []

def load_policy_checks_and_digest(policy_file: Path) -> tuple[dict[str, dict], str, list[str]]:
    if not policy_file.is_file():
        return {}, "", [f"Policy file not found: {policy_file}"]
    data = policy_file.read_bytes()
    digest = compute_sha256_bytes(data)
    try:
        pdata = json.loads(data.decode("utf-8"))
    except Exception as e:
        return {}, digest, [f"Failed to parse policy JSON '{policy_file}': {e}"]
    checks = {}
    for c in pdata.get("checks", []):
        cid = c.get("id")
        if cid:
            checks[cid] = {
                "type": c.get("type", "command"),
                "required": c.get("required", False)
            }
    return checks, digest, []

def validate_transition_authorization(
    auth_dict: dict,
    git_context: dict,
    repo_path: Path
) -> tuple[bool, list[str]]:
    """
    Validates a structured transition authorization object against git context and policy bytes.
    Enforces candidate SHA, base SHA, candidate tree SHA, canonical policy digest,
    required checks, policy file, and policy retirement.
    """
    errors: list[str] = []
    if not isinstance(auth_dict, dict):
        return False, ["Invalid transition_authorization: must be a JSON object"]

    if "target_base_sha" not in auth_dict and "base_sha" in auth_dict:
        auth_dict = dict(auth_dict)
        auth_dict["target_base_sha"] = auth_dict["base_sha"]

    req_keys = ["candidate_sha", "target_base_sha", "candidate_tree_sha", "canonical_policy_sha256", "required_checks"]
    for k in req_keys:
        if k not in auth_dict:
            errors.append(f"Missing required transition_authorization property: '{k}'")
    if errors:
        return False, errors

    auth_cand = auth_dict["candidate_sha"]
    auth_base = auth_dict["target_base_sha"]
    auth_tree = auth_dict["candidate_tree_sha"]
    auth_policy_sha = auth_dict["canonical_policy_sha256"]
    auth_reqs = auth_dict["required_checks"]
    policy_rel_path = auth_dict.get("policy_file", CANONICAL_POLICY_PATH)
    retired_policies = {p.lower() for p in auth_dict.get("retired_policies", [])}

    if not isinstance(auth_cand, str) or not HEX_SHA40_RE.match(auth_cand):
        errors.append(f"Invalid candidate_sha format in transition authorization: '{auth_cand}'")
    if not isinstance(auth_base, str) or not HEX_SHA40_RE.match(auth_base):
        errors.append(f"Invalid target_base_sha format in transition authorization: '{auth_base}'")
    if not isinstance(auth_tree, str) or not HEX_SHA40_RE.match(auth_tree):
        errors.append(f"Invalid candidate_tree_sha format in transition authorization: '{auth_tree}'")
    if not isinstance(auth_policy_sha, str) or not HEX_SHA256_RE.match(auth_policy_sha):
        errors.append(f"Invalid canonical_policy_sha256 format in transition authorization: '{auth_policy_sha}'")
    if not isinstance(auth_reqs, list) or len(auth_reqs) == 0:
        errors.append("required_checks in transition authorization must be a non-empty list of check IDs")

    if errors:
        return False, errors

    event_name = git_context.get("event_name")
    curr_cand = git_context.get("candidate_sha", "")
    curr_base = git_context.get("base_sha", "")
    curr_tree = git_context.get("tree_sha", "")

    # 1. Candidate Provenance Verification
    if not event_name:
        errors.append("Missing explicit event context ('pull_request' or 'push') for transition authorization")
    elif event_name == "pull_request":
        if curr_cand != auth_cand:
            errors.append(f"Candidate SHA mismatch against transition authorization: expected '{auth_cand}', got '{curr_cand}'")
        if curr_base != auth_base:
            errors.append(f"Target base SHA mismatch against transition authorization: expected '{auth_base}', got '{curr_base}'")
        if not curr_tree or curr_tree != auth_tree:
            errors.append(f"Candidate tree SHA mismatch against transition authorization: expected '{auth_tree}', got '{curr_tree}'")
    elif event_name == "push":
        # Post-squash-merge push context: commit SHA differs from PR head SHA.
        # Tree identity, exact previous main SHA, and strict single-parent provenance MUST match!
        if not curr_tree or curr_tree != auth_tree:
            errors.append(f"Post-merge tree SHA mismatch against approved transition: expected '{auth_tree}', got '{curr_tree}'")
        if curr_base != auth_base:
            errors.append(f"Post-merge base provenance mismatch (previous main SHA): expected '{auth_base}', got '{curr_base}'")
        parent_shas = git_context.get("parent_shas")
        if not isinstance(parent_shas, list) or len(parent_shas) != 1 or parent_shas[0] != auth_base:
            errors.append(
                f"Post-merge base provenance mismatch (single-parent): expected sole parent ['{auth_base}'], got {parent_shas}"
            )
    else:
        errors.append(f"Unsupported event context: '{event_name}'")

    # 2. Policy File & Cryptographic Digest Verification
    policy_path = repo_path / policy_rel_path
    if not policy_path.is_file():
        errors.append(f"Authorized policy file not found: {policy_path}")
        return False, errors

    p_checks, actual_policy_sha, p_errs = load_policy_checks_and_digest(policy_path)
    if p_errs:
        errors.extend(p_errs)
        return False, errors

    if actual_policy_sha.lower() != auth_policy_sha.lower():
        errors.append(f"Canonical policy digest mismatch: expected '{auth_policy_sha}', actual file '{policy_rel_path}' is '{actual_policy_sha}'")

    if actual_policy_sha.lower() in retired_policies:
        errors.append(f"Attempt to activate retired policy digest '{actual_policy_sha}' is strictly prohibited")

    # 3. Required Checks Consistency & Mandatory Check Preservation
    policy_required = {cid for cid, defn in p_checks.items() if defn.get("required")}
    auth_required_set = set(auth_reqs)
    if auth_required_set != policy_required:
        errors.append(f"Transition required checks set does not match policy definitions: declared {sorted(auth_required_set)}, policy has {sorted(policy_required)}")

    # Ensure baseline mandatory checks (spec-hygiene, harness-selftests) are strictly preserved
    mandatory_baseline = {"spec-hygiene", "harness-selftests"}
    missing_mandatory = mandatory_baseline - auth_required_set
    if missing_mandatory:
        errors.append(f"Transition policy removes mandatory baseline checks: {sorted(missing_mandatory)}")

    return len(errors) == 0, errors

def resolve_verification_context(
    env: dict,
    git_context: dict,
    repo_path: Path
) -> ResolvedContext:
    """
    Deterministically resolves the authoritative verification parameters.
    Fails closed on missing authorization for modified policies or invalid transition parameters.
    """
    run_id = git_context.get("run_id") or f"run-{os.getpid()}"
    report_path = f"artifacts/verification/{run_id}/report.json"
    cand_sha = git_context.get("candidate_sha", "")
    base_sha = git_context.get("base_sha", "")
    base_ref = git_context.get("base_ref", base_sha)

    raw_auth = env.get("TRANSITION_AUTHORIZATION", "").strip()
    active_approved_policy_sha = env.get("APPROVED_POLICY_SHA256", "").strip()

    if raw_auth:
        try:
            auth_dict = json.loads(raw_auth)
        except Exception as e:
            raise TransitionError([f"Failed to parse TRANSITION_AUTHORIZATION environment JSON: {e}"])

        ok, auth_errs = validate_transition_authorization(auth_dict, git_context, repo_path)
        if not ok:
            raise TransitionError(auth_errs)

        policy_file = auth_dict.get("policy_file", CANONICAL_POLICY_PATH)
        return ResolvedContext(
            run_id=run_id,
            report_path=report_path,
            candidate_sha=cand_sha,
            base_sha=base_sha,
            base_ref=base_ref,
            policy_file=policy_file,
            expected_policy_sha256=auth_dict["canonical_policy_sha256"],
            expected_required_checks=auth_dict["required_checks"],
            is_transition=True,
            tree_sha=git_context.get("tree_sha", ""),
            parent_shas=tuple(git_context.get("parent_shas", []))
        )
    else:
        # No transition authorization provided.
        # Evaluate against active approved policy baseline.
        policy_path = repo_path / CANONICAL_POLICY_PATH
        if not policy_path.is_file():
            raise TransitionError([f"Canonical policy file missing at {policy_path}"])

        p_checks, actual_sha, p_errs = load_policy_checks_and_digest(policy_path)
        if p_errs:
            raise TransitionError(p_errs)

        baseline_file = repo_path / BASELINE_POLICY_PATH
        if active_approved_policy_sha:
            if actual_sha.lower() == active_approved_policy_sha.lower():
                # Policy file matches approved repository baseline directly
                req_checks = [cid for cid, defn in p_checks.items() if defn.get("required")]
                return ResolvedContext(
                    run_id=run_id,
                    report_path=report_path,
                    candidate_sha=cand_sha,
                    base_sha=base_sha,
                    base_ref=base_ref,
                    policy_file=CANONICAL_POLICY_PATH,
                    expected_policy_sha256=actual_sha,
                    expected_required_checks=req_checks,
                    is_transition=False,
                    tree_sha=git_context.get("tree_sha", ""),
                    parent_shas=tuple(git_context.get("parent_shas", []))
                )
            elif baseline_file.is_file():
                b_checks, b_sha, b_errs = load_policy_checks_and_digest(baseline_file)
                if not b_errs and b_sha.lower() == active_approved_policy_sha.lower():
                    req_checks = [cid for cid, defn in b_checks.items() if defn.get("required")]
                    return ResolvedContext(
                        run_id=run_id,
                        report_path=report_path,
                        candidate_sha=cand_sha,
                        base_sha=base_sha,
                        base_ref=base_ref,
                        policy_file=BASELINE_POLICY_PATH,
                        expected_policy_sha256=b_sha,
                        expected_required_checks=req_checks,
                        is_transition=False,
                        tree_sha=git_context.get("tree_sha", ""),
                        parent_shas=tuple(git_context.get("parent_shas", []))
                    )

            # Modified policy without approved transition authorization fails closed!
            raise TransitionError([
                f"Policy mismatch and absent transition authorization: file '{CANONICAL_POLICY_PATH}' "
                f"digest '{actual_sha}' does not match APPROVED_POLICY_SHA256 '{active_approved_policy_sha}', "
                f"and TRANSITION_AUTHORIZATION is not configured."
            ])
        else:
            # No APPROVED_POLICY_SHA256 configured in env: enforce canonical policy directly
            req_checks = [cid for cid, defn in p_checks.items() if defn.get("required")]
            return ResolvedContext(
                run_id=run_id,
                report_path=report_path,
                candidate_sha=cand_sha,
                base_sha=base_sha,
                base_ref=base_ref,
                policy_file=CANONICAL_POLICY_PATH,
                expected_policy_sha256=actual_sha,
                expected_required_checks=req_checks,
                is_transition=False,
                tree_sha=git_context.get("tree_sha", ""),
                parent_shas=tuple(git_context.get("parent_shas", []))
            )

def main():
    parser = argparse.ArgumentParser(description="Policy Transition Context Resolver")
    subparsers = parser.add_subparsers(dest="command", required=True)

    ctx_parser = subparsers.add_parser("resolve-context", help="Resolves CI verification context from event and environment")
    ctx_parser.add_argument("--event-name", type=str, required=True, help="GitHub event name (pull_request or push)")
    ctx_parser.add_argument("--pr-head", type=str, default="", help="PR head commit SHA")
    ctx_parser.add_argument("--pr-base", type=str, default="", help="PR base commit SHA")
    ctx_parser.add_argument("--pr-base-ref", type=str, default="", help="PR base ref name")
    ctx_parser.add_argument("--push-sha", type=str, default="", help="Push commit SHA")
    ctx_parser.add_argument("--push-before", type=str, default="", help="Push before commit SHA")
    ctx_parser.add_argument("--run-id", type=str, default="", help="Unique run ID")
    ctx_parser.add_argument("--repo-dir", type=str, default=".", help="Repository root path")
    ctx_parser.add_argument("--output-github-env", type=str, default=None, help="File path to write GITHUB_OUTPUT key-value pairs")

    args = parser.parse_args()
    repo_path = Path(args.repo_dir).resolve()

    if args.command == "resolve-context":
        event = args.event_name
        if event == "pull_request":
            cand_sha = args.pr_head
            base_sha = args.pr_base
            base_ref = args.pr_base_ref
        elif event == "push":
            cand_sha = args.push_sha
            base_sha = args.push_before
            if not base_sha or base_sha == "0" * 40:
                parents = get_commit_parents(repo_path, cand_sha)
                base_sha = parents[0] if parents else cand_sha
            base_ref = base_sha
        else:
            print(f"Error: Unsupported event name '{event}'", file=sys.stderr)
            sys.exit(1)

        tree_sha = get_tree_sha(repo_path, cand_sha) if cand_sha else ""
        parent_shas = get_commit_parents(repo_path, cand_sha) if cand_sha else []

        git_context = {
            "event_name": event,
            "candidate_sha": cand_sha,
            "base_sha": base_sha,
            "base_ref": base_ref,
            "tree_sha": tree_sha,
            "parent_shas": parent_shas,
            "run_id": args.run_id or f"run-{os.getpid()}"
        }

        try:
            resolved = resolve_verification_context(dict(os.environ), git_context, repo_path)
        except TransitionError as te:
            print("[TRANSITION ERROR] Failed to resolve verification context:", file=sys.stderr)
            for err in te.errors:
                print(f"  [-] {err}", file=sys.stderr)
            sys.exit(1)

        print("[TRANSITION CONTEXT RESOLVED]")
        print(f"  run_id:                   {resolved.run_id}")
        print(f"  candidate_sha:            {resolved.candidate_sha}")
        print(f"  base_sha:                 {resolved.base_sha}")
        print(f"  policy_file:              {resolved.policy_file}")
        print(f"  expected_policy_sha256:   {resolved.expected_policy_sha256}")
        print(f"  expected_required_checks: {resolved.expected_required_checks}")
        print(f"  is_transition:            {resolved.is_transition}")

        if args.output_github_env:
            out_p = Path(args.output_github_env)
            lines = [
                f"run_id={resolved.run_id}",
                f"report_path={resolved.report_path}",
                f"candidate_sha={resolved.candidate_sha}",
                f"base_sha={resolved.base_sha}",
                f"base_ref={resolved.base_ref}",
                f"tree_sha={resolved.tree_sha}",
                f"parent_shas={' '.join(resolved.parent_shas)}",
                f"policy_file={resolved.policy_file}",
                f"expected_policy_sha256={resolved.expected_policy_sha256}",
                f"expected_required_checks={' '.join(resolved.expected_required_checks)}",
                f"is_transition={'true' if resolved.is_transition else 'false'}"
            ]
            with open(out_p, "a", encoding="utf-8") as f:
                f.write("\n".join(lines) + "\n")

        sys.exit(0)

if __name__ == "__main__":
    main()
