import unittest
from pathlib import Path
from verification.checks.spec_validator import validate_markdown_text, validate_spec_traceability
from verification.checks.evidence import verify_log_hash, verify_evidence_report, generate_evidence_report
from verification.checks.runner import CheckResult
from verification.checks.git_auditor import is_clean_checkout
from verification.checks.registry import load_and_validate_registry
import tempfile
import subprocess
import json

class TestControlledMutations(unittest.TestCase):
    def test_broken_link_mutation_TEST_MUT_001(self):
        errs = validate_markdown_text("[broken](missing_file_xyz.md)", base_dir=Path("."))
        self.assertGreater(len(errs), 0)
        self.assertIn("missing_file_xyz.md", errs[0])

    def test_untraced_requirement_mutation_TEST_MUT_002(self):
        with tempfile.TemporaryDirectory() as td:
            req_p = Path(td) / "req.md"
            req_p.write_text("### REQ-HARN-999 (MUST): Unmapped requirement\n- **AC-999.1:** Unmapped AC.")
            trace_p = Path(td) / "trace.json"
            trace_p.write_text(json.dumps({"requirements": [], "acceptance_criteria": []}))
            errs = validate_spec_traceability(req_p, trace_p)
            self.assertGreater(len(errs), 0)
            self.assertTrue(any("REQ-HARN-999" in e for e in errs))

    def test_untracked_file_mutation_TEST_MUT_003(self):
        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "test@test"], cwd=repo, check=True)
            subprocess.run(["git", "commit", "--allow-empty", "-m", "init"], cwd=repo, check=True)
            (repo / "untracked.tmp").write_text("mutation")
            clean, details = is_clean_checkout(repo)
            self.assertFalse(clean)
            self.assertIn("untracked.tmp", details)

    def test_tampered_log_mutation_TEST_MUT_004(self):
        with tempfile.TemporaryDirectory() as td:
            out_dir = Path(td)
            results = [CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"valid output\n")]
            report_path = generate_evidence_report(
                results,
                tested_sha="a"*40,
                base_sha="0"*40,
                policy_sha256="b"*64,
                policy_modified_from_base=False,
                lane="candidate",
                output_dir=out_dir
            )
            # Mutate log file by appending a byte
            with open(out_dir / "c1.log", "ab") as f:
                f.write(b"\n")
            ok, errs = verify_evidence_report(report_path)
            self.assertFalse(ok)
            self.assertTrue(any("Log integrity violation" in e for e in errs))

    def test_invalid_schema_mutation_TEST_MUT_005(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "bad.json"
            p.write_text('{"checks": [{"id": "c1", "argv": ["python3"], "required": true, "timeout_seconds": "30s"}]}')
            with self.assertRaises(ValueError) as ctx:
                load_and_validate_registry(str(p))
            self.assertIn("timeout_seconds must be a positive integer", str(ctx.exception))

if __name__ == "__main__":
    unittest.main()
