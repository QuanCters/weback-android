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

    def test_candidate_mode_missing_expected_context_TEST_EVI_013(self):
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
        # Missing expected_sha
        ok, errs = verify_evidence_report(
            report_path,
            require_candidate=True,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64,
            required_check_ids=["c1"]
        )
        self.assertFalse(ok)
        self.assertIn("Missing required context: expected_sha", "".join(errs))

        # Missing expected_base_sha
        ok, errs = verify_evidence_report(
            report_path,
            require_candidate=True,
            expected_sha="a"*40,
            expected_policy_sha256="b"*64,
            required_check_ids=["c1"]
        )
        self.assertFalse(ok)
        self.assertIn("Missing required context: expected_base_sha", "".join(errs))

        # Missing expected_policy_sha256
        ok, errs = verify_evidence_report(
            report_path,
            require_candidate=True,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            required_check_ids=["c1"]
        )
        self.assertFalse(ok)
        self.assertIn("Missing required context: expected_policy_sha256", "".join(errs))

        # Missing or empty required_check_ids
        ok, errs = verify_evidence_report(
            report_path,
            require_candidate=True,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64,
            required_check_ids=[]
        )
        self.assertFalse(ok)
        self.assertIn("Missing required context: required_check_ids", "".join(errs))

    def test_candidate_mode_invalid_sha_and_digest_format_TEST_EVI_014(self):
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
        # Invalid expected_sha format
        ok, errs = verify_evidence_report(
            report_path,
            require_candidate=True,
            expected_sha="not-a-valid-sha",
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64,
            required_check_ids=["c1"]
        )
        self.assertFalse(ok)
        self.assertIn("Invalid SHA format for expected_sha", "".join(errs))

        # Invalid expected_policy_sha256 format
        ok, errs = verify_evidence_report(
            report_path,
            require_candidate=True,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="short-sha",
            required_check_ids=["c1"]
        )
        self.assertFalse(ok)
        self.assertIn("Invalid SHA-256 digest format for expected_policy_sha256", "".join(errs))

    def test_candidate_mode_run_id_mismatch_TEST_EVI_015(self):
        results = [CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n")]
        report_path = generate_evidence_report(
            results,
            tested_sha="a"*40,
            base_sha="0"*40,
            policy_sha256="b"*64,
            policy_modified_from_base=False,
            lane="candidate",
            output_dir=self.out_dir,
            run_id="real-run-123"
        )
        ok, errs = verify_evidence_report(
            report_path,
            require_candidate=True,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64,
            required_check_ids=["c1"],
            expected_run_id="other-run-456"
        )
        self.assertFalse(ok)
        self.assertIn("Report run_id mismatch", "".join(errs))

    def test_empty_report_checks_array_TEST_EVI_016(self):
        results = []
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
            require_candidate=True,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64,
            required_check_ids=["c1"]
        )
        self.assertFalse(ok)
        self.assertIn("Report contains zero checks", "".join(errs))

    def test_log_path_traversal_rejected_TEST_EVI_021(self):
        # Report referencing a log file outside its directory (path traversal)
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
            "verdict": "PASS",
            "checks": [
                {
                    "id": "c1",
                    "status": "PASS",
                    "exit_code": 0,
                    "duration_seconds": 0.1,
                    "log_file": "../secret.log",
                    "log_sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                }
            ]
        }
        report_path = self.out_dir / "report.json"
        report_path.write_text(json.dumps(report_dict))
        ok, errs = verify_evidence_report(
            report_path,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64,
            required_check_ids=["c1"],
            require_candidate=True
        )
        self.assertFalse(ok)
        self.assertIn("Log path traversal detected", "".join(errs))

    def test_log_symlink_rejected_TEST_EVI_022(self):
        # Create a real log file and a symlink to it
        real_file = self.out_dir.parent / "real_file.log"
        real_file.write_bytes(b"content\n")
        symlink_log = self.out_dir / "c1.log"
        if not symlink_log.exists():
            symlink_log.symlink_to(real_file)

        import hashlib
        h = hashlib.sha256(b"content\n").hexdigest()
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
            "verdict": "PASS",
            "checks": [
                {
                    "id": "c1",
                    "status": "PASS",
                    "exit_code": 0,
                    "duration_seconds": 0.1,
                    "log_file": "c1.log",
                    "log_sha256": h
                }
            ]
        }
        report_path = self.out_dir / "report.json"
        report_path.write_text(json.dumps(report_dict))
        ok, errs = verify_evidence_report(
            report_path,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64,
            required_check_ids=["c1"],
            require_candidate=True
        )
        self.assertFalse(ok)
        self.assertIn("Unsafe symlink log reference detected", "".join(errs))

    def test_cleanup_failed_flag_rejected_in_candidate_TEST_EVI_023(self):
        results = [
            CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n", cleanup_failed=True)
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
            expected_policy_sha256="b"*64,
            required_check_ids=["c1"],
            require_candidate=True
        )
        self.assertFalse(ok)
        self.assertIn("cleanup_failed=true", "".join(errs))

    def test_unknown_check_status_rejected_TEST_EVI_024(self):
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
                    "status": "UNKNOWN_STATUS",
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
            expected_policy_sha256="b"*64,
            required_check_ids=["c1"]
        )
        self.assertFalse(ok)
        self.assertIn("Unknown or unsupported status", "".join(errs))

    def test_candidate_rejects_non_pass_verdict_TEST_EVI_025(self):
        results = [
            CheckResult(id="c1", status="FAIL", exit_code=1, duration_seconds=0.1, output=b"failed\n")
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
        # Diagnostically valid because verdict is truthfully FAIL and logs match
        ok_diag, errs_diag = verify_evidence_report(
            report_path,
            require_candidate=False
        )
        self.assertTrue(ok_diag, errs_diag)

        # But candidate acceptance MUST reject
        ok_cand, errs_cand = verify_evidence_report(
            report_path,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64,
            required_check_ids=["c1"],
            require_candidate=True
        )
        self.assertFalse(ok_cand)
        self.assertIn("Candidate acceptance rejected: verdict is FAIL", "".join(errs_cand))

    def test_test_suite_missing_summary_rejected_TEST_EVI_026(self):
        # c1 defined as test_suite in approved_checks, but lacks test_summary in report
        results = [
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
        approved_checks = {
            "c1": {"type": "test_suite", "required": True}
        }
        ok, errs = verify_evidence_report(
            report_path,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64,
            required_check_ids=["c1"],
            require_candidate=True,
            approved_checks=approved_checks
        )
        self.assertFalse(ok)
        self.assertIn("Missing required test_summary for test_suite check 'c1'", "".join(errs))

    def test_unexpected_check_id_rejected_TEST_EVI_027(self):
        results = [
            CheckResult(id="unapproved-check", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n")
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
        approved_checks = {
            "c1": {"type": "command", "required": True}
        }
        ok, errs = verify_evidence_report(
            report_path,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64,
            required_check_ids=["c1"],
            require_candidate=True,
            approved_checks=approved_checks
        )
        self.assertFalse(ok)
        self.assertIn("Unexpected unapproved check ID in report: unapproved-check", "".join(errs))

    def test_candidate_missing_approved_policy_definitions_TEST_EVI_028(self):
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
        # In candidate mode, omitting approved policy definitions must fail closed
        ok, errs = verify_evidence_report(
            report_path,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64,
            required_check_ids=["c1"],
            require_candidate=True
        )
        self.assertFalse(ok)
        self.assertIn("Missing required approved policy definitions in candidate mode", "".join(errs))

    def test_candidate_required_check_subset_rejected_TEST_EVI_029(self):
        results = [
            CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n"),
            CheckResult(id="c2", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n")
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
        approved_checks = {
            "c1": {"type": "command", "required": True},
            "c2": {"type": "command", "required": True}
        }
        # expected_required_checks only specifies ['c1'] (subset)
        ok, errs = verify_evidence_report(
            report_path,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64,
            required_check_ids=["c1"],
            require_candidate=True,
            approved_checks=approved_checks
        )
        self.assertFalse(ok)
        self.assertIn("Expected required checks set does not match approved policy required checks", "".join(errs))

    def test_candidate_conflicting_approved_definitions_TEST_EVI_030(self):
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
        policy_file = self.out_dir / "policy.json"
        policy_file.write_text(json.dumps({
            "checks": [{"id": "c1", "type": "command", "required": True, "argv": ["true"], "timeout_seconds": 10}]
        }))
        import hashlib
        p_sha = hashlib.sha256(policy_file.read_bytes()).hexdigest()

        # Conflicting approved_checks says c1 is test_suite
        conflicting_approved = {
            "c1": {"type": "test_suite", "required": True}
        }
        ok, errs = verify_evidence_report(
            report_path,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256=p_sha,
            required_check_ids=["c1"],
            require_candidate=True,
            approved_checks=conflicting_approved,
            policy_path=policy_file
        )
        self.assertFalse(ok)
        self.assertIn("Conflicting approved_checks and policy definitions", "".join(errs))

    def test_candidate_valid_approved_policy_and_complete_evidence_TEST_EVI_031(self):
        results = [
            CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"cmd ok\n"),
            CheckResult(
                id="c2",
                status="PASS",
                exit_code=0,
                duration_seconds=0.2,
                output=b"suite ok\n",
                test_summary={"total": 5, "passed": 5, "failures": 0, "errors": 0, "skipped": 0, "expected_failures": 0, "unexpected_successes": 0}
            )
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
        approved_checks = {
            "c1": {"type": "command", "required": True},
            "c2": {"type": "test_suite", "required": True}
        }
        ok, errs = verify_evidence_report(
            report_path,
            expected_sha="a"*40,
            expected_base_sha="0"*40,
            expected_policy_sha256="b"*64,
            required_check_ids=["c1", "c2"],
            require_candidate=True,
            approved_checks=approved_checks
        )
        self.assertTrue(ok, errs)





if __name__ == "__main__":
    unittest.main()
