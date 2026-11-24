# FCT-000 Regression Backlog & Seed Anti-Pattern Catalog

**Change ID:** `FCT-000`  
**Milestone:** `FCT-000`  
**Document ID:** `SPEC-REG-FCT000`  
**Version:** `1.2.0 (Post-G3 Review Revision)`  
**Status:** `DRAFT (Prepared for Implementation Baseline Approval)`  

---

## 1. Principles of Defect Governance

In strict accordance with Constitution Invariant `C-009` (Defect-to-Regression Lifecycle), `policies/verification-integrity.md`, and SDD 2.0:
- **No Fabricated Evidence:** Because the new Android application and verification runner have not yet been implemented or executed, unexecuted RED and GREEN test evidence fields must be `null`.
- **Honest Provenance Classification:** Defect records are explicitly classified into:
  1. `PROPOSED_PREVENTIVE_CHECK`: Conceptual or architectural defects identified during governance review (e.g., INIT-001 audit) that the verification harness must prevent.
  2. `HISTORICAL_RE_DEFECT`: Reference errata and legacy implementation discrepancies documented in the reverse engineering baseline ([docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md)). These are historical reference materials, NOT licensing violation claims or confirmed defects in the new application.
  3. `CONFIRMED_APPLICATION_DEFECT`: Genuine defects discovered and reproduced within the new application codebase (none currently exist).
- **Human Authority for Approval:** The `review_status` of all seed defects and anti-patterns is `PROPOSED`. The status `APPROVED` is reserved strictly for genuine human authorization by the Product Owner or Lead Maintainer.

---

## 2. Seed Defect Catalog (`verification/defects.jsonl` Seed Records)

The following 10 seed defects establish the initial defect-to-regression database:

