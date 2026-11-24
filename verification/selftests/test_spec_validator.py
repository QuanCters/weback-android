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
            (spec1 / "traceability.json").write_text(json.dumps({
                "requirements": [{"requirement_id": "REQ-COMM-001", "acceptance_criteria": [{"ac_id": "AC-001.1", "task_id": "Task 1", "verification_check_id": "check1", "test_oracle": "TEST-1"}]}]
            }))
            (spec2 / "requirements.md").write_text("### REQ-COMM-001\n- **AC-001.1:** Do it again\n")
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


if __name__ == "__main__":
    unittest.main()
