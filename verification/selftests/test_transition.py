import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from verification.checks.transition import (
    resolve_verification_context,
    validate_transition_authorization,
    load_policy_checks_and_digest,
    compute_sha256_bytes,
    TransitionError,
    ResolvedContext,
    CANONICAL_POLICY_PATH,
    BASELINE_POLICY_PATH,
    HISTORICAL_BASELINE_SHA256
)
from verification.checks.evidence import verify_evidence_report, generate_evidence_report
from verification.checks.runner import CheckResult

class TestPolicyTransitionStateMachine(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp_dir.name)

        # Initialize synthetic git repo
        subprocess.run(["git", "init"], cwd=self.repo, check=True, capture_output=True)
        subprocess.run(["git", "config", "user.name", "CI Tester"], cwd=self.repo, check=True)
        subprocess.run(["git", "config", "user.email", "ci@weback.local"], cwd=self.repo, check=True)

        # Create policies directory
        self.pol_dir = self.repo / "policies"
        self.pol_dir.mkdir(parents=True)

        # Setup baseline policy (2 checks)
        self.baseline_policy = {
            "$schema": "../verification/schemas/checks.schema.json",
            "version": 1,
            "checks": [
                {
                    "id": "spec-hygiene",
                    "argv": ["python3", "verification/checks/spec_validator.py"],
                    "required": True,
                    "type": "command"
                },
                {
                    "id": "harness-selftests",
                    "argv": ["python3", "verification/checks/test_adapter.py"],
                    "required": True,
                    "type": "test_suite"
                }
            ]
        }
        self.baseline_bytes = json.dumps(self.baseline_policy, indent=2).encode("utf-8")
        self.baseline_sha = compute_sha256_bytes(self.baseline_bytes)

        # Setup expanded canonical policy (7 checks)
        self.canonical_policy = {
            "$schema": "../verification/schemas/checks.schema.json",
            "version": 1,
            "checks": [
                {"id": "spec-hygiene", "argv": ["python3", "verification/checks/spec_validator.py"], "required": True, "type": "command"},
                {"id": "harness-selftests", "argv": ["python3", "verification/checks/test_adapter.py"], "required": True, "type": "test_suite"},
                {"id": "gradle-wrapper-validation", "argv": ["python3", "verification/checks/gradle_wrapper_check.py"], "required": True, "type": "command"},
                {"id": "android-lint", "argv": ["./gradlew", "lintDebug"], "required": True, "type": "command"},
                {"id": "android-unit-tests", "argv": ["python3", "-m", "gradle_test_adapter"], "required": True, "type": "test_suite"},
                {"id": "android-build", "argv": ["./gradlew", "assembleDebug"], "required": True, "type": "command"},
                {"id": "apk-validation", "argv": ["python3", "verification/checks/apk_validator.py"], "required": True, "type": "command"}
            ]
        }
        self.canonical_bytes = json.dumps(self.canonical_policy, indent=2).encode("utf-8")
        self.canonical_sha = compute_sha256_bytes(self.canonical_bytes)

        # Write files in repo
        (self.pol_dir / "checks.baseline.json").write_bytes(self.baseline_bytes)
        (self.pol_dir / "checks.json").write_bytes(self.canonical_bytes)

        subprocess.run(["git", "add", "."], cwd=self.repo, check=True)
        subprocess.run(["git", "commit", "-m", "initial baseline commit"], cwd=self.repo, check=True, capture_output=True)
        res = subprocess.run(["git", "rev-parse", "HEAD"], cwd=self.repo, check=True, capture_output=True, text=True)
        self.base_commit_sha = res.stdout.strip()

        # Create candidate branch and commit
        subprocess.run(["git", "checkout", "-b", "feature/transition"], cwd=self.repo, check=True, capture_output=True)
        (self.repo / "app.txt").write_text("android app code")
        subprocess.run(["git", "add", "."], cwd=self.repo, check=True)
        subprocess.run(["git", "commit", "-m", "candidate work"], cwd=self.repo, check=True, capture_output=True)

        res_cand = subprocess.run(["git", "rev-parse", "HEAD"], cwd=self.repo, check=True, capture_output=True, text=True)
        self.cand_commit_sha = res_cand.stdout.strip()
        res_tree = subprocess.run(["git", "rev-parse", "HEAD^{tree}"], cwd=self.repo, check=True, capture_output=True, text=True)
        self.cand_tree_sha = res_tree.stdout.strip()

    def tearDown(self):
        self.tmp_dir.cleanup()

    def test_01_existing_baseline_verification_TEST_TRN_001(self):
        """Scenario 1: No transition authorization configured, verifies baseline policy."""
        env = {
            "APPROVED_POLICY_SHA256": self.baseline_sha
        }
        git_ctx = {
            "event_name": "pull_request",
            "candidate_sha": self.cand_commit_sha,
            "base_sha": self.base_commit_sha,
            "tree_sha": self.cand_tree_sha,
            "run_id": "baseline-run-1"
        }
        ctx = resolve_verification_context(env, git_ctx, self.repo)
        self.assertFalse(ctx.is_transition)
        self.assertEqual(ctx.policy_file, BASELINE_POLICY_PATH)
        self.assertEqual(ctx.expected_policy_sha256, self.baseline_sha)
        self.assertEqual(ctx.expected_required_checks, ["spec-hygiene", "harness-selftests"])

    def test_02_valid_transition_candidate_TEST_TRN_002(self):
        """Scenario 2: Valid transition grant enables expanded 7-check canonical policy."""
        auth = {
            "candidate_sha": self.cand_commit_sha,
            "target_base_sha": self.base_commit_sha,
            "candidate_tree_sha": self.cand_tree_sha,
            "canonical_policy_sha256": self.canonical_sha,
            "required_checks": [
                "spec-hygiene", "harness-selftests", "gradle-wrapper-validation",
                "android-lint", "android-unit-tests", "android-build", "apk-validation"
            ]
        }
        env = {
            "TRANSITION_AUTHORIZATION": json.dumps(auth),
            "APPROVED_POLICY_SHA256": self.baseline_sha
        }
        git_ctx = {
            "event_name": "pull_request",
            "candidate_sha": self.cand_commit_sha,
            "base_sha": self.base_commit_sha,
            "tree_sha": self.cand_tree_sha,
            "run_id": "transition-run-1"
        }
        ctx = resolve_verification_context(env, git_ctx, self.repo)
        self.assertTrue(ctx.is_transition)
        self.assertEqual(ctx.policy_file, CANONICAL_POLICY_PATH)
        self.assertEqual(ctx.expected_policy_sha256, self.canonical_sha)
        self.assertEqual(len(ctx.expected_required_checks), 7)

    def test_03_unauthorized_candidate_sha_rejected_TEST_TRN_003(self):
        """Scenario 3: Transition authorization with mismatched candidate SHA fails closed."""
        unauthorized_sha = "0123456789abcdef0123456789abcdef01234567"
        auth = {
            "candidate_sha": unauthorized_sha,
            "target_base_sha": self.base_commit_sha,
            "candidate_tree_sha": self.cand_tree_sha,
            "canonical_policy_sha256": self.canonical_sha,
            "required_checks": ["spec-hygiene", "harness-selftests"]
        }
        env = {"TRANSITION_AUTHORIZATION": json.dumps(auth)}
        git_ctx = {
            "event_name": "pull_request",
            "candidate_sha": self.cand_commit_sha,
            "base_sha": self.base_commit_sha,
            "tree_sha": self.cand_tree_sha
        }
        with self.assertRaises(TransitionError) as cm:
            resolve_verification_context(env, git_ctx, self.repo)
        self.assertTrue(any("Candidate SHA mismatch" in e for e in cm.exception.errors))

    def test_04_incorrect_base_sha_rejected_TEST_TRN_004(self):
        """Scenario 4: Transition authorization with mismatched target base SHA fails closed."""
        wrong_base = "fedcba9876543210fedcba9876543210fedcba98"
        auth = {
            "candidate_sha": self.cand_commit_sha,
            "target_base_sha": wrong_base,
            "candidate_tree_sha": self.cand_tree_sha,
            "canonical_policy_sha256": self.canonical_sha,
            "required_checks": ["spec-hygiene", "harness-selftests"]
        }
        env = {"TRANSITION_AUTHORIZATION": json.dumps(auth)}
        git_ctx = {
            "event_name": "pull_request",
            "candidate_sha": self.cand_commit_sha,
            "base_sha": self.base_commit_sha,
            "tree_sha": self.cand_tree_sha
        }
        with self.assertRaises(TransitionError) as cm:
            resolve_verification_context(env, git_ctx, self.repo)
        self.assertTrue(any("Target base SHA mismatch" in e for e in cm.exception.errors))

    def test_05_wrong_policy_digest_rejected_TEST_TRN_005(self):
        """Scenario 5: Wrong policy digest rejected fails closed."""
        wrong_digest = "e" * 64
        auth = {
            "candidate_sha": self.cand_commit_sha,
            "target_base_sha": self.base_commit_sha,
            "candidate_tree_sha": self.cand_tree_sha,
            "canonical_policy_sha256": wrong_digest,
            "required_checks": ["spec-hygiene", "harness-selftests"]
        }
        env = {"TRANSITION_AUTHORIZATION": json.dumps(auth)}
        git_ctx = {
            "event_name": "pull_request",
            "candidate_sha": self.cand_commit_sha,
            "base_sha": self.base_commit_sha,
            "tree_sha": self.cand_tree_sha
        }
        with self.assertRaises(TransitionError) as cm:
            resolve_verification_context(env, git_ctx, self.repo)
        self.assertTrue(any("Canonical policy digest mismatch" in e for e in cm.exception.errors))

    def test_06_missing_or_downgraded_required_checks_rejected_TEST_TRN_006(self):
        """Scenario 6: Transition authorization dropping mandatory checks or mismatching policy fails closed."""
        # A. Dropping mandatory harness-selftests
        auth_downgrade = {
            "candidate_sha": self.cand_commit_sha,
            "target_base_sha": self.base_commit_sha,
            "candidate_tree_sha": self.cand_tree_sha,
            "canonical_policy_sha256": self.canonical_sha,
            "required_checks": ["spec-hygiene"] # missing harness-selftests
        }
        env = {"TRANSITION_AUTHORIZATION": json.dumps(auth_downgrade)}
        git_ctx = {
            "event_name": "pull_request",
            "candidate_sha": self.cand_commit_sha,
            "base_sha": self.base_commit_sha,
            "tree_sha": self.cand_tree_sha
        }
        with self.assertRaises(TransitionError) as cm:
            resolve_verification_context(env, git_ctx, self.repo)
        self.assertTrue(any("removes mandatory baseline checks" in e or "does not match policy definitions" in e for e in cm.exception.errors))

    def test_07_candidate_runner_to_auditor_consistency_TEST_TRN_007(self):
        """Scenario 7: Candidate runner execution and auditor consume identical context and report."""
        auth = {
            "candidate_sha": self.cand_commit_sha,
            "target_base_sha": self.base_commit_sha,
            "candidate_tree_sha": self.cand_tree_sha,
            "canonical_policy_sha256": self.canonical_sha,
            "required_checks": [
                "spec-hygiene", "harness-selftests", "gradle-wrapper-validation",
                "android-lint", "android-unit-tests", "android-build", "apk-validation"
            ]
        }
        env = {
            "TRANSITION_AUTHORIZATION": json.dumps(auth),
            "APPROVED_POLICY_SHA256": self.baseline_sha
        }
        git_ctx = {
            "event_name": "pull_request",
            "candidate_sha": self.cand_commit_sha,
            "base_sha": self.base_commit_sha,
            "tree_sha": self.cand_tree_sha,
            "run_id": "consistent-run-1"
        }
        ctx = resolve_verification_context(env, git_ctx, self.repo)

        # Simulate runner producing report for all 7 required checks
        results = []
        for cid in ctx.expected_required_checks:
            res = CheckResult(
                id=cid,
                status="PASS",
                exit_code=0,
                duration_seconds=0.1,
                output=f"Output of {cid}\n".encode("utf-8")
            )
            if cid in ("harness-selftests", "android-unit-tests"):
                res.test_summary = {"total": 5, "passed": 5, "failures": 0, "errors": 0, "skipped": 0}
            results.append(res)

        out_dir = self.repo / "artifacts" / "verification" / ctx.run_id
        rep_p = generate_evidence_report(
            results=results,
            tested_sha=ctx.candidate_sha,
            base_sha=ctx.base_sha,
            policy_sha256=ctx.expected_policy_sha256,
            policy_modified_from_base=True,
            lane="candidate",
            output_dir=out_dir,
            run_id=ctx.run_id,
            required_ids=set(ctx.expected_required_checks)
        )

        # Auditor executes with identical resolved parameters
        ok, errs = verify_evidence_report(
            report_path=rep_p,
            expected_sha=ctx.candidate_sha,
            expected_base_sha=ctx.base_sha,
            expected_policy_sha256=ctx.expected_policy_sha256,
            required_check_ids=ctx.expected_required_checks,
            require_candidate=True,
            expected_run_id=ctx.run_id,
            policy_path=self.repo / ctx.policy_file,
            transition_auth=auth
        )
        self.assertTrue(ok, f"Runner-to-auditor consistency audit failed: {errs}")

        # Simulate runner-to-auditor inconsistency (runner executed baseline 2 checks, auditor expects 7)
        results_inconsistent = results[:2]
        out_dir_inc = self.repo / "artifacts" / "verification" / "inconsistent-run"
        rep_inc = generate_evidence_report(
            results=results_inconsistent,
            tested_sha=ctx.candidate_sha,
            base_sha=ctx.base_sha,
            policy_sha256=self.baseline_sha,
            policy_modified_from_base=False,
            lane="candidate",
            output_dir=out_dir_inc,
            run_id="inconsistent-run",
            required_ids={"spec-hygiene", "harness-selftests"}
        )
        ok_inc, errs_inc = verify_evidence_report(
            report_path=rep_inc,
            expected_sha=ctx.candidate_sha,
            expected_base_sha=ctx.base_sha,
            expected_policy_sha256=ctx.expected_policy_sha256,
            required_check_ids=ctx.expected_required_checks,
            require_candidate=True,
            expected_run_id="inconsistent-run",
            policy_path=self.repo / ctx.policy_file,
            transition_auth=auth
        )
        self.assertFalse(ok_inc, "Auditor must reject report that failed to execute all 7 required transition checks")

    def test_08_post_squash_merge_verification_TEST_TRN_008(self):
        """Scenario 8: Post-squash-merge push context validates tree identity and parent provenance."""
        # Perform actual squash merge to main
        subprocess.run(["git", "checkout", "master"], cwd=self.repo, check=True, capture_output=True)
        subprocess.run(["git", "merge", "--squash", "feature/transition"], cwd=self.repo, check=True, capture_output=True)
        subprocess.run(["git", "commit", "-m", "FCT-001 squash merged"], cwd=self.repo, check=True, capture_output=True)

        res_squash = subprocess.run(["git", "rev-parse", "HEAD"], cwd=self.repo, check=True, capture_output=True, text=True)
        squash_commit_sha = res_squash.stdout.strip()
        res_squash_tree = subprocess.run(["git", "rev-parse", "HEAD^{tree}"], cwd=self.repo, check=True, capture_output=True, text=True)
        squash_tree_sha = res_squash_tree.stdout.strip()

        # The squash commit SHA is different from candidate SHA, but tree SHA is identical!
        self.assertNotEqual(squash_commit_sha, self.cand_commit_sha)
        self.assertEqual(squash_tree_sha, self.cand_tree_sha)

        auth = {
            "candidate_sha": self.cand_commit_sha,
            "target_base_sha": self.base_commit_sha,
            "candidate_tree_sha": self.cand_tree_sha,
            "canonical_policy_sha256": self.canonical_sha,
            "required_checks": [
                "spec-hygiene", "harness-selftests", "gradle-wrapper-validation",
                "android-lint", "android-unit-tests", "android-build", "apk-validation"
            ]
        }
        env = {
            "TRANSITION_AUTHORIZATION": json.dumps(auth),
            "APPROVED_POLICY_SHA256": self.baseline_sha
        }
        # Push event on main after squash merge
        git_ctx = {
            "event_name": "push",
            "candidate_sha": squash_commit_sha,
            "base_sha": self.base_commit_sha,
            "tree_sha": squash_tree_sha,
            "parent_shas": [self.base_commit_sha],
            "run_id": "post-merge-run"
        }
        ctx = resolve_verification_context(env, git_ctx, self.repo)
        self.assertTrue(ctx.is_transition)
        self.assertEqual(ctx.policy_file, CANONICAL_POLICY_PATH)

        # Negative test: Post-merge tree SHA mutation fails closed
        git_ctx_mutated = dict(git_ctx)
        git_ctx_mutated["tree_sha"] = "0" * 40
        with self.assertRaises(TransitionError) as cm:
            resolve_verification_context(env, git_ctx_mutated, self.repo)
        self.assertTrue(any("Post-merge tree SHA mismatch" in e for e in cm.exception.errors))

        # Negative test: Base provenance mismatch fails closed
        git_ctx_wrong_base = dict(git_ctx)
        git_ctx_wrong_base["parent_shas"] = ["1" * 40]
        git_ctx_wrong_base["base_sha"] = "1" * 40
        with self.assertRaises(TransitionError) as cm2:
            resolve_verification_context(env, git_ctx_wrong_base, self.repo)
        self.assertTrue(any("Post-merge base provenance mismatch" in e for e in cm2.exception.errors))

        # End-to-end: verify_evidence_report in post-squash-merge push context MUST PASS
        results = []
        for cid in ctx.expected_required_checks:
            res = CheckResult(
                id=cid,
                status="PASS",
                exit_code=0,
                duration_seconds=0.1,
                output=f"Output of {cid}\n".encode("utf-8")
            )
            if cid in ("harness-selftests", "android-unit-tests"):
                res.test_summary = {"total": 5, "passed": 5, "failures": 0, "errors": 0, "skipped": 0}
            results.append(res)

        out_dir = self.repo / "artifacts" / "verification" / ctx.run_id
        rep_push = generate_evidence_report(
            results=results,
            tested_sha=squash_commit_sha,
            base_sha=self.base_commit_sha,
            policy_sha256=ctx.expected_policy_sha256,
            policy_modified_from_base=True,
            lane="candidate",
            output_dir=out_dir,
            run_id=ctx.run_id,
            required_ids=set(ctx.expected_required_checks)
        )
        ok_push, errs_push = verify_evidence_report(
            report_path=rep_push,
            expected_sha=squash_commit_sha,
            expected_base_sha=self.base_commit_sha,
            expected_policy_sha256=self.baseline_sha,
            required_check_ids=ctx.expected_required_checks,
            require_candidate=True,
            expected_run_id=ctx.run_id,
            policy_path=self.repo / ctx.policy_file,
            transition_auth=auth,
            event_name="push",
            expected_tree_sha=squash_tree_sha,
            parent_shas=[self.base_commit_sha]
        )
        self.assertTrue(ok_push, f"Post-squash-merge evidence audit failed: {errs_push}")

        # Negative end-to-end: mismatched tree SHA in push context MUST FAIL evidence audit
        ok_bad_tree, errs_bad_tree = verify_evidence_report(
            report_path=rep_push,
            expected_sha=squash_commit_sha,
            expected_base_sha=self.base_commit_sha,
            expected_policy_sha256=self.baseline_sha,
            required_check_ids=ctx.expected_required_checks,
            require_candidate=True,
            expected_run_id=ctx.run_id,
            policy_path=self.repo / ctx.policy_file,
            transition_auth=auth,
            event_name="push",
            expected_tree_sha="0" * 40,
            parent_shas=[self.base_commit_sha]
        )
        self.assertFalse(ok_bad_tree)
        self.assertTrue(any("tree SHA" in e for e in errs_bad_tree))

    def test_09_retirement_of_previous_policy_TEST_TRN_009(self):
        """Scenario 9: Attempting to select retired policy hash fails closed."""
        auth_with_retired = {
            "candidate_sha": self.cand_commit_sha,
            "target_base_sha": self.base_commit_sha,
            "candidate_tree_sha": self.cand_tree_sha,
            "canonical_policy_sha256": self.baseline_sha, # Trying to activate old policy!
            "policy_file": BASELINE_POLICY_PATH,
            "retired_policies": [self.baseline_sha],
            "required_checks": ["spec-hygiene", "harness-selftests"]
        }
        env = {"TRANSITION_AUTHORIZATION": json.dumps(auth_with_retired)}
        git_ctx = {
            "event_name": "pull_request",
            "candidate_sha": self.cand_commit_sha,
            "base_sha": self.base_commit_sha,
            "tree_sha": self.cand_tree_sha
        }
        with self.assertRaises(TransitionError) as cm:
            resolve_verification_context(env, git_ctx, self.repo)
        self.assertTrue(any("Attempt to activate retired policy digest" in e for e in cm.exception.errors))

    def test_10_unset_approved_policy_uses_canonical_not_baseline_TEST_TRN_010(self):
        """Scenario 10: When APPROVED_POLICY_SHA256 and TRANSITION_AUTHORIZATION are unset, canonical checks.json applies."""
        env = {}
        git_ctx = {
            "event_name": "pull_request",
            "candidate_sha": self.cand_commit_sha,
            "base_sha": self.base_commit_sha,
            "tree_sha": self.cand_tree_sha,
            "run_id": "local-run-1"
        }
        ctx = resolve_verification_context(env, git_ctx, self.repo)
        self.assertFalse(ctx.is_transition)
        self.assertEqual(ctx.policy_file, CANONICAL_POLICY_PATH)
        self.assertEqual(ctx.expected_policy_sha256, self.canonical_sha)
        self.assertEqual(len(ctx.expected_required_checks), 7)

if __name__ == "__main__":
    unittest.main()