```jsonl
{"defect_id": "DEF-2026-001", "classification": "PROPOSED_PREVENTIVE_CHECK", "requirement_or_invariant_id": "C-008", "severity": "P0", "affected_module": "verification/checks/evidence.py", "evidence": "Governance audit during INIT-001 identifying risk of narrative G4 assertions without machine-readable evidence.", "reproduction": "Candidate verification attempted without generating or validating report.json.", "root_cause": "Absence of automated report generator allowing narrative claims to masquerade as verified gates.", "regression_test_ref": "verification/selftests/test_evidence.py", "pre_fix_red_result": null, "post_fix_green_result": null, "generalization_decision": "true", "proposed_antipattern_rule_id": "ANTI-004", "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-002", "classification": "PROPOSED_PREVENTIVE_CHECK", "requirement_or_invariant_id": "REQ-HARN-008", "severity": "P0", "affected_module": "verification/checks/evidence.py", "evidence": "Governance audit identifying risk of submitting prior commit report.json to bypass candidate checks.", "reproduction": "Submit report.json with tested_sha differing from active git HEAD.", "root_cause": "Evidence validator omitting git HEAD commit equality check against report.tested_sha.", "regression_test_ref": "verification/selftests/test_evidence.py", "pre_fix_red_result": null, "post_fix_green_result": null, "generalization_decision": "false", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-003", "classification": "PROPOSED_PREVENTIVE_CHECK", "requirement_or_invariant_id": "REQ-HARN-011", "severity": "P1", "affected_module": "verification/checks/spec_validator.py", "evidence": "Specification link audit during INIT-001 identifying broken relative links after documentation renaming.", "reproduction": "spec_validator parsing markdown relative paths and anchors.", "root_cause": "Absence of automated link integrity checker during documentation refactoring.", "regression_test_ref": "verification/selftests/test_spec_validator.py", "pre_fix_red_result": null, "post_fix_green_result": null, "generalization_decision": "false", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-004", "classification": "HISTORICAL_RE_DEFECT", "requirement_or_invariant_id": "C-014", "severity": "P0", "affected_module": "lib/arm64-v8a/libIOTCAPIs.so", "evidence": "Observed legacy packaging in com.yugong.Backome bundled closed-source vendor binaries (REF-RE-01 Section 6.1) as documented in RE reference material.", "reproduction": "Inspect vendor APK reference files.", "root_cause": "Legacy architecture bundled closed-source proprietary binaries rather than clean-room open-source implementations; recorded as reference baseline, not as licensing violation claim or application defect.", "regression_test_ref": null, "pre_fix_red_result": null, "post_fix_green_result": null, "generalization_decision": "true", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-005", "classification": "HISTORICAL_RE_DEFECT", "requirement_or_invariant_id": "C-010", "severity": "P0", "affected_module": "AndroidManifest.xml", "evidence": "Legacy APK AndroidManifest.xml configured android:usesCleartextTraffic=true globally (SEC-01 in REF-RE-01).", "reproduction": "Inspect decoded legacy AndroidManifest.xml line 42.", "root_cause": "Legacy application disabled transport security globally to allow unencrypted communication.", "regression_test_ref": null, "pre_fix_red_result": null, "post_fix_green_result": null, "generalization_decision": "true", "proposed_antipattern_rule_id": "ANTI-001", "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-006", "classification": "HISTORICAL_RE_DEFECT", "requirement_or_invariant_id": "REG-01", "severity": "P1", "affected_module": "com.yugong.Backome.protocol", "evidence": "Bytecode proves outbound WebSocket commands transmit flat state, whereas early notes assumed desired wrapper (REG-01 in RE_BASELINE.md).", "reproduction": "Compare Dalvik bytecode in websocket transmission against REST timer payload.", "root_cause": "Initial reverse engineering documentation conflated WebSocket wire format with REST schedule countdown schema.", "regression_test_ref": null, "pre_fix_red_result": null, "post_fix_green_result": null, "generalization_decision": "false", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-007", "classification": "HISTORICAL_RE_DEFECT", "requirement_or_invariant_id": "REG-04", "severity": "P1", "affected_module": "com.yugong.Backome.map", "evidence": "Bytecode proves binary SLAM maps decompress with java.util.zip.Inflater (ZLIB header), refuting early GZIP claims (REG-04 in RE_BASELINE.md).", "reproduction": "Decompile MapPacketDecoder.java and observe Inflater usage.", "root_cause": "Early architectural report erroneously assumed GZIP stream format without bytecode confirmation.", "regression_test_ref": null, "pre_fix_red_result": null, "post_fix_green_result": null, "generalization_decision": "false", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-008", "classification": "HISTORICAL_RE_DEFECT", "requirement_or_invariant_id": "REG-03", "severity": "P1", "affected_module": "com.yugong.Backome.bluetooth", "evidence": "Bytecode in bluetooth.c.E proves hardcoded 86-char slice capacity (128 buffer - 42 envelope), refuting MTU-10 variable slicing (REG-03 in RE_BASELINE.md).", "reproduction": "Inspect constant f41938j in decompiled bluetooth.c.E.", "root_cause": "Speculative formula MTU-10 assumed negotiated MTU was used for packet fragmentation when bytecode hardcoded buffer size.", "regression_test_ref": null, "pre_fix_red_result": null, "post_fix_green_result": null, "generalization_decision": "false", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-009", "classification": "HISTORICAL_RE_DEFECT", "requirement_or_invariant_id": "REG-02", "severity": "P1", "affected_module": "com.yugong.Backome.net", "evidence": "Static scan of entire DEX string pool returned 0 occurrences of 192.168.200.1 or 192.168.10.1 (REG-02 in RE_BASELINE.md).", "reproduction": "Search DEX string pool in decompiled codebase.", "root_cause": "Early unverified engineering assumptions introduced speculative subnet IPs not present in legacy firmware/app.", "regression_test_ref": null, "pre_fix_red_result": null, "post_fix_green_result": null, "generalization_decision": "false", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-010", "classification": "HISTORICAL_RE_DEFECT", "requirement_or_invariant_id": "REG-05", "severity": "P2", "affected_module": "com.yugong.Backome.net", "evidence": "Audit of network communications in bytecode found 0 evidence of robot exposing HTTP services on port 8000 (REG-05 in RE_BASELINE.md).", "reproduction": "Search network socket invocations across decompiled codebase.", "root_cause": "Speculative assumption that robot hosted an internal web server on port 8000.", "regression_test_ref": null, "pre_fix_red_result": null, "post_fix_green_result": null, "generalization_decision": "false", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-011", "classification": "CONFIRMED_APPLICATION_DEFECT", "requirement_or_invariant_id": "REQ-HARN-011", "severity": "P1", "affected_module": "specs/changes/FCT-000/regression-backlog.md", "evidence": "GitHub Actions CI run 45417293207 failed in spec-hygiene due to machine-specific file:///home/quan/... path in markdown documentation.", "reproduction": "Run spec_validator.py on runner where /home/quan does not exist.", "root_cause": "Hardcoded developer workstation absolute path instead of relative repository path.", "regression_test_ref": "verification/selftests/test_spec_validator.py", "pre_fix_red_result": "FAILED in CI run 45417293207", "post_fix_green_result": "PASS on local and CI", "generalization_decision": "true", "proposed_antipattern_rule_id": "ANTI-005", "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-012", "classification": "CONFIRMED_APPLICATION_DEFECT", "requirement_or_invariant_id": "REQ-HARN-008", "severity": "P0", "affected_module": "verification/checks/evidence.py", "evidence": "Independent code review on PR #2 identified that candidate verification conditionally verified expected values only when provided, allowing unanchored candidate reports.", "reproduction": "Call verify_evidence_report with require_candidate=True but omit expected_sha, expected_base_sha, expected_policy_sha256, or required_check_ids.", "root_cause": "Conditional checks if expected_sha: instead of mandatory presence enforcement in authoritative candidate mode.", "regression_test_ref": "verification/selftests/test_evidence.py::test_candidate_mode_missing_expected_context_TEST_EVI_013", "pre_fix_red_result": "FAILED (AssertionError: True is not false)", "post_fix_green_result": "PASS (errors correctly detected and candidate rejected)", "generalization_decision": "false", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-013", "classification": "CONFIRMED_APPLICATION_DEFECT", "requirement_or_invariant_id": "REQ-HARN-008", "severity": "P0", "affected_module": "verification/checks/evidence.py", "evidence": "Independent code review on PR #2 identified missing path containment check on log_file, potential symlink traversal, and inability to distinguish test_suite from command checks without trusting report fields.", "reproduction": "Pass report with log_file='../secret.log' or symlink, or test_suite check lacking test_summary.", "root_cause": "Absence of directory containment verification and relying solely on untrusted report fields for check type distinction.", "regression_test_ref": "verification/selftests/test_evidence.py::test_log_path_traversal_rejected_TEST_EVI_021", "pre_fix_red_result": "FAILED (AssertionError: Log path traversal detected not found)", "post_fix_green_result": "PASS (path traversal and symlinks rejected)", "generalization_decision": "true", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-014", "classification": "CONFIRMED_APPLICATION_DEFECT", "requirement_or_invariant_id": "REQ-HARN-015", "severity": "P0", "affected_module": ".github/workflows/ci.yml", "evidence": "Independent code review on PR #2 identified CI workflow executed candidate lane but completely omitted the authoritative evidence audit step.", "reproduction": "Inspect .github/workflows/ci.yml and run test_ci.py.", "root_cause": "CI workflow omitted execution of verification/checks/evidence.py, failing to validate generated report cryptographic provenance.", "regression_test_ref": "verification/selftests/test_ci.py::test_workflow_structure_and_permissions_TEST_CI_001", "pre_fix_red_result": "FAILED (AssertionError: CI workflow must include explicit evidence audit step)", "post_fix_green_result": "PASS (evidence audit stage verified and enforced)", "generalization_decision": "true", "proposed_antipattern_rule_id": "ANTI-006", "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-015", "classification": "CONFIRMED_APPLICATION_DEFECT", "requirement_or_invariant_id": "REQ-HARN-006", "severity": "P1", "affected_module": "verification/checks/git_auditor.py", "evidence": "Independent code review on PR #2 identified fallback to '0'*40 SHA, base SHA substitution with candidate HEAD, and lack of HEAD/policy immutability checks.", "reproduction": "Call get_base_sha with invalid ref or observe run.py exception handlers.", "root_cause": "Overly broad exception handlers swallowing git rev-parse errors and substituting 0*40 or tested_sha.", "regression_test_ref": "verification/selftests/test_git_auditor.py::test_non_existent_base_ref_raises_value_error_TEST_RUN_016", "pre_fix_red_result": "FAILED (CalledProcessError instead of ValueError; masked revisions)", "post_fix_green_result": "PASS (strict ValueError raised and pre/post immutability enforced)", "generalization_decision": "true", "proposed_antipattern_rule_id": "ANTI-007", "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-016", "classification": "CONFIRMED_APPLICATION_DEFECT", "requirement_or_invariant_id": "REQ-HARN-011", "severity": "P1", "affected_module": "verification/checks/spec_validator.py", "evidence": "Independent code review on PR #2 identified hardcoded FCT-000 path, absence of cross-spec requirement/AC ID uniqueness checks, and missing repo-root link traversal guards.", "reproduction": "Add synthetic second spec with duplicate REQ/AC IDs or link with ../ escaping repo root.", "root_cause": "spec_validator checked only FCT-000 and did not validate cross-specification collisions or root boundaries.", "regression_test_ref": "verification/selftests/test_spec_validator.py::test_detect_cross_spec_duplicate_ids_TEST_SPEC_001", "pre_fix_red_result": "FAILED (ImportError: cannot import name validate_all_change_specs)", "post_fix_green_result": "PASS (dynamic discovery and duplicate ID collisions detected)", "generalization_decision": "false", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-017", "classification": "CONFIRMED_APPLICATION_DEFECT", "requirement_or_invariant_id": "REQ-HARN-005", "severity": "P1", "affected_module": "verification/checks/test_adapter.py", "evidence": "Independent code review on PR #2 identified test adapter permitted boolean values, negative counts, string numbers, and omitted test failure tracebacks from output.", "reproduction": "Pass summary with total=True, total=-1, or run failing synthetic suite.", "root_cause": "Incomplete metric type checks (bool being subclass of int in Python) and omitting stderr traceback logging.", "regression_test_ref": "verification/selftests/test_test_adapter.py::test_boolean_counts_rejected_TEST_INV_008", "pre_fix_red_result": "FAILED (AssertionError: ValueError not raised for boolean or negative counts)", "post_fix_green_result": "PASS (strict integer validation and failure tracebacks retained)", "generalization_decision": "false", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-018", "classification": "CONFIRMED_APPLICATION_DEFECT", "requirement_or_invariant_id": "REQ-HARN-004", "severity": "P1", "affected_module": "verification/checks/runner.py", "evidence": "Independent code review on PR #2 identified subprocess engine did not record cleanup_failed when parent process exited normally but descendants survived, and used non-monotonic time.", "reproduction": "Simulate failed process group cleanup on normal exit.", "root_cause": "_kill_process_group return code was ignored on normal parent exit path and time.time() was used instead of time.monotonic().", "regression_test_ref": "verification/selftests/test_runner.py::test_cleanup_failed_sets_status_fail_TEST_RUN_015", "pre_fix_red_result": "FAILED (AssertionError: False is not true for cleanup_failed)", "post_fix_green_result": "PASS (cleanup_failed recorded and monotonic clock enforced)", "generalization_decision": "false", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-019", "classification": "CONFIRMED_APPLICATION_DEFECT", "requirement_or_invariant_id": "REQ-HARN-008", "severity": "P0", "affected_module": "verification/checks/evidence.py", "evidence": "Independent review identified candidate mode permitted missing approved policy definitions, allowed a subset of required checks, and inferred test-suite identity from untrusted report fields.", "reproduction": "Call verify_evidence_report with require_candidate=True and policy_path=None or required_check_ids as subset of policy.", "root_cause": "Optional policy_path parameter in candidate mode and checking required_ids subset instead of exact equality with approved policy required set.", "regression_test_ref": "verification/selftests/test_evidence.py::test_missing_policy_rejected_in_candidate_mode_TEST_EVI_028", "pre_fix_red_result": "FAILED (AssertionError: Missing required approved policy definitions not found)", "post_fix_green_result": "PASS (Mandatory policy and exact required check set enforced)", "generalization_decision": "false", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-020", "classification": "CONFIRMED_APPLICATION_DEFECT", "requirement_or_invariant_id": "REQ-HARN-015", "severity": "P0", "affected_module": ".github/workflows/ci.yml", "evidence": "Hardcoded base SHA commit fallback and unanchored ls -t report selection in CI workflow.", "reproduction": "Run test_ci.py with workflow inspecting hardcoded commit SHAs and ls -t evidence selection.", "root_cause": "CI workflow used shell glob ls -t instead of exact run-id binding and hardcoded a specific commit hash for PR base ref.", "regression_test_ref": "verification/selftests/test_ci.py::test_ci_workflow_no_hardcoded_sha_and_exact_run_binding_TEST_CI_002", "pre_fix_red_result": "FAILED (AssertionError: '4c7e78ca6c4a73290493fa543aaaa48336a181c3' unexpectedly found)", "post_fix_green_result": "PASS (Exact run-id binding and event context derivation enforced)", "generalization_decision": "false", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-021", "classification": "CONFIRMED_APPLICATION_DEFECT", "requirement_or_invariant_id": "REQ-HARN-006", "severity": "P0", "affected_module": "verification/checks/git_auditor.py", "evidence": "Git cleanliness auditor ignored modified tracked files located under ignored_prefixes, and candidate lane allowed output directory traversal.", "reproduction": "Modify a tracked file under artifacts/verification/ and run is_clean_checkout; or supply --output-dir verification to run.py in candidate lane.", "root_cause": "is_clean_checkout checked path prefix match without verifying status_code == '??', and candidate lane did not restrict --output-dir.", "regression_test_ref": "verification/selftests/test_git_auditor.py::test_tracked_file_modification_under_ignored_prefix_not_ignored_TEST_RUN_021", "pre_fix_red_result": "FAILED (AssertionError: True is not false : Tracked file modification must NOT be ignored)", "post_fix_green_result": "PASS (Tracked file modifications strictly trigger dirty status and candidate output directory restricted)", "generalization_decision": "true", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-022", "classification": "CONFIRMED_APPLICATION_DEFECT", "requirement_or_invariant_id": "REQ-HARN-011", "severity": "P1", "affected_module": "verification/checks/spec_validator.py", "evidence": "spec_validator silently skipped changes directories lacking requirements.md, ignored in-document duplicate REQ/AC IDs, and permitted empty catalogs or unmapped ACs in requirements list.", "reproduction": "Run validate_all_change_specs on directory missing validation.md or containing duplicate in-document REQ IDs or unmapped AC strings.", "root_cause": "discover_change_specs filtered on requirements.md existence and validate_spec_traceability set() deduplicated IDs without checking for duplicates.", "regression_test_ref": "verification/selftests/test_spec_validator.py::test_spec_validator_fail_closed_missing_spec_file_TEST_SPEC_003", "pre_fix_red_result": "FAILED (AssertionError: False is not true : Missing required specification file not found)", "post_fix_green_result": "PASS (Fail-closed spec discovery, in-document duplicate rejection, and strict catalog reference checks enforced)", "generalization_decision": "false", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-023", "classification": "CONFIRMED_APPLICATION_DEFECT", "requirement_or_invariant_id": "REQ-HARN-004", "severity": "P1", "affected_module": "verification/checks/runner.py", "evidence": "Runner hung when detached grandchild process held inherited stdout pipe open, exceeding bounded execution time.", "reproduction": "Spawn detached process with sleep 10 holding sys.stdout while parent exits immediately.", "root_cause": "Blocking proc.stdout.read() inside reader thread held BufferedReader lock, blocking join and close until write-end closed.", "regression_test_ref": "verification/selftests/test_runner.py::test_detached_grandchild_pipe_open_does_not_hang_runner_TEST_RUN_024", "pre_fix_red_result": "FAILED (AssertionError: 10.032s not less than 3.0s)", "post_fix_green_result": "PASS (Non-blocking polling reader with stop_event and bounded join enforced)", "generalization_decision": "false", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-024", "classification": "CONFIRMED_APPLICATION_DEFECT", "requirement_or_invariant_id": "REQ-HARN-005", "severity": "P1", "affected_module": "verification/checks/test_adapter.py", "evidence": "extract_test_summary_from_output returned the first marker encountered without rejecting duplicate or conflicting summary markers, and traceability summary counts did not match the 73 validation scenarios.", "reproduction": "Pass output containing multiple TEST_SUMMARY_JSON: markers to extract_test_summary_from_output.", "root_cause": "Loop broke and returned on first matching prefix without checking for subsequent conflicting/duplicate summary lines.", "regression_test_ref": "verification/selftests/test_test_adapter.py::test_duplicate_or_conflicting_summary_markers_rejected_TEST_INV_012", "pre_fix_red_result": "FAILED (AssertionError: {'total': 5...} is not None)", "post_fix_green_result": "PASS (Duplicate and conflicting summary markers strictly fail closed; traceability reconciled)", "generalization_decision": "false", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
```

