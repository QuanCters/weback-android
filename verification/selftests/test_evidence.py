import unittest
import tempfile
import json
from pathlib import Path
from verification.checks.runner import CheckResult
from verification.checks.evidence import (
    generate_evidence_report,
    verify_evidence_report,
    verify_log_hash
)

class TestEvidence(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory()
        self.out_dir = Path(self.td.name)

    def tearDown(self):
        self.td.cleanup()

    def test_valid_evidence_audit_TEST_EVI_001(self):
        results = [CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n")]
        report_path = generate_evidence_report(
            results,
            tested_sha="a"*40,
            base_sha="0"*40,
            policy_sha256="b"*64,
            policy_modified_from_base=False,
            lane="candidate",
            output_dir=self.out_dir
        )
        ok, errs = verify_evidence_report(
            report_path,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64,
            required_check_ids=["c1"]
        )
        self.assertTrue(ok, errs)

    def test_missing_required_check_TEST_EVI_002(self):
        results = []  # Missing required c1
        report_path = generate_evidence_report(
            results,
            tested_sha="a"*40,
            base_sha="0"*40,
            policy_sha256="b"*64,
            policy_modified_from_base=False,
            lane="candidate",
            output_dir=self.out_dir
        )
        ok, errs = verify_evidence_report(
            report_path,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64,
            required_check_ids=["c1"]
        )
        self.assertFalse(ok)
        self.assertIn("Missing result for required check: c1", "".join(errs))

    def test_duplicate_check_id_TEST_EVI_003(self):
        results = [
            CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n"),
            CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n")
        ]
        report_path = generate_evidence_report(
            results,
            tested_sha="a"*40,
            base_sha="0"*40,
            policy_sha256="b"*64,
            policy_modified_from_base=False,
            lane="candidate",
            output_dir=self.out_dir
        )
        ok, errs = verify_evidence_report(
            report_path,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64
        )
        self.assertFalse(ok)
        self.assertIn("Duplicate check ID in report: c1", "".join(errs))

    def test_malformed_report_schema_TEST_EVI_004(self):
        p = self.out_dir / "bad_report.json"
        p.write_text(json.dumps({"schema_version": 1}))  # Missing required keys
        ok, errs = verify_evidence_report(p)
        self.assertFalse(ok)
        self.assertIn("missing required property", "".join(errs))

    def test_stale_tested_sha_TEST_EVI_005(self):
        results = [CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n")]
        report_path = generate_evidence_report(
            results,
            tested_sha="a"*40,
            base_sha="0"*40,
            policy_sha256="b"*64,
            policy_modified_from_base=False,
            lane="candidate",
            output_dir=self.out_dir
        )
        ok, errs = verify_evidence_report(
            report_path,
            expected_sha="c"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64
        )
        self.assertFalse(ok)
        self.assertIn("Report tested_sha mismatch", "".join(errs))

    def test_policy_digest_mismatch_TEST_EVI_006(self):
        results = [CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n")]
        report_path = generate_evidence_report(
            results,
            tested_sha="a"*40,
            base_sha="0"*40,
            policy_sha256="b"*64,
            policy_modified_from_base=False,
            lane="candidate",
            output_dir=self.out_dir
        )
        ok, errs = verify_evidence_report(
            report_path,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="d"*64
        )
        self.assertFalse(ok)
        self.assertIn("Policy hash mismatch", "".join(errs))

    def test_log_tampering_detected_TEST_EVI_007(self):
        results = [CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"original\n")]
        report_path = generate_evidence_report(
            results,
            tested_sha="a"*40,
            base_sha="0"*40,
            policy_sha256="b"*64,
            policy_modified_from_base=False,
            lane="candidate",
            output_dir=self.out_dir
        )
        # Tamper log
        (self.out_dir / "c1.log").write_text("tampered")
        ok, errs = verify_evidence_report(
            report_path,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64
        )
        self.assertFalse(ok)
        self.assertIn("Log integrity violation", "".join(errs))

    def test_missing_raw_log_TEST_EVI_008(self):
        results = [CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n")]
        report_path = generate_evidence_report(
            results,
            tested_sha="a"*40,
            base_sha="0"*40,
            policy_sha256="b"*64,
            policy_modified_from_base=False,
            lane="candidate",
            output_dir=self.out_dir
        )
        (self.out_dir / "c1.log").unlink()
        ok, errs = verify_evidence_report(
            report_path,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64
        )
        self.assertFalse(ok)
        self.assertIn("Referenced log file missing", "".join(errs))

    def test_rejection_of_fast_lane_report_TEST_EVI_009(self):
        results = [CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n")]
        report_path = generate_evidence_report(
            results,
            tested_sha="a"*40,
            base_sha="0"*40,
            policy_sha256="b"*64,
            policy_modified_from_base=False,
            lane="fast",
            output_dir=self.out_dir
        )
        ok, errs = verify_evidence_report(
            report_path,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64,
            require_candidate=True
        )
        self.assertFalse(ok)
        self.assertIn("Fast lane report cannot be used for candidate gate", "".join(errs))

    def test_forged_pass_verdict_rejection_TEST_EVI_010(self):
        results = [CheckResult(id="c1", status="FAIL", exit_code=1, duration_seconds=0.1, output=b"failed\n")]
        report_path = generate_evidence_report(
            results,
            tested_sha="a"*40,
            base_sha="0"*40,
            policy_sha256="b"*64,
            policy_modified_from_base=False,
            lane="candidate",
            output_dir=self.out_dir
        )
        # Tamper report verdict to PASS
        data = json.loads(report_path.read_text())
        data["verdict"] = "PASS"
        report_path.write_text(json.dumps(data))
        ok, errs = verify_evidence_report(
            report_path,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64,
            required_check_ids=["c1"]
        )
        self.assertFalse(ok)
        self.assertIn("Forged PASS verdict rejected", "".join(errs))

    def test_inconsistent_status_and_code_TEST_EVI_011(self):
        # We craft a report with inconsistent check status and exit code
        report_dict = {
            "schema_version": 1,
            "run_id": "test-uuid",
            "tested_sha": "a"*40,
            "base_sha": "0"*40,
            "policy_sha256": "b"*64,
            "policy_modified_from_base": False,
            "lane": "candidate",
            "started_at": "2026-11-24T00:00:00Z",
            "finished_at": "2026-11-24T00:00:01Z",
            "environment": {"python_version": "3.12", "platform": "linux"},
            "verdict": "FAIL",
            "checks": [
                {
                    "id": "c1",
                    "status": "PASS",
                    "exit_code": 1,
                    "duration_seconds": 0.1,
                    "log_file": "c1.log",
                    "log_sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                }
            ]
        }
        (self.out_dir / "c1.log").write_bytes(b"")
        report_path = self.out_dir / "report.json"
        report_path.write_text(json.dumps(report_dict))
        ok, errs = verify_evidence_report(
            report_path,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64
        )
        self.assertFalse(ok)
        self.assertIn("Inconsistent check status and exit code", "".join(errs))

    def test_mismatched_external_policy_context_TEST_EVI_012(self):
        results = [CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n")]
        report_path = generate_evidence_report(
            results,
            tested_sha="a"*40,
            base_sha="0"*40,
            policy_sha256="b"*64,
            policy_modified_from_base=False,
            lane="candidate",
            output_dir=self.out_dir
        )
        ok, errs = verify_evidence_report(
            report_path,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="w"*64
        )
        self.assertFalse(ok)
        self.assertIn("Policy hash mismatch against expected context", "".join(errs))

    def test_verify_log_hash_helper(self):
        content = b"sample log\n"
        import hashlib
        h = hashlib.sha256(content).hexdigest()
        self.assertTrue(verify_log_hash(content, h))
        self.assertFalse(verify_log_hash(content, "0"*64))

if __name__ == "__main__":
    unittest.main()
