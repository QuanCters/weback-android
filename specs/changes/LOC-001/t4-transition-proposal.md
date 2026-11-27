# LOC-001 T4 Policy, Variant & Validator Transition Proposal (Proposal Only — Non-Executing)

- **Change ID:** `LOC-001` (`T4` Transition Target Stage: `Task 5` / `L04A`)
- **Revision:** `1.2.0 (Stage L01F2 Remediated & Frozen)`
- **Risk Tier:** `T4 (Critical Risk)` per [policies/risk-tiers.md](../../../policies/risk-tiers.md) (`POL-RISK-01`)
- **Governance Authority:** Dual human approval required (`Product Owner` + `Lead Maintainer`) + external `TRANSITION_AUTHORIZATION` per [verification/checks/transition.py](../../../verification/checks/transition.py)
- **Status:** `PROPOSAL_ONLY_NOT_AUTHORIZED` *(Zero protected files, Gradle configs, manifests, CI variables, or validator scripts modified in Stage L01 / L01F / L01F2)*

---

## 1. Purpose & Non-Modification Invariant

Per [policies/verification-integrity.md](../../../policies/verification-integrity.md) §2 and [policies/quality-gates.md](../../../policies/quality-gates.md), `policies/checks.json`, `policies/`, `verification/`, `.github/workflows/`, and existing build manifests are protected assets.
In Stage `L01` / `L01F` / `L01F2` (and offline stages `L02A`–`L03A`), **zero** edits are made to those paths. This document specifies the exact atomic `L04A` T4 transition PR so the initial `INTERNET`-capable `local` APK enforces complete composition-root, source-set, `$`-aware multi-DEX, and verification-policy isolation from Day 1, while preserving all existing regression coverage (`FIND-L01R2-01`, `02`, `03`, `07`).

---

## 2. Exact `app/build.gradle.kts` Variant & Source-Set Topology (`L04A`)

### 2.1 Build Types & `BuildConfig` Fields
| BuildType / Variant | Application ID | Manifest Permissions | Composition Root & Trust Scope | `testBuildType` / CI Coverage |
|---|---|---|---|---|
| `debug` (Offline) | `com.weback.robot` | Zero external permissions (`INTERNET` forbidden) | `SimulatorRobotTransport` + `SyntheticReplayTelemetrySource` (`L03A`) + `src/cloudPilot/` (for legacy FCT-004 tests only, `PILOT_INTERNET_CAPABLE = false`); excludes `src/local/`. | `assembleDebug`, `lintDebug`, `testDebugUnitTest`, `apk_validator.py --connectivity-policy offline` |
| `release` (Offline) | `com.weback.robot` | Zero external permissions (`INTERNET` forbidden) | `OfflineAppContainer` (`pilotCapabilityBridge = null`); excludes **both** `src/cloudPilot/` and `src/local/`. | Structural source-set exclusion |
| `pilot` (Cloud Pilot) | `com.weback.robot.pilot` | `android.permission.INTERNET` only | `src/cloudPilot/` (`DefaultPilotContainer`, `AuthorizedPilotEndpointTrustPolicy` HTTPS/WSS `:443`, RFC1918 blocked, single-use tickets, `KeystoreAeadTokenStore`); excludes `src/local/`. | **Pinned `testBuildType = "pilot"`** (`assemblePilot`, `lintPilot`, `testPilotUnitTest`, `assemblePilotAndroidTest`, `apk_validator.py --connectivity-policy pilot`) |
| **`local` (New in `L04A`)** | **`com.weback.robot.local`** (`applicationIdSuffix = ".local"`) | **`android.permission.INTERNET` only** in `app/src/local/AndroidManifest.xml` | `OfflineAppContainer` / `LocalAppContainer` (`pilotCapabilityBridge = null`, **excludes `src/cloudPilot/` from compile & DEX**, zero cloud gateways/controllers/tokens; `ReceiveOnlyTcpSource` + `LocalLanTrustPolicy` added in `L04B`). | `assembleLocal`, `lintLocal`, `testLocalUnitTest`, `apk_validator.py --connectivity-policy local` |

