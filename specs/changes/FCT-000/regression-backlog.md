# FCT-000 Initial Regression Backlog & Anti-Pattern Catalog

**Change ID:** `FCT-000`  
**Milestone:** `FCT-000`  
**Document ID:** `SPEC-REG-CAT-01`  
**Version:** `1.1.0 (G3 Readiness Revision)`  
**Status:** `DRAFT (Prepared for Implementation-Readiness Sign-Off)`  

---

## 1. Provenance & Initial Knowledge Base

The initial defect knowledge base is derived from confirmed reverse-engineering errata (`docs/reference/RE_BASELINE.md`, `docs/engineering/INIT-001_DISCOVERY_CORRECTIONS.md`) and governance invariants.

> [!IMPORTANT]
> **Defect Provenance & Zero-Fabrication Invariant:**
> The new Android application and verification harness do not yet exist in code. Therefore, no unexecuted RED or GREEN execution evidence, simulated test logs, or artificial human approval may be claimed. 
> - Unexecuted evidence fields (`pre_fix_red_result`, `post_fix_green_result`) are set strictly to `null`.
> - All records carry lifecycle status `PROPOSED` until formally reviewed and approved by human maintainers.

### Record Classifications
1. **`HISTORICAL_RE_DEFECT`:** Confirmed defects, vulnerabilities, or erroneous reverse-engineering claims identified in the legacy WeBack 6.1.7 APK (`REF-RE-01`).
2. **`PROPOSED_PREVENTIVE_CHECK`:** Anticipated failure modes, verification bypass vectors, or documentation hygiene rules designed to prevent future engineering mistakes.
3. **`CONFIRMED_APPLICATION_DEFECT`:** Genuine bugs discovered in new application source code or verification harness code (0 existing at bootstrap).

---

## 2. Seed Defect Records (`verification/defects.jsonl` Seed Catalog)

```jsonl
{"defect_id": "DEF-2026-001", "classification": "PROPOSED_PREVENTIVE_CHECK", "requirement_or_invariant_id": "C-008", "severity": "P0", "affected_module": "verification/checks/evidence.py", "evidence": "Governance audit during INIT-001 identifying risk of narrative G4 assertions without machine-readable evidence.", "reproduction": "Candidate verification attempted without generating or validating report.json.", "root_cause": "Absence of automated report generator allowing narrative claims to masquerade as verified gates.", "regression_test_ref": "verification/selftests/test_evidence.py", "pre_fix_red_result": null, "post_fix_green_result": null, "generalization_decision": "true", "proposed_antipattern_rule_id": "ANTI-004", "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-002", "classification": "PROPOSED_PREVENTIVE_CHECK", "requirement_or_invariant_id": "REQ-HARN-008", "severity": "P0", "affected_module": "verification/checks/evidence.py", "evidence": "Governance audit identifying risk of submitting prior commit report.json to bypass candidate checks.", "reproduction": "Submit report.json with tested_sha differing from active git HEAD.", "root_cause": "Evidence validator omitting git HEAD commit equality check against report.tested_sha.", "regression_test_ref": "verification/selftests/test_evidence.py", "pre_fix_red_result": null, "post_fix_green_result": null, "generalization_decision": "false", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-003", "classification": "PROPOSED_PREVENTIVE_CHECK", "requirement_or_invariant_id": "REQ-HARN-011", "severity": "P1", "affected_module": "verification/checks/spec_validator.py", "evidence": "Specification link audit during INIT-001 identifying broken relative links after documentation renaming.", "reproduction": "spec_validator parsing markdown relative paths and anchors.", "root_cause": "Absence of automated link integrity checker during documentation refactoring.", "regression_test_ref": "verification/selftests/test_spec_validator.py", "pre_fix_red_result": null, "post_fix_green_result": null, "generalization_decision": "false", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
{"defect_id": "DEF-2026-004", "classification": "HISTORICAL_RE_DEFECT", "requirement_or_invariant_id": "C-014", "severity": "P0", "affected_module": "lib/arm64-v8a/libIOTCAPIs.so", "evidence": "Dalvik bytecode in com.yugong.Backome bundles closed-source ThroughTek binaries (REF-RE-01 Section 6.1).", "reproduction": "Inspect legacy APK lib/arm64-v8a/ directory.", "root_cause": "Legacy client bundled proprietary third-party binary blobs with licensing and security vulnerabilities.", "regression_test_ref": null, "pre_fix_red_result": null, "post_fix_green_result": null, "generalization_decision": "true", "proposed_antipattern_rule_id": null, "review_status": "PROPOSED"}
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
- **Negative Mutation Sample:** `_uiState.update { it.copy(cleaning = true) }`
- **False Positive Assessment:** Custom classes named `RobotEventBus` or unrelated domain buses are not matched unless using `org.greenrobot` package or `EventBus.getDefault()`.
- **Activation Milestone:** Milestone FCT-001 (Android Foundation).

### ANTI-004: Tautological Self-Referential Test Oracles
- **Lifecycle Status:** `ADVISORY (Non-blocking warning in FCT-000)`
- **Scope (`target_globs`):** `["app/src/test/java/**/*.kt", "verification/selftests/*.py"]`
- **Pattern:** `assert\w*\(\s*([a-zA-Z0-9_.]+)\.([a-zA-Z0-9_]+)\(([^)]*)\)\s*,\s*\1\.\2\(\3\)\s*\)`
- **Matcher Type:** `regex`
- **Rationale:** Constitution Invariant `C-004` requires test assertions to calculate expected values independently.
- **Positive Test Sample:** `assertEquals(service.compute(x), service.compute(x))`
- **Negative Mutation Sample:** `assertEquals(service.compute(x), service.compute(y))`
- **False Positive Assessment:** Scoped to assertions where the receiver, method, and argument are identical. Comparing different arguments `compute(x)` vs `compute(y)` passes cleanly.
- **Activation Milestone:** Advisory during FCT-000; blocking in FCT-002 upon introducing AST-based analysis.

---

## 4. Phased Activation Matrix

> [!IMPORTANT]
> **No Premature Activation Invariant:**
> Checks and anti-pattern rules must **never** be activated before the corresponding codebase components exist. 
> Only Tier 1 rules (`spec-hygiene`, `harness-selftests`) are active in FCT-000. All future Android rules remain `PROPOSED` and inactive.
