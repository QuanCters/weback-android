# FCT-000 Requirements Specification — Minimum Viable Verification Harness

**Change ID:** `FCT-000`  
**Milestone:** `FCT-000`  
**Document ID:** `SPEC-REQ-FCT000`  
**Version:** `1.1.0 (G3 Readiness Revision)`  
**Status:** `DRAFT (Prepared for Implementation-Readiness Sign-Off)`  

---

## 1. Traceable Requirements Catalog

### REQ-HARN-001 (MUST): Declarative Check Registry Validation
The verification system must define all executable checks in a declarative JSON registry (`policies/checks.json`) conforming to a strict JSON schema.
- **AC-001.1:** The runner must validate `policies/checks.json` against `verification/schemas/checks.schema.json` (evaluated via a standard-library Python structural validator in `registry.py`) prior to executing any check.
- **AC-001.2:** The registry validator must reject malformed JSON, missing files, empty registries (0 checks), duplicate `id` values, non-array `argv`, empty `argv`, non-positive timeouts (`timeout_seconds <= 0`), and invalid data types.
- **AC-001.3:** If registry validation fails, the runner must immediately terminate with exit code `2` (Harness Error), emitting actionable schema validation errors to stderr.
- **AC-001.4:** At least one mandatory check (`"required": true`) must exist in the registry. An all-optional registry (where all checks have `"required": false`) must be rejected with exit code `2` (`ALL_OPTIONAL_REGISTRY`).
- **Independent Test Oracle:** Synthetic testbeds feeding malformed, empty, all-optional, duplicate, and invalid registries; oracle verifies exit code is 2 and error messages pinpoint the exact failing key.

---

### REQ-HARN-002 (MUST): Required vs. Optional Check Selection & Governance
The registry must explicitly differentiate required checks (`"required": true`) from advisory/optional checks (`"required": false`).
- **AC-002.1:** A candidate verification run must execute all enabled required checks present in the active policy registry.
- **AC-002.2:** A failure in an optional check must be recorded with status `FAIL` in the evidence report but must not cause the overall candidate run verdict to fail, provided all required checks pass.
- **AC-002.3:** A failure in any required check must cause the overall candidate run verdict to be `FAIL` and exit with code `1`.
- **AC-002.4:** Missing, unknown, or invalid check selections requested via CLI (e.g. `--check non_existent_id`) must fail closed immediately with exit code `2` (`UNKNOWN_CHECK_ID`).
- **AC-002.5:** Required check identity, applicability, and completeness must be validated. Candidate execution cannot omit, rename, or downgrade required checks.
- **Independent Test Oracle:** Test registry containing one passing required check and one failing optional check; oracle asserts overall verdict is `PASS` (exit 0) and report reflects optional check failure. Registry with unknown check selection asserts exit code 2.

---

### REQ-HARN-003 (MUST): Dual-Lane Verification Architecture & Candidate Locking
The runner must support two mutually distinct operational lanes: Fast Local Lane and Candidate/PR Lane.
- **AC-003.1:** **Candidate Lane (`--lane candidate` or default):** Mandates a verified clean git checkout before execution, runs all required checks, verifies the checkout remains clean after execution, computes policy and log digests, and generates an official candidate `report.json`.
- **AC-003.2:** **Fast Local Lane (`--lane fast`):** Permitted to run against a dirty working tree, accepts `--check <id>` to run a single targeted check, and produces a report flagged with `"lane": "fast"`.
- **AC-003.3:** Any report generated with `"lane": "fast"` is strictly invalid as evidence for Gate G4 or candidate PR evaluation.
- **AC-003.4:** In Candidate Lane (`--lane candidate`), the runner must strictly execute against the approved canonical policy path (`policies/checks.json`). Specifying alternate `--config` paths in candidate lane must either match the canonical approved policy digest or be rejected with exit code `2` (`UNAPPROVED_POLICY_CONFIG`).
- **AC-003.5:** Targeted check execution via `--check <id>` is strictly prohibited in Candidate Lane. Invoking `--lane candidate --check <id>` must abort immediately with exit code `2` (`PARTIAL_CANDIDATE_EXECUTION_PROHIBITED`). Partial check execution is allowed exclusively in Fast Local Lane (`--lane fast`).
- **Independent Test Oracle:** Invoke runner with `--lane fast` on a dirty repo (asserts run succeeds and report contains `"lane": "fast"`); invoke runner with `--lane candidate` on a dirty repo or with `--check <id>` (asserts run aborts with exit code 2).

---

