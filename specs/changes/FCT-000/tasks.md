# FCT-000 Implementation Plan — Minimum Viable Verification Harness

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Author the minimal, standalone Python 3 verification harness, declarative check registry, dual-lane runner, structured test adapter, isolated selftest suite, defect-to-regression framework, and CI pipeline for Milestone FCT-000.

**Architecture:** A lightweight, dependency-free Python 3 standard library application partitioned into clean execution modules (`verification/checks/`), JSON schemas (`verification/schemas/`), and an isolated testbed suite (`verification/selftests/`). Employs POSIX process group management for clean subprocess timeout handling, structured test reporting adapter, cryptographic hashing for report provenance, base commit policy diff detection, bootstrap policy approval anchoring, and strict git status checks for clean checkout enforcement.

**Tech Stack:** Python 3.12+ (standard library only: `subprocess`, `hashlib`, `json`, `os`, `sys`, `time`, `signal`, `tempfile`, `unittest`, `re`, `pathlib`), Git 2.43+, GitHub Actions.

**Spec:** `specs/changes/FCT-000/requirements.md`, `specs/changes/FCT-000/design.md`, `specs/changes/FCT-000/validation.md`.

## Global Constraints
- Pure Python 3 standard library only; zero third-party packages in `verification/`.
- Zero Android SDK or Gradle wrapper dependencies in FCT-000.
- All commands executed via `subprocess.Popen` / `run` with `shell=False`.
- Subprocesses spawned in separate process groups (`start_new_session=True`), process group tracked via `pgid = proc.pid`, and cleaned up with `os.killpg` (SIGTERM with 0.5s grace, then SIGKILL, bounded to 2.0s cleanup).
- Candidate verification strictly fails if git checkout is not clean before or after execution, including assume-unchanged or skip-worktree flags.
- Candidate verification strictly requires canonical policy path (`policies/checks.json`) and prohibits targeted check execution (`--check`).
- Evidence validation strictly recomputes overall verdict from actual check results, rejecting forged PASS declarations.

## Review Focus
- Lingering background processes on timeout: test verifies deep grandchild processes and SIGTERM-resistant processes are terminated cleanly via `os.killpg(pgid, SIGKILL)` and recorded PID raises `ProcessLookupError`.
- Tampering with evidence logs: test verifies altering one byte of a `.log` file invalidates the report SHA-256 check.
- False pass on missing checks: test verifies omitting a required check fails the overall verdict.
- False pass on zero discovered tests: test verifies that test runners returning 0 tests are classified as `FAIL`.
- Mutation of repository during run: test verifies creating an untracked file causes post-run cleanliness abort.
- Trust boundary distinction: candidate evidence audit verifies tested SHA, base SHA, policy digest against bootstrap approval anchor, and rejects forged verdicts.

---

### Task 1: Check Registry Schemas & Registry Validator

**Files:**
- Create: `verification/schemas/checks.schema.json`
- Create: `verification/checks/registry.py`
- Test: `verification/selftests/test_registry.py`
- Fixtures: `verification/fixtures/valid_checks.json`, `verification/fixtures/invalid_checks.json`, `verification/fixtures/duplicate_checks.json`, `verification/fixtures/empty_checks.json`, `verification/fixtures/all_optional_checks.json`

**Interfaces:**
- Consumes: JSON file path (`policies/checks.json`).
- Produces: `CheckDefinition` dataclass (`id: str`, `argv: list[str]`, `required: bool`, `timeout_seconds: int`, `type: str = "command"`, `description: str = ""`), `CheckRegistry` dataclass containing `checks: list[CheckDefinition]`, `policy_sha256: str`, and method `load_and_validate_registry(path: str) -> CheckRegistry`.

- [ ] **Step 1: Write failing tests for Category 1 Registry Validation (`TEST-REG-001` through `TEST-REG-009`)**
```python
# verification/selftests/test_registry.py
import unittest
import json
import tempfile
from pathlib import Path
from verification.checks.registry import load_and_validate_registry, CheckRegistry, validate_check_selection

class TestRegistryValidation(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory()
        self.dir = Path(self.td.name)

    def tearDown(self):
        self.td.cleanup()

    def test_valid_registry_TEST_REG_001(self):
        p = self.dir / "valid.json"
        p.write_text(json.dumps({"checks": [{"id": "c1", "argv": ["python3", "-c", "exit(0)"], "required": True, "timeout_seconds": 10}]}))
        reg = load_and_validate_registry(str(p))
        self.assertEqual(len(reg.checks), 1)
        self.assertTrue(reg.policy_sha256)

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

    def test_invalid_argv_TEST_REG_006(self):
        p = self.dir / "bad_argv.json"
        p.write_text(json.dumps({"checks": [{"id": "c1", "argv": "not-an-array", "required": True, "timeout_seconds": 5}]}))
        with self.assertRaises(ValueError) as ctx:
            load_and_validate_registry(str(p))
        self.assertIn("argv must be a non-empty array", str(ctx.exception))

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
```

- [ ] **Step 2: Run test to verify it fails**
Run: `python3 -m unittest verification/selftests/test_registry.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'verification.checks.registry'`

- [ ] **Step 3: Implement `registry.py` and `checks.schema.json`**
Implement standard-library structural validator checking keys, non-empty list, positive timeout, array of strings for `argv`, at least one check with `required: true`, and unique check IDs. Implement `validate_check_selection`. Compute SHA-256 of raw registry bytes.

- [ ] **Step 4: Run test to verify it passes**
Run: `python3 -m unittest verification/selftests/test_registry.py -v`
Expected: PASS with 9/9 tests passing.

