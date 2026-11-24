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

if __name__ == "__main__":
    unittest.main()
