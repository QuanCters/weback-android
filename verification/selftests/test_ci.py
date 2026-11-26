import json
import os
import subprocess
import sys
import tempfile
import unittest
import yaml
from pathlib import Path

from verification.checks.transition import compute_sha256_bytes

class TestCIWorkflow(unittest.TestCase):
    def test_workflow_structure_and_permissions_TEST_CI_001(self):
        wf_path = Path(".github/workflows/ci.yml")
        self.assertTrue(wf_path.exists(), "CI workflow file must exist at .github/workflows/ci.yml")
        raw_text = wf_path.read_text(encoding="utf-8")
        data = yaml.safe_load(raw_text)

        # 1. Triggers (handling YAML 1.1 bool on vs 'on' string)
        on_block = data.get("on") if "on" in data else data.get(True)
        self.assertIsNotNone(on_block, "Workflow must declare 'on' trigger block")
        self.assertIn("push", on_block)
        self.assertIn("pull_request", on_block)
        self.assertIn("main", on_block["push"]["branches"])
        self.assertIn("main", on_block["pull_request"]["branches"])

        # 2. Least Privilege Permissions
        self.assertEqual(data.get("permissions"), {"contents": "read"}, "Permissions must strictly be contents: read")

        # 3. Concurrency
        self.assertIn("concurrency", data)
        self.assertTrue(data["concurrency"].get("cancel-in-progress"))

        # 4. Job Steps
        self.assertIn("jobs", data)
        self.assertIn("verify", data["jobs"])
        job = data["jobs"]["verify"]
        self.assertIn("runs-on", job)
        steps = job["steps"]
        step_names = [s.get("name", "") for s in steps]
        self.assertTrue(any("Checkout" in n for n in step_names), "Must contain checkout step")
        self.assertTrue(any("Candidate" in n or "Verification" in n for n in step_names), "Must contain candidate verification step")

        # Verify candidate checkout ref
        checkout_step = next(s for s in steps if "actions/checkout" in s.get("uses", ""))
        self.assertEqual(checkout_step["with"]["ref"], "${{ github.event.pull_request.head.sha || github.sha }}")

        # Verify candidate lane invocation
        run_step = next(s for s in steps if "run.py" in s.get("run", ""))
        self.assertIn("--lane candidate", run_step["run"])
        self.assertFalse(run_step.get("continue-on-error", False), "continue-on-error prohibited on verification run")
        self.assertNotIn("|| true", run_step["run"], "Exit code suppression prohibited")

        # Verify evidence audit step
        audit_step = next((s for s in steps if "evidence.py" in s.get("run", "")), None)
        self.assertIsNotNone(audit_step, "CI workflow must include explicit evidence audit step")
        self.assertFalse(audit_step.get("continue-on-error", False), "continue-on-error prohibited on evidence audit")
        self.assertNotIn("|| true", audit_step["run"], "Exit code suppression prohibited on evidence audit")
        self.assertIn("--require-candidate", audit_step["run"])
        self.assertIn("--expected-sha", audit_step["run"])
        self.assertIn("--expected-base-sha", audit_step["run"])
        self.assertIn("--expected-policy-sha256", audit_step["run"])
        self.assertIn("--expected-required-checks", audit_step["run"])

        # Verify artifact upload step
        artifact_step = next((s for s in steps if "actions/upload-artifact" in s.get("uses", "")), None)
        self.assertIsNotNone(artifact_step, "Must include actions/upload-artifact step")
        self.assertIn("artifacts/verification", artifact_step["with"]["path"])
        self.assertEqual(artifact_step.get("if"), "always()", "Artifact upload must run on failure (if: always())")


        # 5. Zero Secrets Check
        self.assertNotIn("AWS_SECRET_ACCESS_KEY", raw_text)
        self.assertNotIn("AWS_ACCESS_KEY_ID", raw_text)
        self.assertNotIn("SIGNING_KEY", raw_text)

    def test_ci_workflow_no_hardcoded_sha_and_exact_run_binding_TEST_CI_002(self):
        wf_path = Path(".github/workflows/ci.yml")
        raw_text = wf_path.read_text(encoding="utf-8")
        data = yaml.safe_load(raw_text)

        # Must not contain hardcoded commit SHAs
        self.assertNotIn("4c7e78ca6c4a73290493fa543aaaa48336a181c3", raw_text)
        # Must not use ls -t or timestamps to select evidence reports
        self.assertNotIn("ls -t", raw_text)
        # Authoritative policy digest must not silently fall back to candidate-controlled default
        self.assertIn("vars.APPROVED_POLICY_SHA256", raw_text)
        self.assertNotIn("|| '4a004", raw_text)

        steps = data["jobs"]["verify"]["steps"]
        # Must have context computation step
        context_step = next((s for s in steps if s.get("id") == "context"), None)
        self.assertIsNotNone(context_step, "Workflow must have a step with id 'context' computing run provenance")
        c_run = context_step.get("run", "")
        self.assertIn("pull_request", c_run)
        self.assertIn("push", c_run)
        self.assertIn("candidate_sha", c_run)
        self.assertIn("base_sha", c_run)
        self.assertIn("base_ref", c_run)
        self.assertIn("run_id", c_run)
        self.assertIn("report_path", c_run)

        # Run step must bind --run-id, --event-name, and exact event-derived base SHA
        run_step = next(s for s in steps if "run.py" in s.get("run", ""))
        self.assertIn("--run-id", run_step["run"])
        self.assertIn('--event-name "${{ github.event_name }}"', run_step["run"])
        self.assertIn('--base-ref "${{ steps.context.outputs.base_sha }}"', run_step["run"])

        # Audit step must bind --expected-run-id and report path
        audit_step = next((s for s in steps if "evidence.py" in s.get("run", "")), None)
        self.assertIsNotNone(audit_step)
        self.assertIn("--expected-run-id", audit_step["run"])
        self.assertIn("--report", audit_step["run"])
        self.assertIn('--expected-policy-sha256 "${{ vars.APPROVED_POLICY_SHA256 }}"', audit_step["run"])

    def test_ci_event_context_derivation_logic_TEST_CI_003(self):
        # Deterministic simulation of the shell logic in compute context step
        def derive_context(event_name: str, pr_head: str, pr_base: str, pr_base_ref: str,
                           push_sha: str, push_before: str, fallback_head_parent: str):
            if event_name == "pull_request":
                return {
                    "candidate_sha": pr_head,
                    "base_sha": pr_base,
                    "base_ref": pr_base_ref
                }
            elif event_name == "push":
                base_sha = push_before
                if not base_sha or base_sha == "0" * 40:
                    base_sha = fallback_head_parent or push_sha
                return {
                    "candidate_sha": push_sha,
                    "base_sha": base_sha,
                    "base_ref": base_sha
                }
            raise ValueError(f"Unsupported event: {event_name}")

        # PR event
        pr_ctx = derive_context("pull_request", "head123", "base456", "main", "", "", "")
        self.assertEqual(pr_ctx["candidate_sha"], "head123")
        self.assertEqual(pr_ctx["base_sha"], "base456")
        self.assertEqual(pr_ctx["base_ref"], "main")

        # Push event normal
        push_ctx = derive_context("push", "", "", "", "pushsha789", "beforesha111", "")
        self.assertEqual(push_ctx["candidate_sha"], "pushsha789")
        self.assertEqual(push_ctx["base_sha"], "beforesha111")
        self.assertEqual(push_ctx["base_ref"], "beforesha111")

        # Push event with zero before (e.g., initial or recreated ref)
        push_zero_ctx = derive_context("push", "", "", "", "pushsha789", "0"*40, "parentsha222")
        self.assertEqual(push_zero_ctx["candidate_sha"], "pushsha789")
        self.assertEqual(push_zero_ctx["base_sha"], "parentsha222")
        self.assertEqual(push_zero_ctx["base_ref"], "parentsha222")

    def test_ci_candidate_runner_receives_policy_env_vars_TEST_CI_004(self):
        wf_path = Path(".github/workflows/ci.yml")
        data = yaml.safe_load(wf_path.read_text(encoding="utf-8"))
        steps = data["jobs"]["verify"]["steps"]
        run_step = next(s for s in steps if "run.py" in s.get("run", "") and "--lane candidate" in s.get("run", ""))
        env_map = run_step.get("env", {})
        self.assertEqual(env_map.get("APPROVED_POLICY_SHA256"), "${{ vars.APPROVED_POLICY_SHA256 }}")
        self.assertEqual(env_map.get("TRANSITION_AUTHORIZATION"), "${{ vars.TRANSITION_AUTHORIZATION }}")

    def test_ci_diagnostic_lane_policy_aware_condition_TEST_CI_005(self):
        wf_path = Path(".github/workflows/ci.yml")
        data = yaml.safe_load(wf_path.read_text(encoding="utf-8"))
        steps = data["jobs"]["verify"]["steps"]

        diag_run = next((s for s in steps if s.get("name") == "Run Diagnostic Proposed Checks"), None)
        diag_audit = next((s for s in steps if s.get("name") == "Audit Diagnostic Android Evidence Report"), None)
        auth_audit = next((s for s in steps if "Authoritative Evidence Audit" in s.get("name", "")), None)

        self.assertIsNotNone(diag_run, "Diagnostic run step must exist for proposed-policy preview")
        self.assertIsNotNone(diag_audit, "Diagnostic audit step must exist for proposed-policy preview")
        self.assertIsNotNone(auth_audit, "Authoritative Evidence Audit step must exist")
        self.assertNotIn("if", auth_audit, "Authoritative Evidence Audit must never be conditional/skipped")

        expected_if = (
            "steps.context.outputs.is_transition == 'false' && "
            "steps.context.outputs.has_unapproved_proposed_policy == 'true' && "
            "steps.context.outputs.policy_file != 'policies/checks.json'"
        )
        for step in (diag_run, diag_audit):
            cond = " ".join(str(step.get("if", "")).split())
            self.assertEqual(cond, expected_if)
            self.assertNotIn("github.ref", cond)
            self.assertNotIn("github.base_ref", cond)
            self.assertNotIn("github.head_ref", cond)
            self.assertNotIn("github.event_name", cond)
            self.assertFalse(step.get("continue-on-error", False))
            self.assertNotIn("|| true", step.get("run", ""))

        self.assertIn("--lane fast", diag_run["run"])
        self.assertIn("--config policies/checks.json", diag_run["run"])
        self.assertIn("${{ steps.context.outputs.run_id }}-diagnostic", diag_run["run"])

        self.assertIn("artifacts/verification/${{ steps.context.outputs.run_id }}-diagnostic/report.json", diag_audit["run"])
        self.assertIn("exit 1", diag_audit["run"])
        self.assertIn("--policy policies/checks.json", diag_audit["run"])

    def test_ci_context_step_to_diagnostic_gate_integration_TEST_CI_006(self):
        """End-to-end verification of transition.py resolve-context outputs driving CI diagnostic conditions."""
        def diag_gate_fires(outputs: dict[str, str]) -> bool:
            return (
                outputs.get("is_transition") == "false"
                and outputs.get("has_unapproved_proposed_policy") == "true"
                and outputs.get("policy_file") != "policies/checks.json"
            )

        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "CI Tester"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "ci@weback.local"], cwd=repo, check=True)

            pol_dir = repo / "policies"
            pol_dir.mkdir(parents=True)
            baseline_bytes = json.dumps({
                "version": 1,
                "checks": [
                    {"id": "spec-hygiene", "type": "command", "argv": ["python3", "-c", "exit(0)"], "required": True},
                    {"id": "harness-selftests", "type": "test_suite", "argv": ["python3", "-c", "exit(0)"], "required": True},
                ],
            }).encode("utf-8")
            canonical_bytes = json.dumps({
                "version": 1,
                "checks": [
                    {"id": "spec-hygiene", "type": "command", "argv": ["python3", "-c", "exit(0)"], "required": True},
                    {"id": "harness-selftests", "type": "test_suite", "argv": ["python3", "-c", "exit(0)"], "required": True},
                    {"id": "android-lint", "type": "command", "argv": ["python3", "-c", "exit(0)"], "required": True},
                ],
            }).encode("utf-8")
            baseline_sha = compute_sha256_bytes(baseline_bytes)
            canonical_sha = compute_sha256_bytes(canonical_bytes)

            (pol_dir / "checks.baseline.json").write_bytes(baseline_bytes)
            (pol_dir / "checks.json").write_bytes(canonical_bytes)
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-m", "base"], cwd=repo, check=True, capture_output=True)
            base_sha = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo, check=True, capture_output=True, text=True).stdout.strip()

            subprocess.run(["git", "checkout", "-b", "feat"], cwd=repo, check=True, capture_output=True)
            (repo / "src.txt").write_text("v1\n")
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-m", "cand"], cwd=repo, check=True, capture_output=True)
            cand_sha = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo, check=True, capture_output=True, text=True).stdout.strip()
            tree_sha = subprocess.run(["git", "rev-parse", "HEAD^{tree}"], cwd=repo, check=True, capture_output=True, text=True).stdout.strip()

            script = str(Path("verification/checks/transition.py").resolve())
            base_env = {
                k: v for k, v in os.environ.items()
                if k not in ("APPROVED_POLICY_SHA256", "TRANSITION_AUTHORIZATION", "GITHUB_EVENT_NAME")
            }

            def invoke_resolve(extra_env: dict[str, str], event: str = "pull_request", base_ref: str = "main"):
                out_file = repo / "gh_output.txt"
                out_file.unlink(missing_ok=True)
                env = dict(base_env)
                env.update(extra_env)
                cmd = [
                    sys.executable, script, "resolve-context",
                    "--event-name", event,
                    "--pr-head", cand_sha if event == "pull_request" else "",
                    "--pr-base", base_sha if event == "pull_request" else "",
                    "--pr-base-ref", base_ref if event == "pull_request" else "",
                    "--push-sha", cand_sha if event == "push" else "",
                    "--push-before", base_sha if event == "push" else "",
                    "--run-id", "ci-test-1",
                    "--repo-dir", str(repo),
                    "--output-github-env", str(out_file),
                ]
                proc = subprocess.run(cmd, capture_output=True, text=True, env=env)
                parsed = {}
                if out_file.is_file():
                    for line in out_file.read_text(encoding="utf-8").splitlines():
                        if "=" in line:
                            k, v = line.split("=", 1)
                            parsed[k] = v
                return proc, parsed

            # 1. Ordinary permanent policy (PR & push): diagnostic MUST NOT fire
            for evt, bref in [("pull_request", "main"), ("pull_request", "feature/any"), ("push", "main")]:
                p_perm, out_perm = invoke_resolve({"APPROVED_POLICY_SHA256": canonical_sha}, event=evt, base_ref=bref)
                self.assertEqual(p_perm.returncode, 0, p_perm.stderr)
                self.assertFalse(diag_gate_fires(out_perm))

            # 2. Proposed policy with different digest: diagnostic MUST fire
            p_prop, out_prop = invoke_resolve({"APPROVED_POLICY_SHA256": baseline_sha})
            self.assertEqual(p_prop.returncode, 0, p_prop.stderr)
            self.assertTrue(diag_gate_fires(out_prop))

            # 3. Authorized transition: diagnostic MUST NOT fire
            auth = {
                "candidate_sha": cand_sha,
                "target_base_sha": base_sha,
                "candidate_tree_sha": tree_sha,
                "canonical_policy_sha256": canonical_sha,
                "required_checks": ["spec-hygiene", "harness-selftests", "android-lint"],
            }
            p_auth, out_auth = invoke_resolve({
                "APPROVED_POLICY_SHA256": baseline_sha,
                "TRANSITION_AUTHORIZATION": json.dumps(auth),
            })
            self.assertEqual(p_auth.returncode, 0, p_auth.stderr)
            self.assertFalse(diag_gate_fires(out_auth))

            # 4. Unauthorized policy modification: MUST fail closed
            p_unauth, out_unauth = invoke_resolve({"APPROVED_POLICY_SHA256": "f" * 64})
            self.assertNotEqual(p_unauth.returncode, 0)
            self.assertEqual(out_unauth, {})

            # 5. Malformed policy context: MUST fail closed
            p_malformed, out_malformed = invoke_resolve({"APPROVED_POLICY_SHA256": "malformed"})
            self.assertNotEqual(p_malformed.returncode, 0)
            self.assertEqual(out_malformed, {})

    def test_ci_robolectric_m2_cache_hygiene_TEST_CI_007(self):
        wf_path = Path(".github/workflows/ci.yml")
        data = yaml.safe_load(wf_path.read_text(encoding="utf-8"))
        steps = data["jobs"]["verify"]["steps"]

        cache_steps = [s for s in steps if "actions/cache" in s.get("uses", "")]
        robo_cache = next(
            (s for s in cache_steps if "~/.m2/repository/org/robolectric" in str(s.get("with", {}).get("path", ""))),
            None,
        )
        self.assertIsNotNone(robo_cache, "CI workflow must cache ~/.m2/repository/org/robolectric")

        robo_with = robo_cache["with"]
        self.assertEqual(str(robo_with.get("path", "")).strip(), "~/.m2/repository/org/robolectric")
        robo_key = robo_with.get("key", "")
        self.assertIn("gradle/libs.versions.toml", robo_key)
        self.assertIn("app/build.gradle.kts", robo_key)
        self.assertIn("hashFiles(", robo_key)
        self.assertNotIn("restore-keys", robo_with, "Robolectric m2 cache must avoid stale cross-version restore-keys")

        forbidden_cache_substrings = [
            "test-results",
            "outputs/apk",
            "artifacts/verification",
            "keystore",
            ".jks",
            "credentials",
            "secrets",
            "auth",
        ]
        for cs in cache_steps:
            c_path = str(cs.get("with", {}).get("path", "")).lower()
            for bad in forbidden_cache_substrings:
                self.assertNotIn(bad, c_path, f"Forbidden path '{bad}' in CI cache step")

    def test_ci_prewarm_dependency_only_after_wrapper_check_TEST_CI_008(self):
        wf_path = Path(".github/workflows/ci.yml")
        data = yaml.safe_load(wf_path.read_text(encoding="utf-8"))
        steps = data["jobs"]["verify"]["steps"]

        wrapper_idx = next(
            (i for i, s in enumerate(steps) if "gradle_wrapper_check.py" in s.get("run", "")),
            None,
        )
        prewarm_idx = next(
            (i for i, s in enumerate(steps) if s.get("name") == "Pre-warm Gradle Dependencies"),
            None,
        )
        first_gradlew_idx = next(
            (i for i, s in enumerate(steps) if "./gradlew" in s.get("run", "")),
            None,
        )

        self.assertIsNotNone(wrapper_idx, "Validate Gradle Wrapper Integrity step must exist")
        self.assertIsNotNone(prewarm_idx, "Pre-warm Gradle Dependencies step must exist")
        self.assertIsNotNone(first_gradlew_idx)
        self.assertLess(
            wrapper_idx,
            first_gradlew_idx,
            "gradle_wrapper_check.py must execute before any ./gradlew invocation",
        )
        self.assertLess(wrapper_idx, prewarm_idx)

        prewarm_step = steps[prewarm_idx]
        prewarm_run = prewarm_step.get("run", "")
        self.assertIn(":app:androidDependencies", prewarm_run)
        self.assertNotIn("testDebugUnitTest", prewarm_run)
        self.assertNotIn("testPilotUnitTest", prewarm_run)
        self.assertNotIn("--dry-run", prewarm_run)
        self.assertFalse(prewarm_step.get("continue-on-error", False))
        self.assertNotIn("|| true", prewarm_run)

if __name__ == "__main__":
    unittest.main()