- [ ] **Step 5: Commit**
```bash
git add verification/schemas/checks.schema.json verification/checks/registry.py verification/selftests/test_registry.py
git commit -m "feat(verification): implement declarative check registry validator and schemas (TEST-REG-001..009)"
```

---

### Task 2: Subprocess Execution Engine, Process-Group Timeout Manager & Test Adapter

**Files:**
- Create: `verification/checks/runner.py`
- Create: `verification/checks/test_adapter.py`
- Test: `verification/selftests/test_runner.py`
- Test: `verification/selftests/test_test_adapter.py`

**Interfaces:**
- Consumes: `CheckDefinition` (argv, timeout_seconds, cwd, check_type).
- Produces: `CheckResult` dataclass (`id: str`, `status: str`, `exit_code: int`, `duration_seconds: float`, `output: bytes`, `test_summary: Optional[dict[str, int]] = None`, `cleanup_failed: bool = False`).
- Produces: `StructuredTestResult` and `run_unittest_suite(test_module_or_pattern: str) -> StructuredTestResult`.

- [ ] **Step 1: Write failing tests for Category 2 Runner & Category 4 Inventory (`TEST-RUN-001`..`005`, `TEST-RUN-011`..`014`, `TEST-INV-001`..`007`)**
```python
# verification/selftests/test_runner.py
import unittest
import time
import os
import signal
import tempfile
from pathlib import Path
from verification.checks.registry import CheckDefinition
from verification.checks.runner import execute_check

class TestRunnerExecution(unittest.TestCase):
    def test_successful_command_TEST_RUN_001(self):
        c = CheckDefinition(id="c1", argv=["python3", "-c", "print('hello'); exit(0)"], required=True, timeout_seconds=5)
        res = execute_check(c)
        self.assertEqual(res.exit_code, 0)
        self.assertEqual(res.status, "PASS")
        self.assertIn(b"hello", res.output)

    def test_intentional_required_failure_TEST_RUN_002(self):
        c = CheckDefinition(id="c2", argv=["python3", "-c", "exit(1)"], required=True, timeout_seconds=5)
        res = execute_check(c)
        self.assertEqual(res.exit_code, 1)
        self.assertEqual(res.status, "FAIL")

    def test_optional_check_failure_recorded_TEST_RUN_003(self):
        c = CheckDefinition(id="c3", argv=["python3", "-c", "exit(1)"], required=False, timeout_seconds=5)
        res = execute_check(c)
        self.assertEqual(res.exit_code, 1)
        self.assertEqual(res.status, "FAIL")

    def test_missing_executable_error_127_TEST_RUN_004(self):
        c = CheckDefinition(id="c4", argv=["non_existent_binary_xyz123"], required=True, timeout_seconds=5)
        res = execute_check(c)
        self.assertEqual(res.exit_code, 127)
        self.assertEqual(res.status, "ERROR")
        self.assertIn(b"[EXECUTABLE NOT FOUND", res.output)

    def test_permission_denied_error_126_TEST_RUN_011(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "unexecutable.sh"
            p.write_text("#!/bin/sh\nexit 0")
            p.chmod(0o000)
            c = CheckDefinition(id="c_perm", argv=[str(p)], required=True, timeout_seconds=5)
            res = execute_check(c)
            self.assertEqual(res.exit_code, 126)
            self.assertEqual(res.status, "ERROR")
            self.assertIn(b"[PERMISSION DENIED", res.output)

    def test_timeout_and_process_cleanup_TEST_RUN_005(self):
        with tempfile.TemporaryDirectory() as td:
            pid_file = Path(td) / "child.pid"
            script = f"import time, subprocess; p = subprocess.Popen(['sleep', '100']); open('{pid_file}', 'w').write(str(p.pid)); time.sleep(10)"
            c = CheckDefinition(id="c5", argv=["python3", "-c", script], required=True, timeout_seconds=1)
            res = execute_check(c)
            self.assertEqual(res.status, "TIMEOUT")
            self.assertEqual(res.exit_code, 124)
            # Verify grandchild is killed
            time.sleep(0.5)
            grandchild_pid = int(pid_file.read_text())
            with self.assertRaises(ProcessLookupError):
                os.kill(grandchild_pid, 0)

    def test_parent_exit_with_surviving_descendants_TEST_RUN_012(self):
        with tempfile.TemporaryDirectory() as td:
            pid_file = Path(td) / "descendant.pid"
            script = f"import time, subprocess; p = subprocess.Popen(['sleep', '50']); open('{pid_file}', 'w').write(str(p.pid)); exit(0)"
            c = CheckDefinition(id="c_desc", argv=["python3", "-c", script], required=True, timeout_seconds=5)
            res = execute_check(c)
            self.assertEqual(res.status, "PASS")
            self.assertEqual(res.exit_code, 0)
            time.sleep(0.5)
            pid = int(pid_file.read_text())
            # Clean up lingering group process if runner cleans group on exit
            try:
                os.kill(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass

    def test_sigterm_resistant_child_escalation_TEST_RUN_013(self):
        with tempfile.TemporaryDirectory() as td:
            script = "import signal, time; signal.signal(signal.SIGTERM, signal.SIG_IGN); open('ready.tmp', 'w').write('ok'); time.sleep(10)"
            c = CheckDefinition(id="c_sig", argv=["python3", "-c", script], required=True, timeout_seconds=1, cwd=Path(td))
            t0 = time.time()
            res = execute_check(c)
            elapsed = time.time() - t0
            self.assertEqual(res.status, "TIMEOUT")
            self.assertEqual(res.exit_code, 124)
            self.assertLess(elapsed, 4.0)  # Bounded cleanup

    def test_large_output_buffer_TEST_RUN_014(self):
        script = "import sys; sys.stdout.write('A' * (5 * 1024 * 1024)); sys.stdout.flush()"
        c = CheckDefinition(id="c_large", argv=["python3", "-c", script], required=True, timeout_seconds=10)
        res = execute_check(c)
        self.assertEqual(res.status, "PASS")
        self.assertEqual(res.exit_code, 0)
        self.assertEqual(len(res.output), 5 * 1024 * 1024)
```

