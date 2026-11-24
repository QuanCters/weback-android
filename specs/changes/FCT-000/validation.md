# FCT-000 Verification & Validation Plan — Minimum Viable Verification Harness

**Change ID:** `FCT-000`  
**Milestone:** `FCT-000`  
**Document ID:** `SPEC-VAL-FCT000`  
**Version:** `1.1.0 (G3 Readiness Revision)`  
**Status:** `DRAFT (Prepared for Implementation-Readiness Sign-Off)`  

---

## 1. Test Oracle Strategy & Philosophy

In accordance with Constitution Invariant `C-004` (Independent Test Oracles) and SDD 2.0 principles:
- **No Self-Referential Assertions:** Test assertions must never invoke the code under test to generate expected results. All golden hashes, schema definitions, expected return codes, and error messages are computed or defined independently.
- **Fail-Closed Oracles:** Tests assert exact failure codes (1, 2, 124, 126, 127) and specific error diagnostic substrings, not merely `assert status != PASS`.
- **Isolated Synthetic Environments:** Harness acceptance tests and selftests execute in ephemeral temporary directories (`tempfile.TemporaryDirectory`) with independently initialized mock Git repositories (containing initial empty commits) and synthetic policy files.
- **No Recursive Deadlock:** Selftests must never invoke the root verification harness against the active working tree.
- **Structured Test Execution:** Test counts and statuses are verified using real synthetic unittest test suites executed via the structured test runner adapter, not fragile regex console scraping.

---

## 2. Acceptance Test Matrix

### Category 1: Registry Validation Tests (`TEST-REG-xxx`)

| Test ID | Scenario Description | Test Fixture / Input | Expected Exit Code | Expected Verdict / Status | Independent Test Oracle Assertion |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `TEST-REG-001` | Valid Check Registry | `fixtures/valid_checks.json` with 2 valid checks | 0 | `PASS` | `registry.load()` returns check list; policy hash matches independent `hashlib.sha256(raw_bytes)`. |
| `TEST-REG-002` | Empty Check Registry | JSON object with `"checks": []` | 2 | `ERROR` | Aborts with exit code 2; stderr contains `"Registry contains zero checks"`. |
| `TEST-REG-003` | Malformed JSON File | File containing `{ "checks": [ incomplete... ` | 2 | `ERROR` | Aborts with exit code 2; stderr contains `"Invalid JSON syntax"`. |
| `TEST-REG-004` | Duplicate Check IDs | Registry with two checks having `"id": "lint"` | 2 | `ERROR` | Aborts with exit code 2; stderr contains `"Duplicate check ID: lint"`. |
| `TEST-REG-005` | Invalid Timeout Values | Registry with check having `"timeout_seconds": 0` or `-5` | 2 | `ERROR` | Aborts with exit code 2; stderr contains `"timeout_seconds must be a positive integer"`. |
| `TEST-REG-006` | Invalid Command Arguments | Registry with check having `"argv": "echo hello"` (string instead of array) | 2 | `ERROR` | Aborts with exit code 2; stderr contains `"argv must be a non-empty array of strings"`. |
| `TEST-REG-007` | Missing Required Key | Check definition missing `"required"` or `"argv"` | 2 | `ERROR` | Aborts with exit code 2; stderr contains `"Schema validation failed: missing property"`. |
| `TEST-REG-008` | All-Optional Registry Rejection | Registry where all checks have `"required": false` | 2 | `ERROR` | Aborts with exit code 2; stderr contains `"Registry contains zero required checks"`. |
| `TEST-REG-009` | Unknown Check ID Selection | CLI argument `--check non_existent_check_id` | 2 | `ERROR` | Aborts with exit code 2; stderr contains `"Unknown check ID: non_existent_check_id"`. |

---

### Category 2: Runner Execution Behavior Tests (`TEST-RUN-xxx`)

