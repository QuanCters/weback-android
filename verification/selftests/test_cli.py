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

    def test_run_id_custom_and_path_traversal_rejection_TEST_RUN_020(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "custom.json"
            p.write_text(json.dumps({
                "checks": [
                    {"id": "fast-test", "argv": ["python3", "-c", "exit(0)"], "required": True, "timeout_seconds": 5}
                ]
            }))
            out_dir = Path(td) / "artifacts"
            # 1. Valid custom run-id
            res = subprocess.run(
                [sys.executable, "verification/run.py", "--lane", "fast", "--config", str(p), "--output-dir", str(out_dir), "--run-id", "my-run-123"],
                capture_output=True,
                text=True
            )
            self.assertEqual(res.returncode, 0, res.stderr)
            self.assertTrue((out_dir / "my-run-123" / "report.json").is_file())

            # 2. Path traversal run-id rejected
            res_bad = subprocess.run(
                [sys.executable, "verification/run.py", "--lane", "fast", "--config", str(p), "--output-dir", str(out_dir), "--run-id", "../../escaped"],
                capture_output=True,
                text=True
            )
            self.assertEqual(res_bad.returncode, 2)
            self.assertIn("Invalid run-id format", res_bad.stderr)

    def test_candidate_output_dir_restrictions_TEST_RUN_022(self):
        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Tester"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "tester@weback.local"], cwd=repo, check=True)
            # Canonical policy
            pol_dir = repo / "policies"
            pol_dir.mkdir(parents=True)
            canonical = Path("policies/checks.json").read_text()
            (pol_dir / "checks.json").write_text(canonical)
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-m", "init"], cwd=repo, check=True, capture_output=True)

            run_script = str(Path("verification/run.py").resolve())

            # 1. Reject path traversal in --output-dir
            res1 = subprocess.run(
                [sys.executable, run_script, "--lane", "candidate", "--base-ref", "HEAD", "--repo-dir", str(repo), "--output-dir", "../escaped"],
                capture_output=True,
                text=True
            )
            self.assertEqual(res1.returncode, 2)
            self.assertIn("prohibited in candidate lane", res1.stderr)

            # 2. Reject internal source directory as --output-dir
            res2 = subprocess.run(
                [sys.executable, run_script, "--lane", "candidate", "--base-ref", "HEAD", "--repo-dir", str(repo), "--output-dir", "policies"],
                capture_output=True,
                text=True
            )
            self.assertEqual(res2.returncode, 2)
            self.assertIn("Candidate lane output directory must be within", res2.stderr)

    def test_candidate_check_modifying_tracked_file_fails_cleanliness_TEST_RUN_023(self):
        # Reproduces defect where check modifies tracked file and must fail candidate lane
        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Tester"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "tester@weback.local"], cwd=repo, check=True)

            pol_dir = repo / "policies"
            pol_dir.mkdir(parents=True)
            tracked_source = repo / "tracked_source.py"
            tracked_source.write_text("# clean original\n")

            # Synthetic check modifies tracked_source.py
            policy_content = {
                "version": 1,
                "checks": [
                    {
                        "id": "mutating-check",
                        "type": "command",
                        "argv": ["python3", "-c", "import pathlib; pathlib.Path('tracked_source.py').write_text('# mutated by check')"],
                        "timeout_seconds": 10,
                        "required": True
                    }
                ]
            }
            (pol_dir / "checks.json").write_text(json.dumps(policy_content))
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-m", "init"], cwd=repo, check=True, capture_output=True)

            run_script = str(Path("verification/run.py").resolve())
            res = subprocess.run(
                [sys.executable, run_script, "--lane", "candidate", "--base-ref", "HEAD", "--repo-dir", str(repo)],
                capture_output=True,
                text=True
            )
            self.assertEqual(res.returncode, 2)
            self.assertIn("Repository mutated during execution", res.stderr)
            self.assertIn("tracked_source.py", res.stderr)



if __name__ == "__main__":
    unittest.main()