### REQ-HARN-004 (MUST): Subprocess Execution & Exit-Code Preservation
All checks must execute as real subprocesses without invoking an intermediate shell (`shell=False`).
- **AC-004.1:** Commands must be executed strictly by passing `argv` directly to `subprocess.Popen` / `subprocess.run`, preventing shell injection vulnerabilities.
- **AC-004.2:** The runner must capture both standard output and standard error into a unified sequential byte stream without buffer deadlocks.
- **AC-004.3:** The exact integer exit code returned by the child process must be recorded in the check result structure separately from synthetic runner status classifications.
- **AC-004.4:** If the target binary cannot be found or executed, the runner must record status `ERROR` and exit code `127`.
- **AC-004.5:** Subprocess creation errors (`FileNotFoundError`, `PermissionError`, `OSError`) must be handled within explicit exception boundaries, preserving actual OS error codes (127 for not found, 126 for permission denied) and recording status `ERROR`.
- **Independent Test Oracle:** Script emitting specific exit codes (0, 1, 42), permission-denied binaries, and stdout/stderr markers; oracle verifies exact integer matches and byte-level stream preservation.

---

### REQ-HARN-005 (MUST): Process Group Timeout Management & Tree Cleanup
The runner must enforce a per-check execution timeout and terminate all spawned child and grandchild processes upon expiration.
- **AC-005.1:** Each check must execute within its configured `timeout_seconds`.
- **AC-005.2:** On Linux/POSIX systems, the child process must be spawned in its own process group (`preexec_fn=os.setsid` or equivalent `start_new_session=True`), with `pgid = proc.pid` captured immediately at spawn time.
- **AC-005.3:** Timeout cleanup must not depend solely on the parent process remaining alive. Upon timeout expiration, the runner must inspect remaining processes in `pgid`, send `SIGTERM`, wait a bounded grace period (0.5s), and escalate to `SIGKILL` for descendants that ignore `SIGTERM`.
- **AC-005.4:** Total cleanup duration must be bounded (max 2.0s). If descendant processes cannot be terminated (e.g. process detached via `setsid` or in unkillable D-state), the runner must explicitly record `cleanup_failed: true`.
- **AC-005.5:** All partial output captured prior to timeout or termination must be preserved in the log capture.
- **AC-005.6:** A timed-out check must be recorded with status `TIMEOUT`, exit code `124`, and block the candidate gate.
- **Independent Test Oracle:** Test script spawning deep background sleep processes and SIGTERM-ignoring children; oracle asserts execution terminates within bounded timeout, partial output is preserved, and surviving process group members are killed via SIGKILL.

---

### REQ-HARN-006 (MUST): Clean Candidate Checkout Verification
The Candidate/PR Lane must enforce complete repository hygiene before and after execution.
- **AC-006.1:** Prior to executing checks, the runner must run `git status --porcelain --untracked-files=all`. If any staged, unstaged, or untracked files exist, execution must abort with exit code `2` (`NOT_CLEAN`). In addition, the auditor must inspect `git ls-files -v` and abort if any files have assume-unchanged (`h`) or skip-worktree (`S`) index bits set.
- **AC-006.2:** Following check execution, the runner must re-run `git status --porcelain --untracked-files=all`. If check execution created, modified, or deleted any tracked or untracked files (strictly excluding the designated `artifacts/verification/` path prefix), the runner must fail with exit code `2` (`MUTATED_CHECKOUT`).
- **Independent Test Oracle:** Temporary Git repo with: 1) untracked file before run; 2) check that creates an untracked file during run; 3) assume-unchanged file. Oracle confirms all cases trigger abort with exit code 2 and distinctive diagnostics.

---

### REQ-HARN-007 (MUST): Cryptographic Evidence Generation & Provenance
The runner must generate tamper-evident, machine-readable evidence artifacts for every execution.
- **AC-007.1:** For each run, the runner must generate a unique UUIDv4 `run_id` and write `artifacts/verification/<run_id>/report.json`.
- **AC-007.2:** `report.json` must record: `schema_version`, `run_id`, `tested_sha` (exact 40-char Git HEAD commit SHA), `base_sha` (target base commit SHA), `policy_sha256` (SHA-256 digest of `policies/checks.json`), `policy_modified_from_base` (boolean flag based on git diff against base branch), `lane`, `started_at`, `finished_at`, `environment` (Python version, platform, OS), `verdict` (`PASS`, `FAIL`, `ERROR`), and array of check results.
- **AC-007.3:** The complete combined output of each check must be written to `artifacts/verification/<run_id>/<check_id>.log`, and its SHA-256 digest must be recorded in the corresponding check entry in `report.json`.
- **Independent Test Oracle:** Independent script calculating SHA-256 of `policies/checks.json` and `<check_id>.log`; asserts calculated digests match `report.json` fields bit-for-bit.

