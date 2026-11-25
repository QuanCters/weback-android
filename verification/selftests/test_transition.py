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

    def test_11_e2e_runner_squash_merge_lifecycle_TEST_TRN_011(self):
        """Scenario 11: Real subprocess runner -> auditor E2E lifecycle across PR, squash-merge push, tamper rejections, and policy retirement."""
        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "CI Tester"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "ci@weback.local"], cwd=repo, check=True)

            pol_dir = repo / "policies"
            pol_dir.mkdir(parents=True)

            # Lightweight deterministic checks (independent of Android compilation)
            baseline_policy = {
                "version": 1,
                "checks": [
                    {
                        "id": "spec-hygiene",
                        "type": "command",
                        "argv": ["python3", "-c", "print('spec ok')"],
                        "timeout_seconds": 10,
                        "required": True
                    },
                    {
                        "id": "harness-selftests",
                        "type": "test_suite",
                        "argv": [
                            "python3", "-c",
                            "import json; print('TEST_SUMMARY_JSON: ' + json.dumps({'total': 2, 'passed': 2, 'failures': 0, 'errors': 0, 'skipped': 0}))"
                        ],
                        "timeout_seconds": 10,
                        "required": True
                    }
                ]
            }
            baseline_bytes = json.dumps(baseline_policy, indent=2).encode("utf-8")
            baseline_sha = compute_sha256_bytes(baseline_bytes)

            expanded_policy = {
                "version": 1,
                "checks": baseline_policy["checks"] + [
                    {
                        "id": "apk-validation",
                        "type": "command",
                        "argv": ["python3", "-c", "print('apk ok')"],
                        "timeout_seconds": 10,
                        "required": True
                    }
                ]
            }
            expanded_bytes = json.dumps(expanded_policy, indent=2).encode("utf-8")
            expanded_sha = compute_sha256_bytes(expanded_bytes)

            # 1. Initial baseline commit
            (pol_dir / "checks.baseline.json").write_bytes(baseline_bytes)
            (pol_dir / "checks.json").write_bytes(baseline_bytes)
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-m", "baseline"], cwd=repo, check=True, capture_output=True)
            base_branch = subprocess.run(["git", "branch", "--show-current"], cwd=repo, check=True, capture_output=True, text=True).stdout.strip()
            base_sha = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo, check=True, capture_output=True, text=True).stdout.strip()

            # 2. Feature candidate containing expanded policy
            subprocess.run(["git", "checkout", "-b", "feature/expand"], cwd=repo, check=True, capture_output=True)
            (pol_dir / "checks.json").write_bytes(expanded_bytes)
            (repo / "feature.txt").write_text("feature payload\n")
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-m", "expand policy"], cwd=repo, check=True, capture_output=True)

            # 3. Exact authorized candidate SHA, base SHA, and tree SHA
            cand_sha = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo, check=True, capture_output=True, text=True).stdout.strip()
            cand_tree = subprocess.run(["git", "rev-parse", "HEAD^{tree}"], cwd=repo, check=True, capture_output=True, text=True).stdout.strip()
            auth = {
                "candidate_sha": cand_sha,
                "target_base_sha": base_sha,
                "candidate_tree_sha": cand_tree,
                "canonical_policy_sha256": expanded_sha,
                "policy_file": "policies/checks.json",
                "retired_policies": [baseline_sha],
                "required_checks": ["spec-hygiene", "harness-selftests", "apk-validation"]
            }

            run_script = str(Path("verification/run.py").resolve())
            evidence_script = str(Path("verification/checks/evidence.py").resolve())
            clean_env = {
                k: v for k, v in os.environ.items()
                if k not in ("APPROVED_POLICY_SHA256", "TRANSITION_AUTHORIZATION", "GITHUB_EVENT_NAME")
            }
            pr_env = dict(clean_env)
            pr_env["APPROVED_POLICY_SHA256"] = baseline_sha
            pr_env["TRANSITION_AUTHORIZATION"] = json.dumps(auth)

            # 4. Execute actual candidate runner with pull_request context
            pr_run = subprocess.run(
                [
                    sys.executable, run_script,
                    "--lane", "candidate",
                    "--event-name", "pull_request",
                    "--base-ref", base_sha,
                    "--run-id", "e2e-pr-run",
                    "--repo-dir", str(repo)
                ],
                capture_output=True,
                text=True,
                env=pr_env
            )
            self.assertEqual(pr_run.returncode, 0, f"PR runner failed:\nstdout={pr_run.stdout}\nstderr={pr_run.stderr}")
            self.assertIn("Verdict: PASS", pr_run.stdout)

            # 5. Audit actual generated PR report via evidence.py CLI
            pr_report = repo / "artifacts" / "verification" / "e2e-pr-run" / "report.json"
            pr_audit = subprocess.run(
                [
                    sys.executable, evidence_script,
                    "--report", str(pr_report),
                    "--require-candidate",
                    "--expected-run-id", "e2e-pr-run",
                    "--expected-sha", cand_sha,
                    "--expected-base-sha", base_sha,
                    "--expected-policy-sha256", baseline_sha,
                    "--expected-required-checks", "spec-hygiene", "harness-selftests", "apk-validation",
                    "--policy", str(repo / "policies" / "checks.json"),
                    "--event-name", "pull_request",
                    "--expected-tree-sha", cand_tree,
                    "--parent-shas", base_sha
                ],
                capture_output=True,
                text=True,
                cwd=str(repo),
                env=pr_env
            )
            self.assertEqual(pr_audit.returncode, 0, f"PR audit failed:\nstdout={pr_audit.stdout}\nstderr={pr_audit.stderr}")

            # 6. Perform a real local squash merge
            subprocess.run(["git", "checkout", base_branch], cwd=repo, check=True, capture_output=True)
            subprocess.run(["git", "merge", "--squash", "feature/expand"], cwd=repo, check=True, capture_output=True)
            subprocess.run(["git", "commit", "-m", "Squash merge expanded policy"], cwd=repo, check=True, capture_output=True)
            squash_sha = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo, check=True, capture_output=True, text=True).stdout.strip()
            squash_tree = subprocess.run(["git", "rev-parse", "HEAD^{tree}"], cwd=repo, check=True, capture_output=True, text=True).stdout.strip()
            squash_parents = subprocess.run(["git", "rev-parse", "HEAD^@"], cwd=repo, check=True, capture_output=True, text=True).stdout.strip().splitlines()
            self.assertNotEqual(squash_sha, cand_sha)
            self.assertNotEqual(squash_sha, base_sha)
            self.assertEqual(squash_tree, cand_tree)
            self.assertEqual(squash_parents, [base_sha])

            # 7. Execute actual candidate runner with push context on squash-merged commit
            push_run = subprocess.run(
                [
                    sys.executable, run_script,
                    "--lane", "candidate",
                    "--event-name", "push",
                    "--base-ref", base_sha,
                    "--run-id", "e2e-push-run",
                    "--repo-dir", str(repo)
                ],
                capture_output=True,
                text=True,
                env=pr_env
            )
            self.assertEqual(push_run.returncode, 0, f"Push runner failed:\nstdout={push_run.stdout}\nstderr={push_run.stderr}")
            self.assertIn("Verdict: PASS", push_run.stdout)

            # 8. Audit actual generated push report via evidence.py CLI
            push_report = repo / "artifacts" / "verification" / "e2e-push-run" / "report.json"
            push_audit = subprocess.run(
                [
                    sys.executable, evidence_script,
                    "--report", str(push_report),
                    "--require-candidate",
                    "--expected-run-id", "e2e-push-run",
                    "--expected-sha", squash_sha,
                    "--expected-base-sha", base_sha,
                    "--expected-policy-sha256", baseline_sha,
                    "--expected-required-checks", "spec-hygiene", "harness-selftests", "apk-validation",
                    "--policy", str(repo / "policies" / "checks.json"),
                    "--event-name", "push",
                    "--expected-tree-sha", squash_tree,
                    "--parent-shas", *squash_parents
                ],
                capture_output=True,
                text=True,
                cwd=str(repo),
                env=pr_env
            )
            self.assertEqual(push_audit.returncode, 0, f"Push audit failed:\nstdout={push_audit.stdout}\nstderr={push_audit.stderr}")

            # 9a. Confirm rejection of an incorrect tree SHA
            subprocess.run(["git", "checkout", "-b", "bad-tree", squash_sha], cwd=repo, check=True, capture_output=True)
            (repo / "feature.txt").write_text("tampered payload\n")
            subprocess.run(["git", "commit", "--amend", "-am", "Tampered tree"], cwd=repo, check=True, capture_output=True)
            bad_tree_run = subprocess.run(
                [
                    sys.executable, run_script,
                    "--lane", "candidate",
                    "--event-name", "push",
                    "--base-ref", base_sha,
                    "--run-id", "e2e-bad-tree",
                    "--repo-dir", str(repo)
                ],
                capture_output=True,
                text=True,
                env=pr_env
            )
            self.assertEqual(bad_tree_run.returncode, 2)
            self.assertIn("Post-merge tree SHA mismatch", bad_tree_run.stderr)

            # 9b. Confirm rejection of multi-parent merge commit (violates single-parent provenance)
            subprocess.run(["git", "checkout", base_branch], cwd=repo, check=True, capture_output=True)
            subprocess.run(["git", "reset", "--hard", base_sha], cwd=repo, check=True, capture_output=True)
            subprocess.run(["git", "merge", "--no-ff", "-m", "Non-squash merge", "feature/expand"], cwd=repo, check=True, capture_output=True)
            multi_parent_run = subprocess.run(
                [
                    sys.executable, run_script,
                    "--lane", "candidate",
                    "--event-name", "push",
                    "--base-ref", base_sha,
                    "--run-id", "e2e-multi-parent",
                    "--repo-dir", str(repo)
                ],
                capture_output=True,
                text=True,
                env=pr_env
            )
            self.assertEqual(multi_parent_run.returncode, 2)
            self.assertIn("single-parent", multi_parent_run.stderr.lower())

            # 10. Confirm rejection of previous policy after retirement
            subprocess.run(["git", "reset", "--hard", squash_sha], cwd=repo, check=True, capture_output=True)
            retired_env = dict(clean_env)
            retired_env["APPROVED_POLICY_SHA256"] = expanded_sha
            bad_old_cfg = subprocess.run(
                [
                    sys.executable, run_script,
                    "--lane", "candidate",
                    "--event-name", "push",
                    "--config", "policies/checks.baseline.json",
                    "--base-ref", base_sha,
                    "--run-id", "e2e-retired-cfg",
                    "--repo-dir", str(repo)
                ],
                capture_output=True,
                text=True,
                env=retired_env
            )
            self.assertEqual(bad_old_cfg.returncode, 2)
            self.assertIn("Alternate configuration not approved", bad_old_cfg.stderr)

            retired_auth = dict(auth)
            retired_auth["canonical_policy_sha256"] = baseline_sha
            retired_auth["policy_file"] = "policies/checks.baseline.json"
            retired_auth["required_checks"] = ["spec-hygiene", "harness-selftests"]
            retired_grant_env = dict(clean_env)
            retired_grant_env["APPROVED_POLICY_SHA256"] = expanded_sha
            retired_grant_env["TRANSITION_AUTHORIZATION"] = json.dumps(retired_auth)
            bad_retired_grant = subprocess.run(
                [
                    sys.executable, run_script,
                    "--lane", "candidate",
                    "--event-name", "push",
                    "--base-ref", base_sha,
                    "--run-id", "e2e-retired-grant",
                    "--repo-dir", str(repo)
                ],
                capture_output=True,
                text=True,
                env=retired_grant_env
            )
            self.assertEqual(bad_retired_grant.returncode, 2)
            self.assertIn("retired policy digest", bad_retired_grant.stderr)

if __name__ == "__main__":
    unittest.main()