### 2.2 Mandatory Gradle DSL & Source-Set Inclusion Matrix (`L04A`, Solving `FIND-L01R-04`, `FIND-L01R2-01`, `FIND-L01R2-02`)
1. **`defaultConfig`:** Add `buildConfigField("boolean", "LOCAL_LAN_CAPABLE", "false")` while preserving `PILOT_INTERNET_CAPABLE = false` and `APP_CONNECTIVITY_MODE = "OFFLINE"`.
2. **`buildTypes.create("local")`:**
   ```kotlin
   create("local") {
       initWith(getByName("debug"))
       applicationIdSuffix = ".local"
       matchingFallbacks += listOf("debug")
       buildConfigField("boolean", "PILOT_INTERNET_CAPABLE", "false")
       buildConfigField("boolean", "LOCAL_LAN_CAPABLE", "true")
       buildConfigField("String", "APP_CONNECTIVITY_MODE", "\"LOCAL_READ_ONLY\"")
   }
   ```
3. **Compose BOM & UI Test Manifest Scope (`localImplementation`):**
   ```kotlin
   add("localImplementation", platform(libs.androidx.compose.bom))
   add("localImplementation", libs.androidx.compose.ui.test.manifest)
   ```
4. **Additive Source Sets (Zero `setSrcDirs` Destructive Override!):**
   - `sourceSets.getByName("debug").java.srcDir("src/cloudPilot/java")`
   - `sourceSets.getByName("pilot").java.srcDir("src/cloudPilot/java")`
   - `sourceSets.getByName("testDebug").java.srcDir("src/testCloudPilot/java")`
   - `sourceSets.getByName("testPilot").java.srcDir("src/testCloudPilot/java")`
   *(Android Gradle inherits `app/src/test/{java,resources}` automatically across ALL debuggable build types — `debug`, `pilot`, AND `local` — and automatically binds `app/src/testLocal/java` to `testLocal`.)*
5. **Logical Source-Set Inclusion Matrix (`testDebugUnitTest`, `testLocalUnitTest`, `testPilotUnitTest`, `androidTest`):**

| Test / Resource Category & Exact Examples | Source-Set Directory | `testDebugUnitTest` | `testLocalUnitTest` | `testPilotUnitTest` | `androidTest` (`testBuildType = "pilot"`) |
|---|---|---|---|---|---|
| **Common offline, domain, protocol & UI tests** (`DomainPurityTest`, `DashboardScreenControlTest`, `DashboardViewModel*Test`, `SimulatorRobotTransportTest`, `DiagnosticLoggerTest`, `SecretRedactorTest`, `ReadOnlyPilotGuardTest`) | `app/src/test/java` | **Included** | **Included** | **Included** | N/A |
| **Shared test utilities** (`TestDispatcherProvider`) | `app/src/test/java` | **Included** | **Included** | **Included** | N/A |
| **Shared `L02A`–`L03A` HOSLAM & viewer tests + fixtures** (`Hoslam*Test`, `LocalReplayViewerTest`, `LocalMapTruthfulnessUiTest`, `resources/hoslam/*`) | `app/src/test/{java,resources}` | **Included** | **Included** | **Included** | N/A |
| **Relocated `cloudPilot` / session / Keystore / OkHttp / UI-pilot tests** (`45` files = `90` tests per `design.md` §1.1 Migration Inventory, incl. `ConnectivityRecoveryTest`, `AppContainerTest`, `PilotBuildSeparationTest`, `Fct004C*`, `MainActivityMockPilotFlowTest`, `PilotSignInSheetTest`, `EndpointTrustAndRedirectTest`) | `app/src/testCloudPilot/java` | **Included** | **Excluded** *(no `cloudPilot` classpath)* | **Included** | N/A |
| **`local`-only container, isolation, security scan, LAN trust, TCP, lifecycle & privacy tests** (`LocalAppContainerTest`, `LocalBuildSeparationTest`, `LocalSourceSecurityScanTest` in `L04A`; `L04B` socket/lifecycle/privacy tests) | `app/src/testLocal/java` | **Excluded** | **Included** | **Excluded** | N/A |
| **Pilot instrumentation suites** (`PilotDeviceAcceptanceInstrumentationTest` adapted to `DefaultPilotContainer`/`PilotAppContainer`, `KeystoreHardwareSecurityLevelTest`) | `app/src/androidTest/java` | N/A | N/A | N/A | **Included** (`com.weback.robot.pilot`) |
| **Future `L05` local instrumentation (opt-in only)** | `app/src/androidTestLocal/java` *(future opt-in `-PwebackTestBuildType=local`)* | N/A | Opt-in only | N/A | Separate explicit run |

