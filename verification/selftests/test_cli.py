import unittest
import subprocess
import sys
import tempfile
import json
from pathlib import Path

class TestCliRunner(unittest.TestCase):
    def test_cli_help(self):
        res = subprocess.run([sys.executable, "verification/run.py", "--help"], capture_output=True, text=True)
        self.assertEqual(res.returncode, 0)
        self.assertIn("--lane", res.stdout)
        self.assertIn("--check", res.stdout)

    def test_candidate_partial_check_prohibited_TEST_RUN_009(self):
        res = subprocess.run([sys.executable, "verification/run.py", "--lane", "candidate", "--check", "c1"], capture_output=True, text=True)
        self.assertEqual(res.returncode, 2)
        self.assertIn("Partial check execution is prohibited in candidate lane", res.stderr)

    def test_candidate_alternate_config_prohibited_TEST_RUN_010(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "alt.json"
            p.write_text('{"checks": [{"id": "c1", "argv": ["python3"], "required": true, "timeout_seconds": 5}]}')
            res = subprocess.run([sys.executable, "verification/run.py", "--lane", "candidate", "--config", str(p)], capture_output=True, text=True)
            self.assertEqual(res.returncode, 2)
            self.assertIn("Alternate configuration not approved for candidate lane", res.stderr)

    def test_fast_lane_with_custom_config(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "custom.json"
            p.write_text(json.dumps({
                "checks": [
                    {"id": "fast-test", "argv": ["python3", "-c", "exit(0)"], "required": True, "timeout_seconds": 5}
                ]
            }))
            out_dir = Path(td) / "artifacts"
            res = subprocess.run(
                [sys.executable, "verification/run.py", "--lane", "fast", "--config", str(p), "--output-dir", str(out_dir)],
                capture_output=True,
                text=True
            )
            self.assertEqual(res.returncode, 0, res.stderr)
            self.assertIn("PASS", res.stdout)
            # Verify report exists
            reports = list(out_dir.glob("*/report.json"))
            self.assertEqual(len(reports), 1)
            report_data = json.loads(reports[0].read_text())
            self.assertEqual(report_data["lane"], "fast")
            self.assertEqual(report_data["verdict"], "PASS")

    def test_unresolvable_base_ref_rejected_in_candidate_lane_TEST_RUN_019(self):
        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Tester"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "tester@weback.local"], cwd=repo, check=True)
            # Copy canonical policy
            pol_dir = repo / "policies"
            pol_dir.mkdir(parents=True)
            canonical = Path("policies/checks.json").read_text()
            (pol_dir / "checks.json").write_text(canonical)
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-m", "init"], cwd=repo, check=True, capture_output=True)

            res = subprocess.run(
                [sys.executable, str(Path("verification/run.py").resolve()), "--lane", "candidate", "--base-ref", "non_existent_branch_xyz", "--repo-dir", str(repo)],
                capture_output=True,
                text=True
            )
            self.assertEqual(res.returncode, 2)
            self.assertIn("Could not resolve base SHA", res.stderr)



if __name__ == "__main__":
    unittest.main()