| Test ID | Scenario Description | Test Fixture / Input | Expected Exit Code | Expected Verdict / Status | Independent Test Oracle Assertion |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `TEST-RUN-001` | Successful Command Execution | `argv = ["python3", "-c", "print('hello'); exit(0)"]` | 0 | `PASS` | Exit code 0; `status: PASS`; stdout in log matches exact string `"hello\n"`. |
| `TEST-RUN-002` | Intentional Required Check Failure | `argv = ["python3", "-c", "exit(1)"]`, `required = true` | 1 | `FAIL` | Exit code 1; overall verdict `FAIL`; check result records `exit_code: 1`. |
| `TEST-RUN-003` | Optional Check Failure | `argv = ["python3", "-c", "exit(1)"]`, `required = false` alongside a passing required check | 0 | `PASS` | Overall exit code 0; overall verdict `PASS`; optional check recorded with `status: FAIL` and `exit_code: 1`. |
| `TEST-RUN-004` | Missing Target Executable | `argv = ["non_existent_binary_xyz123"]` | 1 | `FAIL` | Overall exit code 1; check status `ERROR`; exit code recorded as `127`; stderr captures `[EXECUTABLE NOT FOUND]`. |
| `TEST-RUN-005` | Process Timeout & Group Cleanup | `argv = ["python3", "-c", "import time, subprocess, os; p = subprocess.Popen(['sleep', '100']); open('child.pid', 'w').write(str(p.pid)); time.sleep(100)"]`, `timeout = 1` | 1 | `FAIL` | Process terminates in $1.0s \pm 0.5s$; exit code 1; check status `TIMEOUT`; grandchild process is killed (retrying loop confirms `os.kill(pid, 0)` raises `ProcessLookupError`). |
| `TEST-RUN-006` | Dirty Candidate Worktree Abort | Candidate lane invoked in mock repo with untracked file `dirty.txt` | 2 | `ERROR` | Aborts immediately prior to running checks; exit code 2; stderr contains `"Working tree is not clean"`. |
| `TEST-RUN-007` | Repository Mutation During Execution | A registered check command executes `touch mutated.txt` in working tree | 2 | `ERROR` | Check completes, but post-run git cleanliness audit detects `mutated.txt`; runner exits with code 2 and verdict `ERROR`. |
| `TEST-RUN-008` | Policy Mutation During Execution | A check command modifies `policies/checks.json` during execution | 2 | `ERROR` | Post-run check detects modified file; runner exits with code 2; report verdict `ERROR`. |
| `TEST-RUN-009` | Candidate Partial Selection Blocked | Invoking runner with `--lane candidate --check spec-hygiene` | 2 | `ERROR` | Aborts immediately; exit code 2; stderr contains `"Partial check execution is prohibited in candidate lane"`. |
| `TEST-RUN-010` | Candidate Alternate Config Blocked | Invoking runner with `--lane candidate --config alternate.json` where digest differs from canonical | 2 | `ERROR` | Aborts immediately; exit code 2; stderr contains `"Alternate configuration not approved for candidate lane"`. |
| `TEST-RUN-011` | Subprocess Creation Permission Denied | `argv = ["./unexecutable_script.sh"]` with chmod 000 | 1 | `FAIL` | Runner catches `PermissionError`; records status `ERROR`, exit code `126`; overall exit code 1. |
| `TEST-RUN-012` | Parent Exit With Surviving Descendants | Python parent creates background grandchild worker and immediately exits 0 | 0 | `PASS` | Runner detects process group `pgid = proc.pid`, waits for child, terminates remaining background group workers without hanging. |
| `TEST-RUN-013` | SIGTERM-Resistant Child Escalation | Python script traps `SIGTERM` with `signal.signal(signal.SIGTERM, signal.SIG_IGN)` and sleeps | 1 | `FAIL` | Runner sends `SIGTERM`, detects process remains alive after 0.5s, sends `SIGKILL`, cleanly reaps process within bounded 2.0s cleanup. |
| `TEST-RUN-014` | Large Output Buffer Handling | Python command generates 5MB of stdout data without deadlock | 0 | `PASS` | Output captured completely without pipe buffer deadlocks; byte size matches generated size bit-for-bit. |

---

### Category 3: Evidence Integrity & Anti-Staleness Tests (`TEST-EVI-xxx`)