```python
# verification/selftests/test_test_adapter.py
import unittest
import json
import tempfile
from pathlib import Path
from verification.checks.test_adapter import run_synthetic_suite, parse_test_summary

class TestTestAdapter(unittest.TestCase):
    def test_zero_discovered_tests_TEST_INV_001(self):
        summary = {"total": 0, "passed": 0, "failures": 0, "errors": 0, "skipped": 0}
        status, diagnostic = parse_test_summary(summary)
        self.assertEqual(status, "FAIL")
        self.assertIn("Zero tests discovered", diagnostic)

    def test_disallowed_skipped_tests_TEST_INV_002(self):
        summary = {"total": 5, "passed": 4, "failures": 0, "errors": 0, "skipped": 1}
        status, diagnostic = parse_test_summary(summary, allow_skipped=False)
        self.assertEqual(status, "FAIL")
        self.assertIn("Disallowed skipped tests", diagnostic)

    def test_accurate_count_extraction_TEST_INV_003(self):
        # 10 executed tests: 8 pass, 2 fail
        suite_code = """
import unittest
class SyntheticSuite(unittest.TestCase):
    def test_p1(self): pass
    def test_p2(self): pass
    def test_p3(self): pass
    def test_p4(self): pass
    def test_p5(self): pass
    def test_p6(self): pass
    def test_p7(self): pass
    def test_p8(self): pass
    def test_f1(self): self.fail("fail 1")
    def test_f2(self): self.fail("fail 2")
"""
        with tempfile.TemporaryDirectory() as td:
            f = Path(td) / "test_synth.py"
            f.write_text(suite_code)
            res = run_synthetic_suite(f)
            self.assertEqual(res.total, 10)
            self.assertEqual(res.passed, 8)
            self.assertEqual(res.failures, 2)
            self.assertEqual(res.errors, 0)
            self.assertEqual(res.skipped, 0)
            self.assertEqual(res.exit_code, 1)

    def test_single_passing_test_TEST_INV_004(self):
        suite_code = "import unittest\nclass S(unittest.TestCase):\n    def test_1(self): pass"
        with tempfile.TemporaryDirectory() as td:
            f = Path(td) / "test_one.py"
            f.write_text(suite_code)
            res = run_synthetic_suite(f)
            self.assertEqual(res.total, 1)
            self.assertEqual(res.passed, 1)
            self.assertEqual(res.exit_code, 0)

    def test_assertion_failure_TEST_INV_005(self):
        suite_code = "import unittest\nclass S(unittest.TestCase):\n    def test_fail(self): self.assertEqual(1, 2)"
        with tempfile.TemporaryDirectory() as td:
            f = Path(td) / "test_fail.py"
            f.write_text(suite_code)
            res = run_synthetic_suite(f)
            self.assertEqual(res.failures, 1)
            self.assertEqual(res.exit_code, 1)

    def test_runtime_error_TEST_INV_006(self):
        suite_code = "import unittest\nclass S(unittest.TestCase):\n    def test_err(self): 1/0"
        with tempfile.TemporaryDirectory() as td:
            f = Path(td) / "test_err.py"
            f.write_text(suite_code)
            res = run_synthetic_suite(f)
            self.assertEqual(res.errors, 1)
            self.assertEqual(res.exit_code, 1)

    def test_malformed_test_summary_TEST_INV_007(self):
        with self.assertRaises(ValueError):
            parse_test_summary({"malformed": True})
```

- [ ] **Step 2: Run tests to verify they fail**
Run: `python3 -m unittest verification/selftests/test_runner.py verification/selftests/test_test_adapter.py -v`
Expected: FAIL with `ModuleNotFoundError`

- [ ] **Step 3: Implement `runner.py` and `test_adapter.py`**
Implement `execute_check` using `subprocess.Popen` with `start_new_session=True`. Track `pgid = proc.pid`. Catch `FileNotFoundError` (exit code 127), `PermissionError` (exit code 126), and `OSError`.
On `TimeoutExpired`, send `SIGTERM` to `pgid`, wait 0.5s grace, inspect process group, escalate to `SIGKILL`. Drain pipes with `communicate(timeout=1.0)` and preserve partial output. Bounded cleanup duration max 2.0s; record `cleanup_failed: true` if kill fails.
Implement `test_adapter.py` using Python `unittest.TextTestRunner` with custom `TestResult` capturing exact counts: `total`, `passed`, `failures`, `errors`, `skipped`.

- [ ] **Step 4: Run tests to verify they pass**
Run: `python3 -m unittest verification/selftests/test_runner.py verification/selftests/test_test_adapter.py -v`
Expected: PASS with all tests passing.

- [ ] **Step 5: Commit**
```bash
git add verification/checks/runner.py verification/checks/test_adapter.py verification/selftests/test_runner.py verification/selftests/test_test_adapter.py
git commit -m "feat(verification): implement subprocess execution engine, process-group timeout manager, and structured test adapter (TEST-RUN-001..005, TEST-RUN-011..014, TEST-INV-001..007)"
```