6. **Mandatory `L04A` Migration Verification Gate (`FIX-01`, `FIX-02`):**
   Before `local` may pass `L04A`, maintainers MUST approve the complete Migration Inventory (`design.md` §1.1), confirm clean compilation of `testDebugUnitTest`, `testPilotUnitTest`, `testLocalUnitTest`, and `assemblePilotAndroidTest`, and inspect `verification/checks/gradle_test_adapter.py` parsed JUnit XML test identities (`classname#name`) proving:
   - `testDebugUnitTest` & `testPilotUnitTest` execute all `157` migrated legacy tests (`67` common + `90` `testCloudPilot`) plus all `L02A`–`L03A` tests (`0` missing/skipped);
   - `testLocalUnitTest` executes all `67` common offline tests (including `DashboardScreenControlTest`, `DomainPurityTest`), all `L02A`–`L03A` HOSLAM/replay tests, plus `LocalAppContainerTest`, `LocalBuildSeparationTest`, and `LocalSourceSecurityScanTest` (`0` missing/skipped).
7. **Instrumentation & Manifest Governance:**
   - Keep `testBuildType = "pilot"` **explicitly pinned** in `app/build.gradle.kts` (`L04A`).
   - `app/src/local/AndroidManifest.xml` declares solely `<uses-permission android:name="android.permission.INTERNET" />`.
   - `app/src/local/res/values/strings.xml` sets `<string name="app_name">WeBack Robot Local Map (Read-Only)</string>`.
   - `app/src/main/AndroidManifest.xml`, `app/src/pilot/AndroidManifest.xml`, and `app/src/main/res/xml/network_security_config.xml` remain untouched.

---

## 3. Symmetric 3-Way `$`-Aware `apk_validator.py` Extension (`L04A`, Solving `FIND-L01R-04` & `FIND-L01R2-03`)

*(Note: `verification/checks/apk_validator.py` is **NOT** modified during `L01F2`; exact edits execute only in `L04A` under `TRANSITION_AUTHORIZATION`.)*

1. Expand `--connectivity-policy` choices to `["offline", "pilot", "local"]` (`"offline"` default).
2. **Replace `;`-Terminated Prefix Matching with `$`-Aware Class-Family & Package Matching Across ALL `.dex` Files:**
   In `validate_dex_classes_in_apk(...)` (iterating all sorted `*.dex` files in the APK archive), add explicit per-policy DEX rule evaluation:
   ```python
   def matches_forbidden_dex_descriptor(cls_desc: str, rule: str) -> bool:
       if rule.endswith("/"):
           return cls_desc.startswith(rule)
       return cls_desc == f"{rule};" or cls_desc.startswith(f"{rule}$")
   ```
   - **Never** terminate single-class rules with `;` (`cls.startswith("...Foo;")` fails on `Foo$Companion;`, `Foo$1;`, and suspend lambdas `Foo$bar$2;`).
