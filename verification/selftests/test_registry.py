import unittest
import json
import tempfile
from pathlib import Path
from verification.checks.registry import load_and_validate_registry, CheckRegistry, CheckDefinition, validate_check_selection

class TestRegistryValidation(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory()
        self.dir = Path(self.td.name)

    def tearDown(self):
        self.td.cleanup()

    def test_valid_registry_TEST_REG_001(self):
        p = self.dir / "valid.json"
        p.write_text(json.dumps({
            "checks": [
                {
                    "id": "c1",
                    "argv": ["python3", "-c", "exit(0)"],
                    "required": True,
                    "timeout_seconds": 10,
                    "type": "command",
                    "description": "test check"
                }
            ]
        }))
        reg = load_and_validate_registry(str(p))
        self.assertEqual(len(reg.checks), 1)
        self.assertEqual(reg.checks[0].id, "c1")
        self.assertTrue(reg.policy_sha256)
        self.assertEqual(len(reg.policy_sha256), 64)

    def test_empty_registry_TEST_REG_002(self):
        p = self.dir / "empty.json"
        p.write_text(json.dumps({"checks": []}))
        with self.assertRaises(ValueError) as ctx:
            load_and_validate_registry(str(p))
        self.assertIn("Registry contains zero checks", str(ctx.exception))

    def test_malformed_json_TEST_REG_003(self):
        p = self.dir / "bad.json"
        p.write_text("{ incomplete...")
        with self.assertRaises(ValueError) as ctx:
            load_and_validate_registry(str(p))
        self.assertIn("Invalid JSON syntax", str(ctx.exception))

    def test_duplicate_check_ids_TEST_REG_004(self):
        p = self.dir / "dup.json"
        p.write_text(json.dumps({"checks": [
            {"id": "c1", "argv": ["python3"], "required": True, "timeout_seconds": 5},
            {"id": "c1", "argv": ["python3"], "required": False, "timeout_seconds": 5}
        ]}))
        with self.assertRaises(ValueError) as ctx:
            load_and_validate_registry(str(p))
        self.assertIn("Duplicate check ID", str(ctx.exception))

    def test_invalid_timeout_TEST_REG_005(self):
        p = self.dir / "bad_time.json"
        p.write_text(json.dumps({"checks": [{"id": "c1", "argv": ["python3"], "required": True, "timeout_seconds": 0}]}))
        with self.assertRaises(ValueError) as ctx:
            load_and_validate_registry(str(p))
        self.assertIn("timeout_seconds must be a positive integer", str(ctx.exception))

        p2 = self.dir / "bad_time2.json"
        p2.write_text(json.dumps({"checks": [{"id": "c1", "argv": ["python3"], "required": True, "timeout_seconds": -5}]}))
        with self.assertRaises(ValueError) as ctx2:
            load_and_validate_registry(str(p2))
        self.assertIn("timeout_seconds must be a positive integer", str(ctx2.exception))

    def test_invalid_argv_TEST_REG_006(self):
        p = self.dir / "bad_argv.json"
        p.write_text(json.dumps({"checks": [{"id": "c1", "argv": "not-an-array", "required": True, "timeout_seconds": 5}]}))
        with self.assertRaises(ValueError) as ctx:
            load_and_validate_registry(str(p))
        self.assertIn("argv must be a non-empty array", str(ctx.exception))

        p2 = self.dir / "empty_argv.json"
        p2.write_text(json.dumps({"checks": [{"id": "c1", "argv": [], "required": True, "timeout_seconds": 5}]}))
        with self.assertRaises(ValueError) as ctx2:
            load_and_validate_registry(str(p2))
        self.assertIn("argv must be a non-empty array", str(ctx2.exception))

    def test_missing_required_property_TEST_REG_007(self):
        p = self.dir / "missing.json"
        p.write_text(json.dumps({"checks": [{"id": "c1", "timeout_seconds": 5}]}))
        with self.assertRaises(ValueError) as ctx:
            load_and_validate_registry(str(p))
        self.assertIn("missing required property", str(ctx.exception))

    def test_all_optional_registry_rejected_TEST_REG_008(self):
        p = self.dir / "all_optional.json"
        p.write_text(json.dumps({"checks": [
            {"id": "opt1", "argv": ["python3"], "required": False, "timeout_seconds": 5},
            {"id": "opt2", "argv": ["python3"], "required": False, "timeout_seconds": 5}
        ]}))
        with self.assertRaises(ValueError) as ctx:
            load_and_validate_registry(str(p))
        self.assertIn("Registry contains zero required checks", str(ctx.exception))

    def test_unknown_check_id_rejected_TEST_REG_009(self):
        reg = CheckRegistry(checks=[CheckDefinition(id="c1", argv=["python3"], required=True, timeout_seconds=5)], policy_sha256="abc")
        with self.assertRaises(KeyError) as ctx:
            validate_check_selection(reg, "non_existent_check")
        self.assertIn("Unknown check ID: non_existent_check", str(ctx.exception))

if __name__ == "__main__":
    unittest.main()