---

### Task 3: Evidence Collector, Cryptographic Hasher, Report Generator & Trust Validator

**Files:**
- Create: `verification/schemas/report.schema.json`
- Create: `verification/checks/evidence.py`
- Test: `verification/selftests/test_evidence.py`

**Interfaces:**
- Consumes: `list[CheckResult]`, `tested_sha: str`, `base_sha: str`, `policy_sha256: str`, `policy_modified_from_base: bool`, `lane: str`, `output_dir: Path`.
- Produces: Writes `report.json` and `<check_id>.log` files; provides `verify_evidence_report(report_path: Path, expected_sha: Optional[str] = None, expected_base_sha: Optional[str] = None, expected_policy_sha256: Optional[str] = None, required_check_ids: Optional[list[str]] = None, require_candidate: bool = False, bootstrap_approval_path: Optional[Path] = None) -> tuple[bool, list[str]]`.

- [ ] **Step 1: Write failing tests for Category 3 Evidence Integrity (`TEST-EVI-001` through `TEST-EVI-012`)**
```python
# verification/selftests/test_evidence.py
import unittest
import tempfile
import json
from pathlib import Path
from verification.checks.runner import CheckResult
from verification.checks.evidence import generate_evidence_report, verify_evidence_report

class TestEvidence(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory()
        self.out_dir = Path(self.td.name)

    def tearDown(self):
        self.td.cleanup()

    def test_valid_evidence_audit_TEST_EVI_001(self):
        results = [CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n")]
        report_path = generate_evidence_report(results, tested_sha="a"*40, base_sha="0"*40, policy_sha256="b"*64, policy_modified_from_base=False, lane="candidate", output_dir=self.out_dir)
        ok, errs = verify_evidence_report(report_path, expected_sha="a"*40, expected_base_sha="0"*40, expected_policy_sha256="b"*64, required_check_ids=["c1"])
        self.assertTrue(ok, errs)

    def test_missing_required_check_TEST_EVI_002(self):
        results = []  # Missing required c1
        report_path = generate_evidence_report(results, tested_sha="a"*40, base_sha="0"*40, policy_sha256="b"*64, policy_modified_from_base=False, lane="candidate", output_dir=self.out_dir)
        ok, errs = verify_evidence_report(report_path, expected_sha="a"*40, expected_policy_sha256="b"*64, required_check_ids=["c1"])
        self.assertFalse(ok)
        self.assertIn("Missing result for required check: c1", "".join(errs))

    def test_duplicate_check_id_TEST_EVI_003(self):
        results = [
            CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n"),
            CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n")
        ]
        report_path = generate_evidence_report(results, tested_sha="a"*40, base_sha="0"*40, policy_sha256="b"*64, policy_modified_from_base=False, lane="candidate", output_dir=self.out_dir)
        ok, errs = verify_evidence_report(report_path, expected_sha="a"*40, expected_policy_sha256="b"*64)
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
        report_path = generate_evidence_report(results, tested_sha="a"*40, base_sha="0"*40, policy_sha256="b"*64, policy_modified_from_base=False, lane="candidate", output_dir=self.out_dir)
        ok, errs = verify_evidence_report(report_path, expected_sha="c"*40, expected_policy_sha256="b"*64)
        self.assertFalse(ok)
        self.assertIn("Report tested_sha mismatch", "".join(errs))

    def test_policy_digest_mismatch_TEST_EVI_006(self):
        results = [CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n")]
        report_path = generate_evidence_report(results, tested_sha="a"*40, base_sha="0"*40, policy_sha256="b"*64, policy_modified_from_base=False, lane="candidate", output_dir=self.out_dir)
        ok, errs = verify_evidence_report(report_path, expected_sha="a"*40, expected_policy_sha256="d"*64)
        self.assertFalse(ok)
        self.assertIn("Policy hash mismatch", "".join(errs))

    def test_log_tampering_detected_TEST_EVI_007(self):
        results = [CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"original\n")]
        report_path = generate_evidence_report(results, tested_sha="a"*40, base_sha="0"*40, policy_sha256="b"*64, policy_modified_from_base=False, lane="candidate", output_dir=self.out_dir)
        # Tamper log
        (self.out_dir / "c1.log").write_text("tampered")
        ok, errs = verify_evidence_report(report_path, expected_sha="a"*40, expected_policy_sha256="b"*64)
        self.assertFalse(ok)
        self.assertIn("Log integrity violation", "".join(errs))

    def test_missing_raw_log_TEST_EVI_008(self):
        results = [CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n")]
        report_path = generate_evidence_report(results, tested_sha="a"*40, base_sha="0"*40, policy_sha256="b"*64, policy_modified_from_base=False, lane="candidate", output_dir=self.out_dir)
        (self.out_dir / "c1.log").unlink()
        ok, errs = verify_evidence_report(report_path, expected_sha="a"*40, expected_policy_sha256="b"*64)
        self.assertFalse(ok)
        self.assertIn("Referenced log file missing", "".join(errs))

    def test_rejection_of_fast_lane_report_TEST_EVI_009(self):
        results = [CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n")]
        report_path = generate_evidence_report(results, tested_sha="a"*40, base_sha="0"*40, policy_sha256="b"*64, policy_modified_from_base=False, lane="fast", output_dir=self.out_dir)
        ok, errs = verify_evidence_report(report_path, expected_sha="a"*40, expected_policy_sha256="b"*64, require_candidate=True)
        self.assertFalse(ok)
        self.assertIn("Fast lane report cannot be used for candidate gate", "".join(errs))

    def test_forged_pass_verdict_rejection_TEST_EVI_010(self):
        results = [CheckResult(id="c1", status="FAIL", exit_code=1, duration_seconds=0.1, output=b"failed\n")]
        report_path = generate_evidence_report(results, tested_sha="a"*40, base_sha="0"*40, policy_sha256="b"*64, policy_modified_from_base=False, lane="candidate", output_dir=self.out_dir)
        # Tamper report verdict to PASS
        data = json.loads(report_path.read_text())
        data["verdict"] = "PASS"
        report_path.write_text(json.dumps(data))
        ok, errs = verify_evidence_report(report_path, expected_sha="a"*40, expected_policy_sha256="b"*64, required_check_ids=["c1"])
        self.assertFalse(ok)
        self.assertIn("Forged PASS verdict rejected", "".join(errs))

    def test_inconsistent_status_and_code_TEST_EVI_011(self):
        results = [CheckResult(id="c1", status="PASS", exit_code=1, duration_seconds=0.1, output=b"failed\n")]
        report_path = generate_evidence_report(results, tested_sha="a"*40, base_sha="0"*40, policy_sha256="b"*64, policy_modified_from_base=False, lane="candidate", output_dir=self.out_dir)
        ok, errs = verify_evidence_report(report_path, expected_sha="a"*40, expected_policy_sha256="b"*64)
        self.assertFalse(ok)
        self.assertIn("Inconsistent check status and exit code", "".join(errs))

    def test_missing_bootstrap_anchor_TEST_EVI_012(self):
        results = [CheckResult(id="c1", status="PASS", exit_code=0, duration_seconds=0.1, output=b"ok\n")]
        report_path = generate_evidence_report(results, tested_sha="a"*40, base_sha="0"*40, policy_sha256="b"*64, policy_modified_from_base=True, lane="candidate", output_dir=self.out_dir)
        # Missing bootstrap anchor
        ok, errs = verify_evidence_report(report_path, expected_sha="a"*40, expected_policy_sha256="b"*64, bootstrap_approval_path=self.out_dir / "non_existent.json")
        self.assertFalse(ok)
        self.assertIn("Missing bootstrap policy approval", "".join(errs))
```

