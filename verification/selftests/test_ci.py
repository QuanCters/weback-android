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

if __name__ == "__main__":
    unittest.main()
