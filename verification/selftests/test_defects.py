import unittest
from pathlib import Path
from verification.checks.spec_validator import (
    validate_defects_registry,
    validate_antipattern_registry
)

class TestDefectGovernance(unittest.TestCase):
    def test_defect_schema_conformance_TEST_DEF_001(self):
        ok, errs = validate_defects_registry(Path("verification/defects.jsonl"))
        self.assertTrue(ok, errs)

    def test_antipattern_schema_and_dual_samples_TEST_ANTI_001(self):
        ok, errs = validate_antipattern_registry(Path("verification/antipatterns.json"))
        self.assertTrue(ok, errs)

if __name__ == "__main__":
    unittest.main()