- [ ] **Step 2: Run test to verify it fails**
Run: `python3 -m unittest verification/selftests/test_evidence.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'verification.checks.evidence'`

- [ ] **Step 3: Implement `evidence.py` and `report.schema.json`**
Implement evidence generation: generate UUIDv4 `run_id`, write individual `.log` files, compute SHA-256 digests, and write `report.json` with `tested_sha`, `base_sha`, `test_summary`, `policy_modified_from_base`, and check entries.
Implement `verify_evidence_report` auditing structural keys, SHA matches, duplicate check detection, log hash integrity, independent verdict recomputation (rejecting forged PASS), status/code consistency, and bootstrap approval verification.

- [ ] **Step 4: Run test to verify it passes**
Run: `python3 -m unittest verification/selftests/test_evidence.py -v`
Expected: PASS with 12/12 tests passing.

- [ ] **Step 5: Commit**
```bash
git add verification/schemas/report.schema.json verification/checks/evidence.py verification/selftests/test_evidence.py
git commit -m "feat(verification): implement evidence generator, cryptographic hasher, and trust validator (TEST-EVI-001..012)"
```

---

### Task 4: Git Checkout State Auditor, Cleanliness Guard & Policy Diff Inspector

**Files:**
- Create: `verification/checks/git_auditor.py`
- Test: `verification/selftests/test_git_auditor.py`

**Interfaces:**
- Consumes: Target repository directory (`cwd: Path`), `ignored_prefixes: list[str] = ["artifacts/verification/"]`.
- Produces: `is_clean_checkout(repo_dir: Path, ignored_prefixes: list[str]) -> tuple[bool, str]`, `get_head_sha(repo_dir: Path) -> str`, `get_base_sha(repo_dir: Path, base_ref: str = "main") -> str`, `check_policy_modified_from_base(repo_dir: Path, base_ref: str = "main") -> bool`.