---

### REQ-HARN-008 (MUST): Evidence Validation, Recomputed Verdict & Trust Boundaries
The verification system must provide a standalone validator (`verification/checks/evidence.py` or `verify-evidence`) that audits execution reports before gate transition.
- **AC-008.1:** The validator must verify that `report.json` conforms structurally to `verification/schemas/report.schema.json`.
- **AC-008.2:** The validator must reject reports where `tested_sha` does not match the active candidate HEAD SHA or where `base_sha` does not match expected target base.
- **AC-008.3:** The validator must reject reports where `policy_sha256` does not match the active `policies/checks.json` hash. Furthermore, if `policy_modified_from_base` is true and no approved Lead Maintainer waiver/signature is attached, the gate is strictly blocked.
- **AC-008.4:** The validator must verify that every log file referenced in the report exists on disk and its SHA-256 matches `log_sha256`.
- **AC-008.5:** Reusing report artifacts from a prior commit or another branch must be strictly rejected.
- **AC-008.6:** The evidence validator must independently recompute the overall run verdict from actual required check results and exit codes. A report declaring `verdict: PASS` while containing failing, skipped, or timed-out required checks must be rejected as forged (`FORGED_VERDICT_REJECTED`).
- **AC-008.7:** The evidence validator must reject reports containing inconsistent result status and exit code (e.g. exit code 1 with status PASS, or exit code 0 with status FAIL).
- **AC-008.8:** Four-tier governance distinction: Requirements distinguish local execution result (runner output), evidence validation result (auditor check), governance authorization (human sign-off), and remote CI status (GitHub runner). A valid local report does NOT constitute merge or release authorization.
- **AC-008.9:** Bootstrap approval mechanism for FCT-000: Initial verification policy is anchored by a bootstrap authorization record (`policies/bootstrap_approval.json` or signed Lead Maintainer G2 review digest).
- **Independent Test Oracle:** Mutate tested SHA in report, forge PASS verdict with failing check, alter log file, or alter checks.json; oracle asserts validator flags each tampering attempt with non-zero exit code.

---

### REQ-HARN-009 (MUST): Check Completeness & Accurate Result Classification
All required checks must be accounted for without omission or misclassification.
- **AC-009.1:** If any required check from `policies/checks.json` is missing from the results list, the overall verdict must be `FAIL`.
- **AC-009.2:** Duplicate check IDs in results must cause the report to be rejected with exit code `2`.
- **AC-009.3:** Required checks that yield `SKIP`, `ERROR`, `NOT_RUN`, or `TIMEOUT` must cause the overall verdict to be `FAIL` while retaining their exact individual status in `report.json` (no false `PASS` conversion).
- **Independent Test Oracle:** Report fixture omitting one required check; oracle confirms overall verdict is `FAIL` and missing check ID is explicitly reported.

---

### REQ-HARN-010 (MUST): Test Execution Inventory & Structured Reporting
When a check encapsulates unit, contract, or behavioral tests, test execution counts and outcomes must be parsed via a structured reporting adapter.
- **AC-010.1:** For checks designated as test suites (`"type": "test_suite"`), the check execution must utilize a structured test runner adapter (`verification/checks/test_adapter.py`) emitting machine-readable metrics.
- **AC-010.2:** The test summary must capture: `total`, `passed`, `failures`, `errors`, `skipped`, and fail if `total == 0` (`ZERO_TESTS_DISCOVERED`).
- **AC-010.3:** Required skipped tests (`skipped > 0` where skipped tests are disallowed) must block the gate.
- **AC-010.4:** Reports with missing, malformed, or inconsistent test execution metrics relative to child process exit codes must fail closed and block the gate.
- **Independent Test Oracle:** Run test check against empty test suite, 1-passing, multiple passing, failing suite (10 tests: 8 pass, 2 fail), and disallowed skipped suite; oracle asserts exact count extraction and proper pass/fail classification.

---