---

## 3. Seed Anti-Pattern Catalog (`verification/antipatterns.json` Seed Catalog)

### ANTI-001: Global Cleartext HTTP Permission
- **Lifecycle Status:** `PROPOSED (Inactive in FCT-000)`
- **Scope (`target_globs`):** `["app/src/main/res/xml/network_security_config.xml"]`
- **Pattern:** `<base-config\b[^>]*cleartextTrafficPermitted\s*=\s*["']true["']`
- **Matcher Type:** `regex`
- **Rationale:** Cleartext traffic leaks device credentials and authentication tokens (`SEC-01`).
- **Positive Test Sample:** `<network-security-config><base-config cleartextTrafficPermitted="true"></base-config></network-security-config>`
- **Negative Mutation Sample:** `<network-security-config><base-config cleartextTrafficPermitted="false"></base-config></network-security-config>`
- **False Positive Assessment:** Scoped `<domain-config>` tags for loopback (`127.0.0.1`) or SoftAP (`192.168.4.1`) do not match `<base-config>`.
- **Activation Milestone:** Milestone FCT-001 (Android Foundation).

### ANTI-002: Deprecated EncryptedSharedPreferences Usage
- **Lifecycle Status:** `PROPOSED (Inactive in FCT-000)`
- **Scope (`target_globs`):** `["app/src/main/java/**/*.kt"]`
- **Pattern:** `androidx\.security\.crypto\.EncryptedSharedPreferences`
- **Matcher Type:** `regex`
- **Rationale:** Google has deprecated `EncryptedSharedPreferences` due to deadlock bugs and data loss.
- **Positive Test Sample:** `import androidx.security.crypto.EncryptedSharedPreferences`
- **Negative Mutation Sample:** `import com.weback.robot.security.KeystoreTokenStorage`
- **False Positive Assessment:** None; library is banned in its entirety.
- **Activation Milestone:** Milestone FCT-001 (Android Foundation).