- [ ] **Step 1: Write failing tests (`TEST-RUN-006`, `TEST-RUN-007`, `TEST-RUN-008`, `TEST-MUT-003`)**
```python
# verification/selftests/test_git_auditor.py
import unittest
import tempfile
import subprocess
from pathlib import Path
from verification.checks.git_auditor import is_clean_checkout, get_head_sha, check_policy_modified_from_base

class TestGitAuditor(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory()
        self.repo = Path(self.td.name)
        subprocess.run(["git", "init"], cwd=self.repo, check=True, capture_output=True)
        subprocess.run(["git", "config", "user.name", "Test Agent"], cwd=self.repo, check=True)
        subprocess.run(["git", "config", "user.email", "test@weback.local"], cwd=self.repo, check=True)
        subprocess.run(["git", "commit", "--allow-empty", "-m", "initial commit"], cwd=self.repo, check=True, capture_output=True)

    def tearDown(self):
        self.td.cleanup()

    def test_clean_and_dirty_detection_TEST_RUN_006(self):
        clean, _ = is_clean_checkout(self.repo)
        self.assertTrue(clean)
        (self.repo / "dirty.txt").write_text("modified")
        clean, details = is_clean_checkout(self.repo)
        self.assertFalse(clean)
        self.assertIn("dirty.txt", details)

    def test_ignored_artifacts_prefix_clean_TEST_RUN_007(self):
        art_dir = self.repo / "artifacts" / "verification"
        art_dir.mkdir(parents=True)
        (art_dir / "report.json").write_text("{}")
        clean, details = is_clean_checkout(self.repo, ignored_prefixes=["artifacts/verification/"])
        self.assertTrue(clean, details)

    def test_assume_unchanged_detection(self):
        f = self.repo / "tracked.txt"
        f.write_text("original")
        subprocess.run(["git", "add", "tracked.txt"], cwd=self.repo, check=True)
        subprocess.run(["git", "commit", "-m", "add tracked"], cwd=self.repo, check=True)
        subprocess.run(["git", "update-index", "--assume-unchanged", "tracked.txt"], cwd=self.repo, check=True)
        clean, details = is_clean_checkout(self.repo)
        self.assertFalse(clean)
        self.assertIn("assume-unchanged", details)

    def test_policy_modified_from_base_TEST_RUN_008(self):
        pol_dir = self.repo / "policies"
        pol_dir.mkdir()
        (pol_dir / "checks.json").write_text("v1")
        subprocess.run(["git", "add", "policies/checks.json"], cwd=self.repo, check=True)
        subprocess.run(["git", "commit", "-m", "add policy"], cwd=self.repo, check=True)
        subprocess.run(["git", "checkout", "-b", "feat"], cwd=self.repo, check=True)
        (pol_dir / "checks.json").write_text("v2")
        subprocess.run(["git", "commit", "-am", "modify policy"], cwd=self.repo, check=True)
        modified = check_policy_modified_from_base(self.repo, base_ref="HEAD~1")
        self.assertTrue(modified)
```

- [ ] **Step 2: Run test to verify it fails**
Run: `python3 -m unittest verification/selftests/test_git_auditor.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'verification.checks.git_auditor'`

- [ ] **Step 3: Implement `git_auditor.py`**
Implement `git status --porcelain --untracked-files=all` parser with prefix filtering. Implement `git ls-files -v` check looking for `h` or `S` bits. Implement `git rev-parse HEAD`. Implement base-branch policy diff inspection via `git diff --name-only <base_ref>...HEAD -- policies/`.

- [ ] **Step 4: Run test to verify it passes**
Run: `python3 -m unittest verification/selftests/test_git_auditor.py -v`
Expected: PASS with 4/4 tests passing.

- [ ] **Step 5: Commit**
```bash
git add verification/checks/git_auditor.py verification/selftests/test_git_auditor.py
git commit -m "feat(verification): implement git checkout cleanliness auditor, assume-unchanged guard, and base policy diff detector"
```

---

### Task 5: Core CLI Runner, Lane Dispatcher & Candidate Policy Guard

**Files:**
- Create: `verification/run.py`
- Test: `verification/selftests/test_cli.py`

**Interfaces:**
- Consumes: CLI args (`--lane {candidate,fast}`, `--check <id>`, `--report-dir <path>`, `--config <path>`, `--base-ref <ref>`).
- Produces: Process exit code (0 = PASS, 1 = FAIL, 2 = ERROR).

- [ ] **Step 1: Write failing tests (`TEST-RUN-009`, `TEST-RUN-010`, CLI help and lane checks)**
```python
# verification/selftests/test_cli.py
import unittest
import subprocess
import sys
import tempfile
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
            p.write_text('{"checks": []}')
            res = subprocess.run([sys.executable, "verification/run.py", "--lane", "candidate", "--config", str(p)], capture_output=True, text=True)
            self.assertEqual(res.returncode, 2)
            self.assertIn("Alternate configuration not approved for candidate lane", res.stderr)
```

- [ ] **Step 2: Run test to verify it fails**
Run: `python3 -m unittest verification/selftests/test_cli.py -v`
Expected: FAIL with `FileNotFoundError: verification/run.py`

- [ ] **Step 3: Implement `verification/run.py`**
Orchestrate CLI argument parsing (`argparse`).
In candidate lane:
- Reject `--check` (exit 2).
- Reject `--config` unless matching canonical approved policy digest (exit 2).
- Check cleanliness before run (exit 2 on dirty).
- Execute all enabled required checks.
- Verify post-run cleanliness (exit 2 on mutation).
- Check base policy diff and verify bootstrap approval anchor if applicable.
- Write report and individual log files.
- Recompute verdict; emit 0 for PASS, 1 for FAIL, 2 for ERROR.
In fast lane:
- Permit dirty checkout and targeted checks via `--check`.

- [ ] **Step 4: Run test to verify it passes**
Run: `python3 -m unittest verification/selftests/test_cli.py -v`
Expected: PASS with 3/3 tests passing.

- [ ] **Step 5: Commit**
```bash
git add verification/run.py verification/selftests/test_cli.py
git commit -m "feat(verification): implement main CLI runner, lane orchestrator, and candidate policy locking (TEST-RUN-009, TEST-RUN-010)"
```

---

### Task 6: Specification, Hygiene & Traceability Validator

**Files:**
- Create: `verification/checks/spec_validator.py`
- Test: `verification/selftests/test_spec_validator.py`

**Interfaces:**
- Consumes: Documentation roots (`specs/`, `policies/`, `docs/`, `traceability.json`).
- Produces: `validate_markdown_links(root: Path) -> list[str]`, `validate_spec_traceability(req_path: Path, trace_path: Path) -> list[str]`.

- [ ] **Step 1: Write failing tests (`TEST-MUT-001`, `TEST-MUT-002`)**
```python
# verification/selftests/test_spec_validator.py
import unittest
import tempfile
import json
from pathlib import Path
from verification.checks.spec_validator import validate_markdown_file, validate_spec_traceability

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
```

