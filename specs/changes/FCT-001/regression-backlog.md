# FCT-001 Regression Backlog & Remediated Defect Catalog

**Change ID:** `FCT-001`  
**Milestone:** `FCT-001`  
**Document ID:** `SPEC-REG-FCT001`  
**Version:** `1.0.0 (Remediation & Acceptance Gate Revision)`  
**Status:** `RESOLVED (Remediated under FIX-001 through FIX-006)`  

---

## 1. Principles of Defect Governance

In strict accordance with Constitution Invariant `C-009` (Defect-to-Regression Lifecycle), `policies/verification-integrity.md`, and SDD 2.0:
- Every discovered defect in code, test harness, or verification logic must follow:
  $$\text{Detect} \rightarrow \text{Reproduce} \rightarrow \text{Root Cause} \rightarrow \text{RED Test} \rightarrow \text{Fix} \rightarrow \text{GREEN} \rightarrow \text{Verify Verifier} \rightarrow \text{Review} \rightarrow \text{Permanent Protection}$$
- Defect IDs for milestone `FCT-001` strictly begin at `DEF-2026-025`, preserving IDs `DEF-2026-001` through `DEF-2026-024` for milestone `FCT-000`.
- All remediated defects include independently verifiable automated regression test oracles.

---

## 2. Remediated Defect Catalog (`verification/defects.jsonl`)

### DEF-2026-025: StateFlow Collection Lifespan in DashboardViewModelTest
- **Classification:** `CONFIRMED_APPLICATION_DEFECT`
- **Requirement / Invariant:** `REQ-ANDR-106`
- **Severity:** `P1`
- **Affected Module:** `app/src/test/java/com/weback/robot/ui/DashboardViewModelTest.kt`
- **Evidence:** `DashboardViewModelTest` failed when testing `uiState` emission because `SharingStarted.WhileSubscribed(5000)` delayed emission until subscriber collected, and test scope cancelled before transformation completed.
- **Reproduction:** Run `./gradlew testDebugUnitTest --tests com.weback.robot.ui.DashboardViewModelTest` with standard test coroutine dispatcher without advancing scheduler or unconfined dispatcher.
- **Root Cause:** StateFlow `WhileSubscribed` strategy paired with background scope queued emission without immediate collection in standard test scope.
- **Regression Test Reference:** `app/src/test/java/com/weback/robot/ui/DashboardViewModelTest.kt::testTelemetryUpdatesCollectedAndEmitted`
- **Pre-Fix RED Result:** `FAILED: expected:<2> but was:<1>`
- **Post-Fix GREEN Result:** `PASS: 9/9 tests pass with UnconfinedTestDispatcher`
- **Generalization Decision:** `false`
- **Review Status:** `RESOLVED`

### DEF-2026-026: Android SDK Discovery Fallback in Subprocesses
- **Classification:** `CONFIRMED_APPLICATION_DEFECT`
- **Requirement / Invariant:** `REQ-ANDR-101`
- **Severity:** `P1`
- **Affected Module:** `verification/checks/gradle_test_adapter.py`
- **Evidence:** Executing `./gradlew` in subprocess failed with `SDK location not found` when environment variable `ANDROID_HOME` was not explicitly inherited by the subshell.
- **Reproduction:** Run `python3 -m verification.checks.gradle_test_adapter` in clean environment with unexported `ANDROID_HOME`.
- **Root Cause:** Subprocess invocation did not detect default SDK location `~/.android/sdk` when `ANDROID_HOME` was unset.
- **Regression Test Reference:** `verification/selftests/test_gradle_test_adapter.py`
- **Pre-Fix RED Result:** `SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path in your project's local properties file.`
- **Post-Fix GREEN Result:** `PASS: gradle_test_adapter automatically detects and exports ANDROID_HOME`
- **Generalization Decision:** `true`
- **Review Status:** `RESOLVED`

### DEF-2026-027: Coupling of Harness Selftests to Real Gradle Compilation
- **Classification:** `CONFIRMED_APPLICATION_DEFECT`
- **Requirement / Invariant:** `REQ-HARN-005`
- **Severity:** `P0`
- **Affected Module:** `verification/selftests/test_gradle_test_adapter.py`
- **Evidence:** `test_e2e_cli_invocation_without_pythonpath` invoked live `./gradlew testDebugUnitTest` inside harness selftests, exceeding 60-second execution budget on cold CI runner (timed out at 62.01s in CI run 45439831283).
- **Reproduction:** Run `python3 verification/checks/test_adapter.py --discover verification/selftests` on clean environment without warm Gradle daemon.
- **Root Cause:** Baseline verification harness selftests depended on heavy Android toolchain compilation and external network artifact resolution.
- **Regression Test Reference:** `verification/selftests/test_gradle_test_adapter.py::test_e2e_cli_invocation_without_pythonpath`
- **Pre-Fix RED Result:** `TIMEOUT (duration 62.01s > 60.0s)`
- **Post-Fix GREEN Result:** `PASS: harness selftests execute in 17.2s using isolated executable fixture`
- **Generalization Decision:** `true`
- **Review Status:** `RESOLVED`

### DEF-2026-028: Malformed Activity Component & Launch Validation in Smoke Test
- **Classification:** `CONFIRMED_APPLICATION_DEFECT`
- **Requirement / Invariant:** `REQ-ANDR-108`
- **Severity:** `P1`
- **Affected Module:** `verification/checks/android_smoke_test.py`
- **Evidence:** Component name was formatted with redundant dot `com.weback.robot/.com.weback.robot.MainActivity` and `am start` exit code 0 was trusted despite potential runtime launch errors.
- **Reproduction:** Run `python3 verification/checks/android_smoke_test.py` against ADB device.
- **Root Cause:** Incorrect string concatenation for ADB activity component and missing stdout error inspection (`Error:`, `Exception`).
- **Regression Test Reference:** `verification/selftests/test_android_smoke_test.py::test_smoke_runner_argv_construction`
- **Pre-Fix RED Result:** `Activity not found: com.weback.robot/.com.weback.robot.MainActivity`
- **Post-Fix GREEN Result:** `PASS: Exact component com.weback.robot/com.weback.robot.MainActivity and am start error parsing verified`
- **Generalization Decision:** `false`
- **Review Status:** `RESOLVED`

### DEF-2026-029: Permissive Manifest Substring & Omitted DEX Bytecode Inspection
- **Classification:** `CONFIRMED_APPLICATION_DEFECT`
- **Requirement / Invariant:** `REQ-ANDR-108`
- **Severity:** `P0`
- **Affected Module:** `verification/checks/apk_validator.py`
- **Evidence:** APK validator relied on string search in text output of `apkanalyzer manifest print` instead of structural XML hierarchy, and lacked Dalvik bytecode inspection.
- **Reproduction:** Feed manifest containing launcher intent-filter in non-exported or secondary activity, or APK containing prohibited classes in DEX.
- **Root Cause:** Substring matching failed to bind `<intent-filter>` tags to specific exported activities, and lack of DEX class definition parser.
- **Regression Test Reference:** `verification/selftests/test_apk_validator.py::test_validate_manifest_xml_structural_missing_launcher_category`
- **Pre-Fix RED Result:** `AssertionError: Substring check accepted non-exported activity and missed DEX inspection`
- **Post-Fix GREEN Result:** `PASS: Structural XML validation and parse_dex_classes enforce 100% manifest and DEX integrity`
- **Generalization Decision:** `true`
- **Review Status:** `RESOLVED`