### ANTI-003: GreenRobot EventBus Usage
- **Lifecycle Status:** `PROPOSED (Inactive in FCT-000)`
- **Scope (`target_globs`):** `["app/src/main/java/**/*.kt"]`
- **Pattern:** `org\.greenrobot\.eventbus\.EventBus|EventBus\.(getDefault|builder)`
- **Matcher Type:** `regex`
- **Rationale:** EventBus creates untraceable global state spaghetti and breaks clean MVI state machines.
- **Positive Test Sample:** `EventBus.getDefault().post(UpdateEvent())`
- **Negative Mutation Sample:** `viewModel.eventChannel.send(UpdateEvent)`
- **False Positive Assessment:** Specific to package and default invocation patterns.
- **Activation Milestone:** Milestone FCT-001 (Android Foundation).

### ANTI-004: In-Tree Report Verdict Masquerade
- **Lifecycle Status:** `PROPOSED (Inactive in FCT-000)`
- **Scope (`target_globs`):** `["verification/checks/**/*.py"]`
- **Pattern:** `status\s*=\s*["']PASS["'].*exit_code\s*=\s*[1-9]`
- **Matcher Type:** `regex`
- **Rationale:** Forbids code from assigning `status = "PASS"` when a check command returned a non-zero exit code.
- **Positive Test Sample:** `return CheckResult(status="PASS", exit_code=1)`
- **Negative Mutation Sample:** `return CheckResult(status="FAIL", exit_code=1)`
- **False Positive Assessment:** Scoped strictly to check runner result constructors.
- **Activation Milestone:** Milestone FCT-000 (Harness Bootstrap).