### REQ-HARN-011 (MUST): Specification Hygiene & Traceability Verification
The harness must provide a built-in check (`spec-hygiene`) validating documentation and requirements integrity.
- **AC-011.1:** Verifies that all requirement IDs (`REQ-xxx`) in `specs/changes/` are unique and referenced in `traceability.json`.
- **AC-011.2:** Verifies that all Markdown hyperlinks and image references resolve to existing files or anchors within the repository, filtering out external web schemes (`^https?://`, `^mailto:`).
- **AC-011.3:** Verifies that 100% of MUST acceptance criteria in the active change specification map to a planned task, check ID, and validation test oracle.
- **Independent Test Oracle:** Introduce broken Markdown link and unmapped requirement in a temporary branch; oracle confirms `spec-hygiene` check fails with exit code 1.

---

### REQ-HARN-012 (MUST): Policy Provenance & Protected Verification Assets
Modifications to verification policies and harness scripts must be tracked and protected.
- **AC-012.1:** The runner must record the SHA-256 digest of `policies/checks.json` before and after execution; any modification during run fails checkout cleanliness.
- **AC-012.2:** Changes to `policies/` or `verification/` must be flagged as `T4` risk assets in review workflows.
- **Independent Test Oracle:** Test mutating `policies/checks.json` during a check execution; oracle asserts post-run cleanliness check fails.

---

### REQ-HARN-013 (MUST): Isolated Harness Selftest Suite
The verification harness must contain its own comprehensive selftest suite executing in isolated, temporary environments.
- **AC-013.1:** Selftests must execute against temporary directories (`tempfile.TemporaryDirectory`) initialized as synthetic Git repositories with an initial empty commit.
- **AC-013.2:** Selftests must never invoke the entire verification harness recursively against the root repository.
- **AC-013.3:** Selftests must verify negative failure paths: intentional command failure, process timeout, missing binary, dirty repo abort, post-run mutation, and malformed registry.
- **Independent Test Oracle:** Execute `python3 -m unittest discover -s verification/selftests`; oracle confirms all selftests pass in < 5 seconds without leaving files on host.

---

### REQ-HARN-014 (MUST): Controlled Validator Mutation Proofs
The harness selftest suite must include controlled mutation tests proving that validators actively detect defects and are not tautologies.
- **AC-014.1:** Injects synthetic defect into a mock report (tampered log hash) and proves evidence validator catches it.
- **AC-014.2:** Injects synthetic broken link into mock spec and proves spec validator catches it.
- **AC-014.3:** Injects untracked file into mock git repo and proves cleanliness guard catches it.
- **Independent Test Oracle:** Mutation runner invokes each mutation test; asserts validator returns non-zero code for every injected defect.

---

### REQ-HARN-015 (MUST): CI Integration & Least-Privilege Trust Boundary
The project must include a minimal, secure GitHub Actions workflow (`.github/workflows/ci.yml`).
- **AC-015.1:** Workflow must declare minimal permissions: `permissions: { contents: read }`.
- **AC-015.2:** Executes on `pull_request` and `push` to `main` with concurrency cancellation.
- **AC-015.3:** Verifies candidate SHA matches `github.event.pull_request.head.sha || github.sha`, runs `python3 verification/run.py --lane candidate`, and archives `artifacts/verification/` as build artifacts.
- **AC-015.4:** Contains zero production secrets, AWS tokens, or signing keys.
- **Independent Test Oracle:** CI linting tool (e.g. `actionlint` or schema validator) validates `.github/workflows/ci.yml` syntax, permissions block, and command sequence.

---

### REQ-HARN-016 (MUST): Defect-to-Regression Framework & Anti-Pattern Governance
The verification system must establish a standardized machine-readable defect registry and anti-pattern rule schema.
- **AC-016.1:** Defect records must be stored in `verification/defects.jsonl` adhering to schema `verification/schemas/defects.schema.json`.
- **AC-016.2:** Anti-pattern rules must be stored in `verification/antipatterns.json` adhering to schema `verification/schemas/antipatterns.schema.json`.
- **AC-016.3:** Anti-pattern rules are categorized by lifecycle state: `PROPOSED`, `ADVISORY`, and `APPROVED_ACTIVE`. Only `APPROVED_ACTIVE` rules with an executable detector, scoped file globs, positive sample, negative sample, and false-positive proof shall execute as blocking gates.
- **AC-016.4:** Every defect record must be categorized as `HISTORICAL_RE_DEFECT`, `PROPOSED_PREVENTIVE_CHECK`, or `CONFIRMED_APPLICATION_DEFECT`. Unexecuted RED/GREEN evidence fields must be null until executed.
- **Independent Test Oracle:** Validate initial seed defects and anti-patterns against their respective JSON schemas using standard Python validator.