3. **Complete Verified Forbidden Class-Family Inventories (Checked Directly Against Canonical `184175a` Declarations):**
   - **`CLOUD_PILOT_DEX_RULES` (forbidden in `local`):**
     - *Package prefixes:* `Lcom/weback/robot/app/pilot/`, `Lcom/weback/robot/app/session/`, `Lcom/weback/robot/protocol/pilot/`, `Lcom/weback/robot/ui/pilot/` *(covers `PilotSessionController`, `SessionCoordinator`, `DeviceInventoryRepository`, `PilotCapabilityRestrictedAuthGateway`, `PilotCapabilityRestrictedInventoryGateway`, `PilotReadOnlyWebSocketTransport`, `PilotReadOnlyFixtureTransport`, `PilotHttpOperationGuard`, `PilotReadinessSheetKt`, `EphemeralPasswordBuffer`, `PilotSheetUiState`, and all their `$Companion`/coroutine/Compose lambda classes)*.
     - *Explicit class-family stems (no trailing `;`):*
       - Core container/bridge: `Lcom/weback/robot/core/PilotAppContainer`, `Lcom/weback/robot/core/DefaultPilotContainer`, `Lcom/weback/robot/core/PilotCapabilityBridgeImpl`, `Lcom/weback/robot/core/PilotContainerTestHooks`, `Lcom/weback/robot/core/InMemoryMockTokenStore`, `Lcom/weback/robot/core/NonPersistentSessionStore`
       - Auth/inventory/WS transport implementations: `Lcom/weback/robot/protocol/auth/AuthGateway`, `Lcom/weback/robot/protocol/auth/OfflineFixtureAuthGateway`, `Lcom/weback/robot/protocol/auth/OkHttpAuthGateway`, `Lcom/weback/robot/protocol/auth/RegionEndpointRouter`, `Lcom/weback/robot/protocol/inventory/DeviceInventoryGateway`, `Lcom/weback/robot/protocol/inventory/OfflineFixtureInventoryGateway`, `Lcom/weback/robot/protocol/inventory/OkHttpDeviceInventoryGateway`, `Lcom/weback/robot/protocol/ws/ConnectionConfig`, `Lcom/weback/robot/protocol/ws/OfflineFixtureRealtimeTransport`, `Lcom/weback/robot/protocol/ws/OkHttpWebSocketTransport`, `Lcom/weback/robot/protocol/ws/RealtimeSessionTransport`, `Lcom/weback/robot/protocol/ws/RealtimeSessionTransportKt`
       - Cloud security/trust/Keystore/export/password: `Lcom/weback/robot/security/AuthorizedPilotEndpointTrustPolicy`, `Lcom/weback/robot/security/AuthorizedPilotEndpointTrustPolicyKt`, `Lcom/weback/robot/security/IpAddressPolicy`, `Lcom/weback/robot/security/OriginProvenanceResult`, `Lcom/weback/robot/security/OriginProvenanceVerifier`, `Lcom/weback/robot/security/DynamicOriginEvaluation`, `Lcom/weback/robot/security/DynamicOriginConfirmationResult`, `Lcom/weback/robot/security/PilotActiveCallRegistry`, `Lcom/weback/robot/security/PilotClientFactory`, `Lcom/weback/robot/security/EndpointTrustPolicy`, `Lcom/weback/robot/security/EndpointTrustDecision`, `Lcom/weback/robot/security/ProductionEndpointTrustPolicy`, `Lcom/weback/robot/security/TestLoopbackTrustPolicy`, `Lcom/weback/robot/security/OkHttpSecurityGuard`, `Lcom/weback/robot/security/ExternalPilotAuthorizationLoader`, `Lcom/weback/robot/security/ExternalPilotAuthorizationKt`, `Lcom/weback/robot/security/AndroidInstalledApkVerifier`, `Lcom/weback/robot/security/FileBackedProvisionedPilotAuthorizationStore`, `Lcom/weback/robot/security/InMemoryProvisionedPilotAuthorizationStore`, `Lcom/weback/robot/security/ProvisionedAuthorizationLoadResult`, `Lcom/weback/robot/security/ProvisionedPilotAuthorizationStore`, `Lcom/weback/robot/security/AndroidKeystoreAeadCipherProvider`, `Lcom/weback/robot/security/KeystoreAeadTokenStore`, `Lcom/weback/robot/security/AeadCipherProvider`, `Lcom/weback/robot/security/TokenStore`, `Lcom/weback/robot/security/PasswordDigestStrategy`, `Lcom/weback/robot/security/Utf8TlsDefault`, `Lcom/weback/robot/security/PilotSanitizedExporter`, `Lcom/weback/robot/security/UriValidator`
   - **`LOCAL_LIVE_DEX_RULES` (forbidden in `offline` and `pilot`):**
     - `Lcom/weback/robot/protocol/local/ReceiveOnlyTcpSource`
     - `Lcom/weback/robot/security/LocalLanTrustPolicy`
     - `Lcom/weback/robot/security/LocalDiagnosticSanitizer`
