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

if __name__ == "__main__":
    unittest.main()