### ANTI-005: Machine-Specific Workstation Paths
- **Lifecycle Status:** `PROPOSED (Inactive in FCT-000)`
- **Scope (`target_globs`):** `["specs/**/*.md", "policies/**/*.md", "docs/**/*.md"]`
- **Pattern:** `\]\(file:///(?:home|Users)/[^)]+\)`
- **Matcher Type:** `regex`
- **Rationale:** Hardcoded workstation filesystem paths break portability across different CI runners and developer machines.
- **Positive Test Sample:** `[link](file:///home/user/workspace/doc.md)`
- **Negative Mutation Sample:** `[link](../../doc.md)`
- **False Positive Assessment:** Scoped strictly to markdown link destinations starting with file:///home or file:///Users.
- **Activation Milestone:** Milestone FCT-000 (Harness Bootstrap).

### ANTI-006: CI continue-on-error on Mandatory Steps
- **Lifecycle Status:** `PROPOSED (Inactive in FCT-000)`
- **Scope (`target_globs`):** `[".github/workflows/**/*.yml"]`
- **Pattern:** `continue-on-error\s*:\s*true`
- **Matcher Type:** `regex`
- **Rationale:** continue-on-error on mandatory verification suppresses test and check failures in CI.
- **Positive Test Sample:** `run: python3 verification/run.py\ncontinue-on-error: true`
- **Negative Mutation Sample:** `run: python3 verification/run.py\ncontinue-on-error: false`
- **False Positive Assessment:** Scoped to workflow YAML files.
- **Activation Milestone:** Milestone FCT-000 (Harness Bootstrap).

