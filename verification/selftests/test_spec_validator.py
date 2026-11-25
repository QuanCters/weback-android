import unittest
import tempfile
import json
from pathlib import Path
from verification.checks.spec_validator import (
    validate_markdown_file,
    validate_markdown_text,
    validate_spec_traceability
)

class TestSpecValidator(unittest.TestCase):
    def test_detect_broken_link_TEST_MUT_001(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "doc.md"
            p.write_text("See [broken](non_existent_file.md)")
            errs = validate_markdown_file(p, repo_root=Path(td))
            self.assertGreater(len(errs), 0)
            self.assertIn("non_existent_file.md", errs[0])

    def test_ignore_web_links(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "doc.md"
            p.write_text("See [Google](https://google.com) and [Mail](mailto:test@local)")
            errs = validate_markdown_file(p, repo_root=Path(td))
            self.assertEqual(len(errs), 0)

    def test_detect_untraced_requirement_TEST_MUT_002(self):
        with tempfile.TemporaryDirectory() as td:
            req_p = Path(td) / "req.md"
            req_p.write_text("### REQ-HARN-001 (MUST): Test")
            trace_p = Path(td) / "trace.json"
            trace_p.write_text(json.dumps({"requirements": []}))
            errs = validate_spec_traceability(req_p, trace_p)
            self.assertGreater(len(errs), 0)
            self.assertIn("REQ-HARN-001", errs[0])

    def test_valid_traceability(self):
        with tempfile.TemporaryDirectory() as td:
            req_p = Path(td) / "req.md"
            req_p.write_text("### REQ-HARN-001 (MUST): Test\n- **AC-001.1:** Do something.")
            trace_p = Path(td) / "trace.json"
            trace_p.write_text(json.dumps({
                "requirements": [
                    {
                        "requirement_id": "REQ-HARN-001",
                        "acceptance_criteria": [
                            {
                                "ac_id": "AC-001.1",
                                "task_id": "TASK-1",
                                "verification_check_id": "spec-hygiene",
                                "test_oracle": "asserts something"
                            }
                        ]
                    }
                ]
            }))
            errs = validate_spec_traceability(req_p, trace_p)
            self.assertEqual(len(errs), 0, errs)

    def test_anchor_validation(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "doc.md"
            p.write_text("## My Heading\n\nSee [link](#my-heading) and [bad](#missing-heading)")
            errs = validate_markdown_file(p, repo_root=Path(td))
            self.assertEqual(len(errs), 1)
            self.assertIn("missing-heading", errs[0])

    def test_detect_machine_specific_absolute_path(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "doc.md"
            p.write_text("See [baseline](file:///home/developer/project/doc.md)")
            errs = validate_markdown_file(p, repo_root=Path(td))
            self.assertGreater(len(errs), 0)
            self.assertTrue(any("workstation" in e.lower() or "file:///home" in e or "does not exist" in e for e in errs))

    def test_detect_cross_spec_duplicate_ids_TEST_SPEC_001(self):
        from verification.checks.spec_validator import validate_all_change_specs
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            specs_dir = root / "specs" / "changes"
            spec1 = specs_dir / "FCT-001"
            spec2 = specs_dir / "FCT-002"
            spec1.mkdir(parents=True)
            spec2.mkdir(parents=True)

            # Both specs declare REQ-COMM-001 and AC-001.1
            (spec1 / "requirements.md").write_text("### REQ-COMM-001\n- **AC-001.1:** Do it\n")
            (spec1 / "tasks.md").write_text("### Task 1: Task 1\n")
            (spec1 / "validation.md").write_text("`TEST-1`: Test 1\n")
            (spec1 / "traceability.json").write_text(json.dumps({
                "requirements": [{"requirement_id": "REQ-COMM-001", "acceptance_criteria": [{"ac_id": "AC-001.1", "task_id": "Task 1", "verification_check_id": "check1", "test_oracle": "TEST-1"}]}]
            }))
            (spec2 / "requirements.md").write_text("### REQ-COMM-001\n- **AC-001.1:** Do it again\n")
            (spec2 / "tasks.md").write_text("### Task 1: Task 1\n")
            (spec2 / "validation.md").write_text("`TEST-1`: Test 1\n")
            (spec2 / "traceability.json").write_text(json.dumps({
                "requirements": [{"requirement_id": "REQ-COMM-001", "acceptance_criteria": [{"ac_id": "AC-001.1", "task_id": "Task 1", "verification_check_id": "check1", "test_oracle": "TEST-1"}]}]
            }))

            errs = validate_all_change_specs(root)
            self.assertGreater(len(errs), 0)
            self.assertTrue(any("Duplicate requirement ID 'REQ-COMM-001'" in e for e in errs))
            self.assertTrue(any("Duplicate acceptance criteria ID 'AC-001.1'" in e for e in errs))

    def test_detect_link_escaping_repo_root_TEST_SPEC_002(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            doc = root / "docs" / "doc.md"
            doc.parent.mkdir(parents=True)
            doc.write_text("See [escape](../../../../../../etc/passwd)")
            errs = validate_markdown_file(doc, repo_root=root)
            self.assertGreater(len(errs), 0)
            self.assertTrue(any("escapes repository root" in e for e in errs))

    def test_spec_validator_fail_closed_missing_spec_file_TEST_SPEC_003(self):
        from verification.checks.spec_validator import validate_all_change_specs
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            cdir = root / "specs" / "changes" / "FCT-999"
            cdir.mkdir(parents=True)
            (cdir / "requirements.md").write_text("### REQ-TEST-001\n- **AC-001.1:** desc\n")
            # Missing validation.md, tasks.md, traceability.json
            errs = validate_all_change_specs(root)
            self.assertGreater(len(errs), 0)
            self.assertTrue(any("Missing required specification file" in e for e in errs))

    def test_spec_validator_detect_duplicate_ids_within_document_TEST_SPEC_004(self):
        from verification.checks.spec_validator import validate_all_change_specs
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            cdir = root / "specs" / "changes" / "FCT-001"
            cdir.mkdir(parents=True)
            # Duplicate REQ and AC within the same document
            (cdir / "requirements.md").write_text(
                "### REQ-TEST-001\n- **AC-001.1:** First\n"
                "### REQ-TEST-001\n- **AC-001.1:** Second duplicate\n"
            )
            (cdir / "tasks.md").write_text("### Task 1: Do it\n")
            (cdir / "validation.md").write_text("`TEST-001`: Verify\n")
            (cdir / "traceability.json").write_text(json.dumps({
                "requirements": [
                    {
                        "requirement_id": "REQ-TEST-001",
                        "acceptance_criteria": [
                            {"ac_id": "AC-001.1", "task_id": "Task 1", "verification_check_id": "check1", "test_oracle": "TEST-001"}
                        ]
                    }
                ]
            }))
            pol = root / "policies"
            pol.mkdir()
            (pol / "checks.json").write_text(json.dumps({"checks": [{"id": "check1"}]}))

            errs = validate_all_change_specs(root)
            self.assertTrue(any("Duplicate requirement ID 'REQ-TEST-001' within" in e for e in errs))
            self.assertTrue(any("Duplicate acceptance criterion ID 'AC-001.1' within" in e for e in errs))

    def test_spec_validator_detect_duplicate_and_missing_detailed_ac_mapping_TEST_SPEC_005(self):
        from verification.checks.spec_validator import validate_spec_traceability
        with tempfile.TemporaryDirectory() as td:
            req_p = Path(td) / "req.md"
            req_p.write_text("### REQ-TEST-001\n- **AC-001.1:** One\n- **AC-001.2:** Two\n")

            # 1. Duplicate detailed AC mapping
            trace_dup = Path(td) / "trace_dup.json"
            trace_dup.write_text(json.dumps({
                "requirements": [
                    {
                        "requirement_id": "REQ-TEST-001",
                        "acceptance_criteria": [
                            {"ac_id": "AC-001.1", "task_id": "Task 1", "verification_check_id": "check1", "test_oracle": "TEST-001"},
                            {"ac_id": "AC-001.1", "task_id": "Task 1", "verification_check_id": "check1", "test_oracle": "TEST-001"},
                            {"ac_id": "AC-001.2", "task_id": "Task 2", "verification_check_id": "check1", "test_oracle": "TEST-002"}
                        ]
                    }
                ]
            }))
            errs_dup = validate_spec_traceability(req_p, trace_dup)
            self.assertTrue(any("Duplicate detailed AC mapping for 'AC-001.1'" in e for e in errs_dup))

            # 2. Missing detailed mapping (AC-001.2 listed as string without details)
            trace_miss = Path(td) / "trace_miss.json"
            trace_miss.write_text(json.dumps({
                "requirements": [
                    {
                        "requirement_id": "REQ-TEST-001",
                        "acceptance_criteria": [
                            {"ac_id": "AC-001.1", "task_id": "Task 1", "verification_check_id": "check1", "test_oracle": "TEST-001"},
                            "AC-001.2"
                        ]
                    }
                ]
            }))
            errs_miss = validate_spec_traceability(req_p, trace_miss)
            self.assertTrue(any("Missing detailed traceability mapping for acceptance criterion 'AC-001.2'" in e for e in errs_miss))

    def test_spec_validator_multi_feature_fct001_synthetic_valid_TEST_SPEC_006(self):
        from verification.checks.spec_validator import validate_all_change_specs
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            pol = root / "policies"
            pol.mkdir()
            (pol / "checks.json").write_text(json.dumps({"checks": [{"id": "check-shared"}, {"id": "check-fct1"}]}))

            specs_dir = root / "specs" / "changes"

            # Feature 0
            fct0 = specs_dir / "FCT-000"
            fct0.mkdir(parents=True)
            (fct0 / "requirements.md").write_text("### REQ-FCT0-001\n- **AC-000.1:** Do FCT0\n")
            (fct0 / "tasks.md").write_text("### Task 1: Task for FCT0\n")
            (fct0 / "validation.md").write_text("`TEST-FCT0-001`: Oracle\n")
            (fct0 / "traceability.json").write_text(json.dumps({
                "requirements": [
                    {
                        "requirement_id": "REQ-FCT0-001",
                        "acceptance_criteria": [
                            {"ac_id": "AC-000.1", "task_id": "Task 1", "verification_check_id": "check-shared", "test_oracle": "TEST-FCT0-001"}
                        ]
                    }
                ]
            }))

            # Feature 1 (Next planned feature)
            fct1 = specs_dir / "FCT-001"
            fct1.mkdir(parents=True)
            (fct1 / "requirements.md").write_text("### REQ-FCT1-001\n- **AC-001.1:** Do FCT1\n")
            (fct1 / "tasks.md").write_text("### Task 1: Task for FCT1\n")
            (fct1 / "validation.md").write_text("`TEST-FCT1-001`: Oracle\n")
            (fct1 / "traceability.json").write_text(json.dumps({
                "requirements": [
                    {
                        "requirement_id": "REQ-FCT1-001",
                        "acceptance_criteria": [
                            {"ac_id": "AC-001.1", "task_id": "Task 1", "verification_check_id": "check-fct1", "test_oracle": "TEST-FCT1-001"}
                        ]
                    }
                ]
            }))

            errs = validate_all_change_specs(root)
            self.assertEqual(len(errs), 0, errs)

    def test_spec_validator_rejects_unknown_checks_TEST_TRUST_001(self):
        from verification.checks.spec_validator import validate_all_change_specs
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            pol = root / "policies"
            pol.mkdir()
            (pol / "checks.json").write_text(json.dumps({"checks": [{"id": "approved-check"}]}))

            specs_dir = root / "specs" / "changes"
            fct = specs_dir / "FCT-001"
            fct.mkdir(parents=True)
            (fct / "requirements.md").write_text("### REQ-FCT1-001\n- **AC-001.1:** Do FCT1\n")
            (fct / "tasks.md").write_text("### Task 1: Task for FCT1\n")
            (fct / "validation.md").write_text("`TEST-FCT1-001`: Oracle\n")
            (fct / "traceability.json").write_text(json.dumps({
                "requirements": [
                    {
                        "requirement_id": "REQ-FCT1-001",
                        "acceptance_criteria": [
                            {"ac_id": "AC-001.1", "task_id": "Task 1", "verification_check_id": "unknown-nonexistent-check", "test_oracle": "TEST-FCT1-001", "status": "PLANNED"}
                        ]
                    }
                ]
            }))

            errs = validate_all_change_specs(root)
            self.assertTrue(any("Unknown verification_check_id 'unknown-nonexistent-check'" in e for e in errs))

    def test_spec_validator_rejects_arbitrary_json_in_policies_TEST_TRUST_002(self):
        from verification.checks.spec_validator import validate_all_change_specs
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            pol = root / "policies"
            pol.mkdir()
            (pol / "checks.json").write_text(json.dumps({"checks": [{"id": "approved-check"}]}))
            # Arbitrary JSON file in policies must NOT establish check identities
            (pol / "arbitrary_custom_checks.json").write_text(json.dumps({"checks": [{"id": "arbitrary-rogue-check"}]}))

            specs_dir = root / "specs" / "changes"
            fct = specs_dir / "FCT-001"
            fct.mkdir(parents=True)
            (fct / "requirements.md").write_text("### REQ-FCT1-001\n- **AC-001.1:** Do FCT1\n")
            (fct / "tasks.md").write_text("### Task 1: Task for FCT1\n")
            (fct / "validation.md").write_text("`TEST-FCT1-001`: Oracle\n")
            (fct / "traceability.json").write_text(json.dumps({
                "requirements": [
                    {
                        "requirement_id": "REQ-FCT1-001",
                        "acceptance_criteria": [
                            {"ac_id": "AC-001.1", "task_id": "Task 1", "verification_check_id": "arbitrary-rogue-check", "test_oracle": "TEST-FCT1-001", "status": "PLANNED"}
                        ]
                    }
                ]
            }))

            errs = validate_all_change_specs(root)
            self.assertTrue(any("Unknown verification_check_id 'arbitrary-rogue-check'" in e for e in errs))

    def test_spec_validator_accepts_planned_references_to_valid_fct001_proposal_TEST_TRUST_003(self):
        from verification.checks.spec_validator import validate_all_change_specs
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            pol = root / "policies"
            pol.mkdir()
            (pol / "checks.json").write_text(json.dumps({"checks": [{"id": "approved-check"}]}))
            # Valid milestone-scoped proposal for FCT-001
            (pol / "fct001_checks_proposal.json").write_text(json.dumps({
                "milestone": "FCT-001",
                "status": "PROPOSED",
                "checks": [{"id": "proposed-android-check"}]
            }))

            specs_dir = root / "specs" / "changes"
            fct = specs_dir / "FCT-001"
            fct.mkdir(parents=True)
            (fct / "requirements.md").write_text("### REQ-FCT1-001\n- **AC-001.1:** Do FCT1\n")
            (fct / "tasks.md").write_text("### Task 1: Task for FCT1\n")
            (fct / "validation.md").write_text("`TEST-FCT1-001`: Oracle\n")
            (fct / "traceability.json").write_text(json.dumps({
                "requirements": [
                    {
                        "requirement_id": "REQ-FCT1-001",
                        "acceptance_criteria": [
                            {"ac_id": "AC-001.1", "task_id": "Task 1", "verification_check_id": "proposed-android-check", "test_oracle": "TEST-FCT1-001", "status": "PLANNED"}
                        ]
                    }
                ]
            }))

            errs = validate_all_change_specs(root)
            self.assertEqual(len(errs), 0, errs)

    def test_spec_validator_rejects_proposed_check_masquerading_as_verified_evidence_TEST_TRUST_004(self):
        from verification.checks.spec_validator import validate_all_change_specs
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            pol = root / "policies"
            pol.mkdir()
            (pol / "checks.json").write_text(json.dumps({"checks": [{"id": "approved-check"}]}))
            (pol / "fct001_checks_proposal.json").write_text(json.dumps({
                "milestone": "FCT-001",
                "status": "PROPOSED",
                "checks": [{"id": "proposed-android-check"}]
            }))

            specs_dir = root / "specs" / "changes"
            fct = specs_dir / "FCT-001"
            fct.mkdir(parents=True)
            (fct / "requirements.md").write_text("### REQ-FCT1-001\n- **AC-001.1:** Do FCT1\n")
            (fct / "tasks.md").write_text("### Task 1: Task for FCT1\n")
            (fct / "validation.md").write_text("`TEST-FCT1-001`: Oracle\n")
            # AC claims VERIFIED status while pointing to a proposed check
            (fct / "traceability.json").write_text(json.dumps({
                "requirements": [
                    {
                        "requirement_id": "REQ-FCT1-001",
                        "acceptance_criteria": [
                            {"ac_id": "AC-001.1", "task_id": "Task 1", "verification_check_id": "proposed-android-check", "test_oracle": "TEST-FCT1-001", "status": "VERIFIED"}
                        ]
                    }
                ]
            }))

            errs = validate_all_change_specs(root)
            self.assertTrue(any("cannot masquerade as approved execution evidence" in e for e in errs))

    def test_proposal_duplicate_check_id_rejected_TEST_PROP_001(self):
        from verification.checks.spec_validator import validate_all_change_specs
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            pol = root / "policies"
            pol.mkdir()
            (pol / "checks.json").write_text(json.dumps({"checks": [{"id": "approved-check"}]}))
            (pol / "fct001_checks_proposal.json").write_text(json.dumps({
                "milestone": "FCT-001",
                "status": "PROPOSED",
                "checks": [
                    {"id": "duplicate-check", "type": "command", "argv": ["python3", "c1.py"], "timeout_seconds": 30, "required": True},
                    {"id": "duplicate-check", "type": "command", "argv": ["python3", "c2.py"], "timeout_seconds": 30, "required": True}
                ]
            }))
            specs_dir = root / "specs" / "changes"
            fct = specs_dir / "FCT-001"
            fct.mkdir(parents=True)
            (fct / "requirements.md").write_text("### REQ-FCT1-001\n- **AC-001.1:** Do FCT1\n")
            (fct / "tasks.md").write_text("### Task 1: Task for FCT1\n")
            (fct / "validation.md").write_text("`TEST-FCT1-001`: Oracle\n")
            (fct / "traceability.json").write_text(json.dumps({
                "requirements": [{"requirement_id": "REQ-FCT1-001", "acceptance_criteria": [{"ac_id": "AC-001.1", "task_id": "Task 1", "verification_check_id": "duplicate-check", "test_oracle": "TEST-FCT1-001", "status": "PLANNED"}]}]
            }))
            errs = validate_all_change_specs(root)
            self.assertTrue(any("Duplicate check ID 'duplicate-check'" in e for e in errs))

    def test_proposal_invalid_fields_rejected_TEST_PROP_002(self):
        from verification.checks.spec_validator import validate_all_change_specs
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            pol = root / "policies"
            pol.mkdir()
            (pol / "checks.json").write_text(json.dumps({"checks": [{"id": "approved-check"}]}))
            (pol / "fct001_checks_proposal.json").write_text(json.dumps({
                "milestone": "FCT-001",
                "status": "PROPOSED",
                "checks": [
                    {"id": "bad-check-1", "type": "unsupported_type", "argv": ["cmd"], "timeout_seconds": 30, "required": True},
                    {"id": "bad-check-2", "type": "command", "argv": [], "timeout_seconds": 30, "required": True},
                    {"id": "bad-check-3", "type": "command", "argv": ["cmd"], "timeout_seconds": -5, "required": True},
                    {"id": "bad-check-4", "type": "command", "argv": ["cmd"], "timeout_seconds": 30, "required": "yes"}
                ]
            }))
            specs_dir = root / "specs" / "changes"
            fct = specs_dir / "FCT-001"
            fct.mkdir(parents=True)
            (fct / "requirements.md").write_text("### REQ-FCT1-001\n- **AC-001.1:** Do FCT1\n")
            (fct / "tasks.md").write_text("### Task 1: Task for FCT1\n")
            (fct / "validation.md").write_text("`TEST-FCT1-001`: Oracle\n")
            (fct / "traceability.json").write_text(json.dumps({
                "requirements": [{"requirement_id": "REQ-FCT1-001", "acceptance_criteria": [{"ac_id": "AC-001.1", "task_id": "Task 1", "verification_check_id": "bad-check-1", "test_oracle": "TEST-FCT1-001", "status": "PLANNED"}]}]
            }))
            errs = validate_all_change_specs(root)
            self.assertTrue(any("invalid 'type'" in e for e in errs))
            self.assertTrue(any("invalid 'argv'" in e for e in errs))
            self.assertTrue(any("invalid 'timeout_seconds'" in e for e in errs))
            self.assertTrue(any("invalid 'required' flag" in e for e in errs))

if __name__ == "__main__":
    unittest.main()
