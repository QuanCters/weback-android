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
- **Evidence:** `test_e2e_cli_invocation_without_pythonpath` invoked live `./gradlew testDebugUnitTest` inside harness selftests, exceeding 60-second execution budget on cold unprimed environment (timed out at 62.01s; historical initial CI run ID reference unavailable/unrecorded).
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

### DEF-2026-030: DEX Parser Fail-Closed Bounds & Header Validation
- **Classification:** `CONFIRMED_APPLICATION_DEFECT`
- **Requirement / Invariant:** `REQ-ANDR-108`
- **Severity:** `P0`
- **Affected Module:** `verification/checks/apk_validator.py`
- **Evidence:** `parse_dex_classes` returned an empty list on malformed DEX or parsing exceptions without failing closed, allowing incomplete class inspection to pass silently.
- **Reproduction:** Feed truncated DEX header or invalid table offsets to `parse_dex_classes`.
- **Root Cause:** Broad try/except catching `Exception` and returning empty list instead of raising `ValueError` on corrupt or malformed DEX input.
- **Regression Test Reference:** `verification/selftests/test_apk_validator.py::test_dex_negative_malformed_header`
- **Pre-Fix RED Result:** `parse_dex_classes returned [] (silent suppression) and validate_dex_classes_in_apk returned True`
- **Post-Fix GREEN Result:** `PASS: parse_dex_classes raises ValueError and validate_dex_classes_in_apk fails closed`
- **Generalization Decision:** `false`
- **Review Status:** `RESOLVED`

### DEF-2026-031: Launcher Intent-Filter Co-Occurrence Validation
- **Classification:** `CONFIRMED_APPLICATION_DEFECT`
- **Requirement / Invariant:** `REQ-ANDR-108`
- **Severity:** `P1`
- **Affected Module:** `verification/checks/apk_validator.py`
- **Evidence:** `validate_manifest_xml_structural` permitted `android.intent.action.MAIN` and `android.intent.category.LAUNCHER` to reside in separate `<intent-filter>` elements of `MainActivity`.
- **Reproduction:** Feed manifest XML where MAIN action and LAUNCHER category are defined in two distinct `<intent-filter>` children.
- **Root Cause:** Intent filter validation iterated over filters separately and stored presence in global boolean flags instead of verifying co-occurrence within the exact same filter.
- **Regression Test Reference:** `verification/selftests/test_apk_validator.py::test_manifest_negative_main_and_launcher_in_separate_filters`
- **Pre-Fix RED Result:** `validate_manifest_xml_structural returned True (accepted split filters)`
- **Post-Fix GREEN Result:** `PASS: Rejects manifests where MAIN and LAUNCHER are in separate filters`
- **Generalization Decision:** `false`
- **Review Status:** `RESOLVED`

### DEF-2026-032: Runner-to-Auditor Decoupling During Policy Transition
- **Classification:** `CONFIRMED_APPLICATION_DEFECT`
- **Requirement / Invariant:** `C-008`
- **Severity:** `P0`
- **Affected Module:** `verification/checks/transition.py`
- **Evidence:** Candidate runner executed 2 baseline checks while authoritative auditor received transition authorization but audited the 2-check report, creating runner-to-auditor decoupling and preventing authoritative acceptance.
- **Reproduction:** Run CI workflow with transition authorization where candidate lane runs baseline policy and evidence auditor receives transition authorization.
- **Root Cause:** Candidate runner and evidence auditor did not resolve context through a shared deterministic state machine binding exact policy file, digest, required checks, and report path.
- **Regression Test Reference:** `verification/selftests/test_transition.py::test_07_candidate_runner_to_auditor_consistency_TEST_TRN_007`
- **Pre-Fix RED Result:** `Auditor received 2-check report while transition authorization specified 7 checks, or runner executed old checks in candidate lane`
- **Post-Fix GREEN Result:** `PASS: transition.py resolves unified context for runner and auditor; inconsistency strictly rejected`
- **Generalization Decision:** `false`
- **Review Status:** `RESOLVED`


### DEF-2026-033: Incorrect Event Inference in Candidate Runner During Squash-Merge Push
- **Classification:** `CONFIRMED_APPLICATION_DEFECT`
- **Requirement / Invariant:** `C-008`
- **Severity:** `P0`
- **Affected Module:** `verification/run.py`
- **Evidence:** `verification/run.py` inferred `"event_name": "pull_request" if tested_sha != base_sha else "push"`, misclassifying post-squash-merge `push` commits (where the squash commit SHA differs from both the authorized PR head SHA and previous `main` SHA) as `pull_request` and rejecting valid post-merge transitions.
- **Reproduction:** Execute `verification/run.py --lane candidate` on a real squash-merged commit with `TRANSITION_AUTHORIZATION` active (`TEST_TRN_011`).
- **Root Cause:** Candidate runner lacked an explicit `--event-name` parameter (and CI did not pass `--event-name "${{ github.event_name }}"`), relying on an invalid SHA-comparison heuristic, while `transition.py` and `evidence.py` needed strict single-parent `[auth_base]` verification in `push` context.
- **Regression Test Reference:** `verification/selftests/test_transition.py::test_11_e2e_runner_squash_merge_lifecycle_TEST_TRN_011`
- **Pre-Fix RED Result:** `run.py: error: unrecognized arguments: --event-name pull_request` (`TEST_RUN_028` also passed without `--event-name` via SHA inference)
- **Post-Fix GREEN Result:** `PASS: Explicit --event-name propagated from CI through run.py, transition.py, and evidence.py; missing/unsupported events rejected and full PR -> squash-merge push lifecycle verified`
- **Generalization Decision:** `false`
- **Review Status:** `RESOLVED`


### DEF-2026-034: Selftest Environment Leakage Under Active Transition Authorization
- **Classification:** `CONFIRMED_APPLICATION_DEFECT`
- **Requirement / Invariant:** `C-008`
- **Severity:** `P0`
- **Affected Module:** `verification/selftests/test_cli.py`
- **Evidence:** Authoritative transition CI run `45530115994` attempt 2 (job `138925613934`) failed `harness-selftests` (`184 passed, 1 failed`) on `test_candidate_alternate_config_prohibited_TEST_RUN_010` when `TRANSITION_AUTHORIZATION` was active in the runner environment.
- **Reproduction:** Run `TEST_RUN_029` (or `test_adapter.py --discover verification/selftests`) with `TRANSITION_AUTHORIZATION`, `APPROVED_POLICY_SHA256`, and `GITHUB_EVENT_NAME=pull_request` set in the parent process environment.
- **Root Cause:** `TEST_RUN_009`, `TEST_RUN_010`, `test_fast_lane_with_custom_config`, and `TEST_RUN_020` in `verification/selftests/test_cli.py` invoked `verification/run.py` subprocesses without `env=self._clean_env()`, causing them to inherit host `TRANSITION_AUTHORIZATION` and hit the missing `--event-name` guard before reaching their intended assertions.
- **Regression Test Reference:** `verification/selftests/test_cli.py::test_cli_isolated_tests_retain_behavior_under_active_transition_env_TEST_RUN_029`
- **Pre-Fix RED Result:** `AssertionError: 'Alternate configuration not approved for candidate lane' not found in "Error: Explicit --event-name ('pull_request' or 'push') is required for authoritative transition verification."`
- **Post-Fix GREEN Result:** `PASS: TEST_RUN_009, TEST_RUN_010, test_fast_lane_with_custom_config, TEST_RUN_020, and TEST_RUN_029 pass with isolated self._clean_env() even when parent process holds active transition authorization variables`
- **Generalization Decision:** `true`
- **Review Status:** `RESOLVED`