### ANTI-007: Fallback to 0*40 Revision Masking
- **Lifecycle Status:** `PROPOSED (Inactive in FCT-000)`
- **Scope (`target_globs`):** `["verification/**/*.py"]`
- **Pattern:** `["']0{40}["']`
- **Matcher Type:** `regex`
- **Rationale:** Fallback to 0*40 SHA masks unresolvable commit revisions and circumvents candidate revision binding.
- **Positive Test Sample:** `tested_sha = "0000000000000000000000000000000000000000"`
- **Negative Mutation Sample:** `raise ValueError("Could not resolve SHA")`
- **False Positive Assessment:** Scoped to verification harness Python scripts.
- **Activation Milestone:** Milestone FCT-000 (Harness Bootstrap).

---

## 4. Outstanding Non-Blocking Technical Limitations

The following technical boundaries and limitations are recorded as engineering backlog items for subsequent milestones:

### LIM-001: Python Standard-Library Scope & Android Tooling Boundary
- **Description:** The FCT-000 verification harness runtime strictly uses standard-library Python 3.12 without third-party runner dependencies.
- **Impact:** Android Gradle build infrastructure, Roborazzi UI snapshot testing, and Android emulator instrumentation are not present in FCT-000.
- **Resolution Plan:** Milestone FCT-001 (Android Foundation & First Runnable APK) introduces the Gradle wrapper (`gradlew`), AGP, Kotlin 2.0, and synthetic UI test harness.

### LIM-002: Workflow Immutability & Repository Variable Scope
- **Description:** The GitHub repository variable `APPROVED_POLICY_SHA256` provides an external expectation boundary for authoritative candidate evidence verification. However, repository variables alone do not prevent a candidate-controlled pull request workflow from modifying workflow steps.
- **Impact:** Candidate-controlled branches could theoretically modify workflow step definitions before merging.
- **Resolution Plan:** Full branch protection rulesets on `main` and mandatory human maintainer PR code review are recommended to prevent unauthorized workflow mutations.

### LIM-003: Simulator-First Boundary & Deferred Physical Device Verification
- **Description:** In strict adherence to Constitution Invariant `C-003` (Simulator-First by Default) and `C-004` (Zero Live Robot Credentials in Git), all FCT-000 selftests execute against synthetic fixtures and subprocess testbeds.
- **Impact:** No automated connections to physical vacuum robots (Tuya SoftAP / BLE) or live AWS/WeBack cloud services are attempted.
- **Resolution Plan:** Milestone FCT-003+ introduces synthetic device mocks and decoupled mock servers for local integration testing; physical device testing requires separate explicit human authorization.