4. **Symmetric `CONNECTIVITY_POLICY_CONFIG` & Line 370 Fix:**
   - `"offline"`: `app/build/outputs/apk/debug/app-debug.apk`, `com.weback.robot`, `0` external perms, `forbidden_variant_tokens = ("pilot", "app-pilot.apk", "local", "app-local.apk")`, `prohibited_dex_rules = PROHIBITED_DEX_PACKAGES + LOCAL_LIVE_DEX_RULES`.
   - `"pilot"`: `app/build/outputs/apk/pilot/app-pilot.apk`, `com.weback.robot.pilot`, `INTERNET` only, `forbidden_variant_tokens = ("debug", "app-debug.apk", "local", "app-local.apk")`, `prohibited_dex_rules = PROHIBITED_DEX_PACKAGES + LOCAL_LIVE_DEX_RULES`.
   - `"local"`: `app/build/outputs/apk/local/app-local.apk`, `com.weback.robot.local`, `INTERNET` only, `forbidden_variant_tokens = ("debug", "app-debug.apk", "pilot", "app-pilot.apk")`, `prohibited_dex_rules = PROHIBITED_DEX_PACKAGES + CLOUD_PILOT_DEX_RULES`.
   - Update `validate_apk_path_for_policy` line 370 to `if name_lower in ("app-debug.apk", "app-pilot.apk", "app-local.apk") and name_lower != expected_path.name:`.
5. **Required Positive & Negative `test_apk_validator.py` Oracles (`TEST-LOCAL-521`):**
   - Exact forbidden class match (`...PilotSessionController;` in `local`; `...ReceiveOnlyTcpSource;` in `offline`/`pilot`) -> FAIL
   - Forbidden `$Companion` match (`...OkHttpAuthGateway$Companion;`) -> FAIL
   - Forbidden coroutine/anonymous nested class (`...PilotSessionController$activatePilot$1;`) -> FAIL
   - Legitimate similarly named unrelated classes (`...DiagnosticLogger;`, `...LocalMapRepository;`, `...SyntheticReplayTelemetrySource;`) -> PASS
   - Forbidden class placed only in secondary `classes2.dex` (`...AndroidKeystoreAeadCipherProvider$Companion;`) -> FAIL
   - 3-way substitution rejection (`offline` <-> `pilot` <-> `local`) & manifest permission/flag checks -> FAIL
   - **Pre-`L04A` Prerequisite:** Re-verify complete class-family inventory before `L04A` G3/T4 approval.

---

## 4. Governed Policy Activation Lifecycle & CI Synchronization (`L04A` -> `L04B`, Solving `FIND-L01R2-07`)

### 4.1 Current Active Policy (`184175a`)
- `policies/checks.json` (`fd870ee6b2b324aa1d2bfbe84d9282df13708821cb7d291d2560d0a80a77bbc4`, `11` checks):
  `spec-hygiene`, `harness-selftests`, `gradle-wrapper-validation`, `android-lint`, `android-unit-tests`, `android-build`, `apk-validation`, `pilot-android-lint`, `pilot-android-unit-tests`, `pilot-android-build`, `pilot-apk-validation`.
- `policies/checks.baseline.json` (`e688b6fef22290281facf91d26f42ae53ff527ca7f6b599a750d24d66873985e`, `7` checks).

### 4.2 Proposed Additive Checks (`15` Total Checks)
Preserve **all `11` existing required checks verbatim** and add `4` symmetric `local` checks:
12. `local-android-lint`: `["./gradlew", "lintLocal", "--no-daemon"]` (`type: "command"`, `required: true`, `timeout_seconds: 300`)
13. `local-android-unit-tests`: `["python3", "-m", "verification.checks.gradle_test_adapter", "--task", "testLocalUnitTest", "--results-dir", "app/build/test-results/testLocalUnitTest"]` (`type: "test_suite"`, `required: true`, `timeout_seconds: 180`)
14. `local-android-build`: `["./gradlew", "assembleLocal", "--no-daemon"]` (`type: "command"`, `required: true`, `timeout_seconds: 300`)
15. `local-apk-validation`: `["python3", "verification/checks/apk_validator.py", "--connectivity-policy", "local"]` (`type: "command"`, `required: true`, `timeout_seconds: 60`)