| Test ID | Scenario Description | Test Fixture / Input | Expected Exit Code | Expected Verdict / Status | Independent Test Oracle Assertion |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `TEST-EVI-001` | Valid Evidence Report Audit | Properly generated `report.json` with matching log files | 0 | `PASS` | Evidence auditor passes; all log SHA-256 digests match file contents on disk. |
| `TEST-EVI-002` | Missing Required Check in Report | `report.json` missing one required check defined in policy | 1 | `FAIL` | Evidence auditor fails; reports `"Missing result for required check: <id>"`. |
| `TEST-EVI-003` | Duplicate Check ID in Report | `report.json` containing two result blocks for `spec-hygiene` | 2 | `ERROR` | Evidence auditor fails; reports `"Duplicate check ID in report: spec-hygiene"`. |
| `TEST-EVI-004` | Malformed Report Schema | `report.json` missing `"tested_sha"` or `"verdict"` | 2 | `ERROR` | Schema validation fails against `report.schema.json`. |
| `TEST-EVI-005` | Stale Tested SHA Detection | `report.json` records SHA `aaaa...`, but current HEAD is `bbbb...` | 1 | `FAIL` | Auditor fails; reports `"Report tested_sha mismatch: expected bbbb..., found aaaa..."`. |
| `TEST-EVI-006` | Policy Digest Mismatch | `report.json` policy SHA does not match current hash of `policies/checks.json` | 1 | `FAIL` | Auditor fails; reports `"Policy hash mismatch: policy has been modified since run"`. |
| `TEST-EVI-007` | Log Digest Mismatch (Tampering) | One byte in `artifacts/verification/<id>/spec-hygiene.log` modified after run | 1 | `FAIL` | Auditor re-hashes log file; asserts calculated hash $\neq$ `log_sha256`; reports `"Log integrity violation"`. |
| `TEST-EVI-008` | Missing Raw Log File | `report.json` references `spec-hygiene.log`, but file deleted | 1 | `FAIL` | Auditor fails; reports `"Referenced log file missing: spec-hygiene.log"`. |
| `TEST-EVI-009` | Rejection of Fast Lane Report for Gate G4 | Candidate verification audit provided with report having `"lane": "fast"` | 1 | `FAIL` | Auditor fails; reports `"Fast lane report cannot be used for candidate gate evaluation"`. |
| `TEST-EVI-010` | Forged PASS Verdict Rejection | Report declares `"verdict": "PASS"` but contains a check with `exit_code: 1` | 2 | `ERROR` | Auditor recomputes verdict from checks; detects discrepancy; aborts with `"Forged PASS verdict rejected"`. |
| `TEST-EVI-011` | Inconsistent Status and Exit Code | Check result records `status: "PASS"` but `exit_code: 1` | 2 | `ERROR` | Auditor detects status/code mismatch; aborts with `"Inconsistent check status and exit code"`. |
| `TEST-EVI-012` | Rejection of Mismatched External Policy Context | Candidate verification audit provided with mismatched `--expected-policy-sha256` | 2 | `ERROR` | Auditor detects policy hash mismatch against external expectation; aborts with `"Policy hash mismatch against expected context"`. |

---

### Category 4: Test Discovery & Inventory Integrity Tests (`TEST-INV-xxx`)

| Test ID | Scenario Description | Test Fixture / Input | Expected Exit Code | Expected Verdict / Status | Independent Test Oracle Assertion |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `TEST-INV-001` | Zero Discovered Tests Rejection | Test suite file with zero `test_*` methods executed via test adapter | 1 | `FAIL` | Structured adapter records `total = 0`; check status marked `FAIL` with diagnostic `"Zero tests discovered"`. |
| `TEST-INV-002` | Disallowed Skipped Tests | Test suite with 5 passing tests and 1 `@unittest.skip` test | 1 | `FAIL` | Structured adapter records `skipped = 1`; check status marked `FAIL` with diagnostic `"Disallowed skipped tests: 1"`. |
| `TEST-INV-003` | Accurate Count Extraction (Mixed Suite) | Test suite with 10 executed tests: 8 pass, 2 fail | 1 | `FAIL` | Check result records exact summary: `total: 10, passed: 8, failures: 2, errors: 0, skipped: 0`; exit code 1. |
| `TEST-INV-004` | Single Passing Test Suite | Test suite with 1 `test_pass` method | 0 | `PASS` | Structured adapter records `total: 1, passed: 1, failures: 0, errors: 0`; exit code 0. |
| `TEST-INV-005` | Test Suite With Assertion Failure | Test suite with 1 `self.assertEqual(1, 2)` failure | 1 | `FAIL` | Adapter records `failures: 1, total: 1`; exit code 1; status `FAIL`. |
| `TEST-INV-006` | Test Suite With Runtime Error | Test suite with 1 test raising `ZeroDivisionError` | 1 | `FAIL` | Adapter records `errors: 1, total: 1`; exit code 1; status `FAIL`. |
| `TEST-INV-007` | Malformed Test Summary Report | Test check emits unparseable non-JSON summary | 2 | `ERROR` | Check wrapper fails to parse structured summary; records status `ERROR`, exit code 2. |
| `TEST-INV-008` | Unapproved Expected Failures | Test suite summary with `expected_failures: 1` when `allow_expected_failures=False` | 1 | `FAIL` | Adapter records `expected_failures = 1`; check status marked `FAIL` with diagnostic `"Unapproved expected failures"`. |
| `TEST-INV-009` | Unexpected Successes Detection | Test suite summary with `unexpected_successes: 1` | 1 | `FAIL` | Adapter records `unexpected_successes = 1`; check status marked `FAIL` with diagnostic `"Unexpected successes"`. |
| `TEST-INV-010` | Inconsistent Execution Counts | Test summary where `total != sum(passed + failures + errors + skipped + ...)` | 1 | `FAIL` | Integrity validator fails with diagnostic `"Inconsistent execution counts"`. |
| `TEST-INV-011` | Exit Code Contradiction Rejection | Report with exit code 0 and failures > 0, or exit code 1 with all passed | 1 | `FAIL` | Integrity validator fails with diagnostic `"Exit-code contradiction"`. |
| `TEST-INV-012` | Structured CLI Discover Invocation | `python3 verification/checks/test_adapter.py --discover ...` CLI invocation | 0 | `PASS` | Adapter executes discovered tests, emits `TEST_SUMMARY_JSON:`, and exits with code 0. |