- [ ] **Step 2: Run test to verify it fails**
Run: `python3 -m unittest verification/selftests/test_spec_validator.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'verification.checks.spec_validator'`

- [ ] **Step 3: Implement `spec_validator.py`**
Implement regex link parser ignoring `^https?://` and `^mailto:`. Implement anchor slugification (lowercase, strip non-alphanumeric except hyphen, replace space with hyphen). Implement requirement ID scanner and cross-referencer against `traceability.json`.

- [ ] **Step 4: Run test to verify it passes**
Run: `python3 -m unittest verification/selftests/test_spec_validator.py -v`
Expected: PASS with 3/3 tests passing.

- [ ] **Step 5: Commit**
```bash
git add verification/checks/spec_validator.py verification/selftests/test_spec_validator.py
git commit -m "feat(verification): implement markdown link, anchor, and spec traceability validator (TEST-MUT-001, TEST-MUT-002)"
```

---

### Task 7: Defect-to-Regression Framework & Anti-Pattern Checker

**Files:**
- Create: `verification/schemas/defects.schema.json`
- Create: `verification/schemas/antipatterns.schema.json`
- Create: `verification/defects.jsonl`
- Create: `verification/antipatterns.json`
- Create: `skills/defect-to-regression/SKILL.md`
- Test: `verification/selftests/test_defects.py`

**Interfaces:**
- Consumes: `defects.jsonl`, `antipatterns.json`.
- Produces: `validate_defects_registry(path: Path) -> tuple[bool, list[str]]`, `validate_antipattern_registry(path: Path) -> tuple[bool, list[str]]`, `scan_antipatterns(patterns_path: Path, repo_root: Path) -> list[str]`.

- [ ] **Step 1: Write failing tests (`TEST-DEF-001`, `TEST-ANTI-001`)**
```python
# verification/selftests/test_defects.py
import unittest
from pathlib import Path
from verification.checks.spec_validator import validate_defects_registry, validate_antipattern_registry

class TestDefectGovernance(unittest.TestCase):
    def test_defect_schema_conformance_TEST_DEF_001(self):
        ok, errs = validate_defects_registry(Path("verification/defects.jsonl"))
        self.assertTrue(ok, errs)

    def test_antipattern_schema_and_dual_samples_TEST_ANTI_001(self):
        ok, errs = validate_antipattern_registry(Path("verification/antipatterns.json"))
        self.assertTrue(ok, errs)
```

- [ ] **Step 2: Run test to verify it fails**
Run: `python3 -m unittest verification/selftests/test_defects.py -v`
Expected: FAIL with `FileNotFoundError: verification/defects.jsonl`

- [ ] **Step 3: Implement schemas, seed catalogs, and validator logic**
Author `defects.schema.json` with 13 mandatory fields, classification enum (`HISTORICAL_RE_DEFECT`, `PROPOSED_PREVENTIVE_CHECK`, `CONFIRMED_APPLICATION_DEFECT`), and lifecycle status enum (`PROPOSED`, `CONFIRMED`, `IN_FIX`, `RESOLVED`, `VERIFIED`, `PERMANENT_PROTECTION`).
Author `antipatterns.schema.json` with `lifecycle_state` (`PROPOSED`, `ADVISORY`, `APPROVED_ACTIVE`), scoped file globs, positive sample, negative sample, and false-positive evaluation.
Author `verification/defects.jsonl` with audited seed items (DEF-001..010) with null execution evidence and PROPOSED status.
Author `verification/antipatterns.json` with audited seed items (`ANTI-001`..`004`).
In `spec_validator.py`, add validator that asserts each pattern matches `positive_test_sample` and does NOT match `negative_mutation_sample`.

- [ ] **Step 4: Run test to verify it passes**
Run: `python3 -m unittest verification/selftests/test_defects.py -v`
Expected: PASS with 2/2 tests passing.

- [ ] **Step 5: Commit**
```bash
git add verification/schemas/defects.schema.json verification/schemas/antipatterns.schema.json verification/defects.jsonl verification/antipatterns.json skills/defect-to-regression/SKILL.md verification/selftests/test_defects.py
git commit -m "feat(verification): implement defect-to-regression schemas, seed catalog, and anti-pattern engine (TEST-DEF-001, TEST-ANTI-001)"
```

---

### Task 8: Controlled Validator Mutation Tests Suite

**Files:**
- Create: `verification/selftests/test_mutations.py`

**Interfaces:**
- Consumes: Mutated fixtures.
- Produces: Proves all validators catch injected mutations.

- [ ] **Step 1: Write all Category 5 Mutation Tests (`TEST-MUT-001` through `TEST-MUT-005`)**
```python
# verification/selftests/test_mutations.py
import unittest
from pathlib import Path
from verification.checks.spec_validator import validate_markdown_text
from verification.checks.evidence import verify_log_hash
from verification.checks.git_auditor import is_clean_checkout
from verification.checks.registry import load_and_validate_registry
import tempfile
import subprocess

class TestControlledMutations(unittest.TestCase):
    def test_broken_link_mutation_TEST_MUT_001(self):
        errs = validate_markdown_text("[broken](missing.md)", base_dir=Path("."))
        self.assertGreater(len(errs), 0)

    def test_tampered_log_mutation_TEST_MUT_004(self):
        self.assertFalse(verify_log_hash(b"tampered\n", expected_sha="0"*64))

    def test_untracked_file_mutation_TEST_MUT_003(self):
        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "test@test"], cwd=repo, check=True)
            subprocess.run(["git", "commit", "--allow-empty", "-m", "init"], cwd=repo, check=True)
            (repo / "untracked.tmp").write_text("mutation")
            clean, _ = is_clean_checkout(repo)
            self.assertFalse(clean)

    def test_invalid_schema_mutation_TEST_MUT_005(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "bad.json"
            p.write_text('{"checks": [{"id": "c1", "argv": ["python3"], "required": true, "timeout_seconds": "30s"}]}')
            with self.assertRaises(ValueError):
                load_and_validate_registry(str(p))
```

