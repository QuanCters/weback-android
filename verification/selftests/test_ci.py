import unittest
import yaml
from pathlib import Path

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

        # Run step must bind --run-id and exact event-derived base SHA
        run_step = next(s for s in steps if "run.py" in s.get("run", ""))
        self.assertIn("--run-id", run_step["run"])
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

if __name__ == "__main__":
    unittest.main()