---

### Category 5: Controlled Validator Mutation Tests (`TEST-MUT-xxx`)

To prove that verification checks actively detect defects rather than behaving as tautological no-ops:

| Mutation Test ID | Target Validator | Injected Fault (Mutation) | Expected Detection Behavior |
| :--- | :--- | :--- | :--- |
| `TEST-MUT-001` | `spec_validator.py` (Markdown Links) | Introduce link `[broken](non_existent_file.md)` into temporary spec file | Validator must fail with exit code 1; stderr pinpoints line and broken link. |
| `TEST-MUT-002` | `spec_validator.py` (Traceability) | Remove `REQ-HARN-001` from `traceability.json` while keeping in `requirements.md` | Validator must fail with exit code 1; stderr pinpoints `"Untraced requirement: REQ-HARN-001"`. |
| `TEST-MUT-003` | `git_auditor.py` (Cleanliness) | Create untracked empty file `untracked_fixture.tmp` in mock repo | Auditor must return `is_clean = False` and list `untracked_fixture.tmp`. |
| `TEST-MUT-004` | `evidence.py` (Log Digest) | Append a newline character `\n` to a verified `.log` file | Auditor must detect SHA-256 mismatch and abort. |
| `TEST-MUT-005` | `registry.py` (Schema) | Alter `"timeout_seconds": 30` to `"timeout_seconds": "30s"` (string) | Registry loader must throw schema validation exception. |

---

### Category 6: CI & Governance Acceptance Tests (`TEST-GOV-xxx`)

| Test ID | Scenario Description | Test Fixture / Input | Expected Exit Code | Expected Verdict / Status | Independent Test Oracle Assertion |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `TEST-CI-001` | CI Workflow Structural Validation & Least Privilege | `.github/workflows/ci.yml` file content | 0 | `PASS` | Structured PyYAML parser asserts: top-level triggers, permissions dict strictly equals `{'contents': 'read'}`, concurrency group configured with `cancel-in-progress: true`, candidate checkout ref pins candidate SHA, runner invoked with `--lane candidate`, and artifact upload step present. Records disclaimer that remote CI execution remains unexecuted until run on GitHub Actions. |
| `TEST-DEF-001` | Defect Registry Schema Conformance & Provenance | `verification/defects.jsonl` lines | 0 | `PASS` | All 13 mandatory schema fields present, valid UUIDs, valid severity levels, unexecuted RED/GREEN fields are null, and provenance type is valid enum. |
| `TEST-ANTI-001` | Anti-Pattern Rule Schema & Dual-Sample Verification | `verification/antipatterns.json` entries | 0 | `PASS` | Evaluates each rule against its `positive_test_sample` (must match) and `negative_mutation_sample` (must NOT match); verifies rules with status `APPROVED_ACTIVE` have test coverage. |

---

## 3. Selftest Testbed Isolation Architecture

Harness selftests (`verification/selftests/`) run under Python's `unittest` framework with strict isolation:
```python
# verification/selftests/test_runner.py pattern
import unittest
import tempfile
import subprocess
import shutil
import os
from pathlib import Path

class RunnerBehaviorSelftest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="weback_selftest_")
        # Initialize synthetic git repository
        subprocess.run(["git", "init"], cwd=self.temp_dir, check=True, capture_output=True)
        subprocess.run(["git", "config", "user.name", "Test Agent"], cwd=self.temp_dir, check=True)
        subprocess.run(["git", "config", "user.email", "test@weback.local"], cwd=self.temp_dir, check=True)
        # Create initial commit so HEAD is always valid
        subprocess.run(["git", "commit", "--allow-empty", "-m", "initial commit"], cwd=self.temp_dir, check=True, capture_output=True)

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)
```
- Each test creates an ephemeral Git repository.
- Selftests run in $< 5$ seconds locally.
- Clean up is guaranteed via `tearDown`.