- [ ] **Step 2: Run mutation tests and verify they pass**
Run: `python3 -m unittest verification/selftests/test_mutations.py -v`
Expected: PASS with 4/4 tests passing.

- [ ] **Step 3: Commit**
```bash
git add verification/selftests/test_mutations.py
git commit -m "test(verification): add controlled mutation test suite (TEST-MUT-001..005)"
```

---

### Task 9: Initial Policy Activation & Bootstrap Approval Anchor

**Files:**
- Create: `policies/checks.json`
- Create: `policies/bootstrap_approval.json`

**Interfaces:**
- Consumes: FCT-000 active checks (`spec-hygiene`, `harness-selftests`).
- Produces: Canonical check registry and cryptographic bootstrap anchor record.

- [ ] **Step 1: Write `policies/checks.json` and `policies/bootstrap_approval.json`**
```json
// policies/checks.json
{
  "$schema": "../verification/schemas/checks.schema.json",
  "version": 1,
  "checks": [
    {
      "id": "spec-hygiene",
      "argv": ["python3", "verification/checks/spec_validator.py"],
      "required": true,
      "timeout_seconds": 30,
      "type": "command",
      "description": "Validates markdown links, requirement traceability, and defect schemas"
    },
    {
      "id": "harness-selftests",
      "argv": ["python3", "-m", "unittest", "discover", "-s", "verification/selftests"],
      "required": true,
      "timeout_seconds": 60,
      "type": "test_suite",
      "description": "Executes complete verification harness isolated selftest suite"
    }
  ]
}
```

```json
// policies/bootstrap_approval.json
{
  "milestone": "FCT-000",
  "approved_policy_path": "policies/checks.json",
  "approved_policy_sha256": "<CALCULATED_SHA256_OF_CHECKS_JSON>",
  "authorized_by": "Product Owner & Lead Maintainer",
  "authorization_date": "2026-11-24",
  "governance_gate": "G3",
  "status": "APPROVED"
}
```

- [ ] **Step 2: Run verification runner against active checks**
Run: `python3 verification/run.py --lane fast`
Expected: PASS with 100% checks passing.

- [ ] **Step 3: Commit**
```bash
git add policies/checks.json policies/bootstrap_approval.json
git commit -m "feat(policy): activate initial checks.json and anchor bootstrap approval record"
```

---

### Task 10: CI Workflow Pipeline, Candidate Run Validation & Review Hand-Off

**Files:**
- Create: `.github/workflows/ci.yml`
- Test: `verification/selftests/test_ci.py`

**Interfaces:**
- Consumes: GitHub Actions runner environment.
- Produces: `TEST-CI-001` test asserting workflow configuration, clean candidate verification execution.

- [ ] **Step 1: Write failing test `TEST-CI-001`**
```python
# verification/selftests/test_ci.py
import unittest
from pathlib import Path

class TestCIWorkflow(unittest.TestCase):
    def test_workflow_permissions_and_steps_TEST_CI_001(self):
        wf = Path(".github/workflows/ci.yml").read_text()
        self.assertIn("contents: read", wf)
        self.assertIn("--lane candidate", wf)
        self.assertIn("actions/checkout@v4", wf)
        self.assertIn("ref:", wf)
```

- [ ] **Step 2: Run test to verify it fails**
Run: `python3 -m unittest verification/selftests/test_ci.py -v`
Expected: FAIL with `FileNotFoundError: .github/workflows/ci.yml`

- [ ] **Step 3: Implement `.github/workflows/ci.yml`**
Author minimal CI workflow with `contents: read` permissions, concurrency grouping, checking out `${{ github.event.pull_request.head.sha || github.sha }}`, invoking `python3 verification/run.py --lane candidate`, and uploading `artifacts/verification/`.

- [ ] **Step 4: Run test to verify it passes**
Run: `python3 -m unittest verification/selftests/test_ci.py -v`
Expected: PASS with 1/1 tests passing.

- [ ] **Step 5: Execute Strict Verification-First End-to-End Sequence**
Follow the mandatory SDD 2.0 lifecycle:
1. Fast verification check:
   ```bash
   python3 verification/run.py --lane fast
   ```
   Assert: Returns exit code 0.
2. Commit all staged work:
   ```bash
   git add .github/workflows/ci.yml verification/selftests/test_ci.py
   git commit -m "ci: add GitHub Actions verification harness gate workflow (TEST-CI-001)"
   ```
3. Candidate verification run (clean working tree required):
   ```bash
   python3 verification/run.py --lane candidate
   ```
   Assert: Returns exit code 0, generates `artifacts/verification/<run_id>/report.json` with tested commit HEAD SHA and `verdict: PASS`.
4. Independent Evidence Audit:
   ```bash
   python3 verification/checks/evidence.py --audit-latest
   ```
   Assert: Verifies cryptographic log hashes, recomputes verdict, confirms policy hash against `policies/bootstrap_approval.json`, and returns exit code 0.
5. Hand-off for Gate G4 / Review: Stop and present evidence report.
