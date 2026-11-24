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