### 4.3 End-to-End Lifecycle of Policy Activation & CI Sync (`transition.py`, `evidence.py`, `.github/workflows/ci.yml`)
1. **Current Approved Digest:** `fd870ee6b2b324aa1d2bfbe84d9282df13708821cb7d291d2560d0a80a77bbc4` (matches active `vars.APPROVED_POLICY_SHA256` on `184175a`).
2. **Candidate Policy Digest Calculation:** In `L04A`, compute exact `sha256sum` over the committed 15-check `policies/checks.json` bytes; value remains **`TO_BE_COMPUTED_IN_L04A_PR`** prior to `L04A` (`0` fabricated digests).
3. **Exact Old/New Baseline Handling:** In the `L04A` commit, copy the retiring 11-check policy (`fd870ee6...`) verbatim into `policies/checks.baseline.json` (replacing stale 7-check `e688b6...`), while writing the 15-check policy into `policies/checks.json`. Promote `LOC-001/traceability.json` `AC-506.1`..`508.2` `verification_check_id` atomically from provisional `L01F` placeholders to `local-apk-validation` / `local-android-unit-tests` (future checks remain `PLANNED` until `L04A` activates them).
4. **Candidate-Bound `TRANSITION_AUTHORIZATION`:** PO + Lead Maintainer supply external GitHub `vars.TRANSITION_AUTHORIZATION` JSON binding `candidate_sha`, `target_base_sha` (exact `main` HEAD before merge), `candidate_tree_sha`, `canonical_policy_sha256` (`L04A` 15-check digest), `retired_policies = ["e688b6fef22290281facf91d26f42ae53ff527ca7f6b599a750d24d66873985e", "fd870ee6b2b324aa1d2bfbe84d9282df13708821cb7d291d2560d0a80a77bbc4"]`, and all `15` `required_checks`.
5. **Candidate Verification Context (`ci.yml` + `transition.py`):** During `L04A` `pull_request` with valid `TRANSITION_AUTHORIZATION`, `transition.py` resolves `is_transition = True` and `policy_file = "policies/checks.json"`; `verification/run.py --lane candidate` runs all 15 checks; `evidence.py` audits all `15` checks against `candidate_sha`, `tree_sha`, and `canonical_policy_sha256`. Non-transition PRs prior to `L04A` (`L02A`–`L03A`) evaluate against `fd870ee6...` (`11` checks).
6. **Controlled Merge Prerequisites (`G4`/`G5`/`G6`):** 15/15 candidate checks `PASS`, independent `G5` review `PASS`, dual PO/Lead approval, clean single-parent squash-merge (`parent_shas == [target_base_sha]`, `tree_sha == candidate_tree_sha` per `transition.py` lines `154-165` — zero intervening commits on `main` during `L04A` merge window!).
7. **Mandatory Post-Merge Synchronization of `vars.APPROVED_POLICY_SHA256` (`FIND-L01R2-07`):** Immediately after `L04A` squash-merge push CI passes, an authorized repository maintainer/admin MUST update GitHub Actions variable `vars.APPROVED_POLICY_SHA256` from `fd870ee6...` to the newly landed 15-check `canonical_policy_sha256` AND clear `vars.TRANSITION_AUTHORIZATION` **before `L04B` opens**.
8. **Post-Merge steady-state Verification:** Subsequent `push` and `L04B` PRs run with cleared `TRANSITION_AUTHORIZATION` and updated `vars.APPROVED_POLICY_SHA256`, hitting `transition.py` lines `255-270` (`actual_sha == active_approved_policy_sha`), enforcing `policies/checks.json` (`15` required checks) directly.
9. **Failure & Recovery Behavior on Inconsistent Policy / CI State:**
   - **Silent 11-check fallback hazard:** If `L04A` merges `checks.baseline.json = fd870ee6...` but maintainers forget to update `vars.APPROVED_POLICY_SHA256`, `transition.py` lines `271-287` resolve `BASELINE_POLICY_PATH` (`11` checks) instead of `15`! Therefore `L04B` readiness explicitly verifies CI `policy_file == policies/checks.json` (`15` checks) and treats `11`-check fallback as a **hard stop** until `vars.APPROVED_POLICY_SHA256` is synchronized.
   - **Mismatched non-matching digest:** If `vars.APPROVED_POLICY_SHA256` matches neither `policies/checks.json` nor `policies/checks.baseline.json` (and `TRANSITION_AUTHORIZATION` is absent/invalid), `transition.py` lines `289-294` fail closed with `TransitionError`; recovery requires maintainer inspection of `main` HEAD `policies/checks.json` SHA-256 and restoring matching `vars.APPROVED_POLICY_SHA256` (never weakening checks or fabricating digests).
