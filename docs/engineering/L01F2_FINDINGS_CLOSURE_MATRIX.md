# L01F2 Findings Closure Matrix (`LOC-001` rev `1.2.0`)

- **Reviewed Revision:** `LOC-001` rev `1.1.0` (manifest `016f04018555cd549ae347b02ad14a5e5663379a13a7deb31d87ca60d09074e8`)
- **Governance Rule:** Self-approval is prohibited. All seven new findings (`FIND-L01R2-01`..`07`) and all four reopened/partial original findings (`FIND-L01R-03`, `04`, `05`, `06`) carry status **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`**. All six previously **`CLOSED`** original findings (`FIND-L01R-01`, `02`, `07`, `08`, `09`, `10`) remain **`PRESERVED_CLOSED_PENDING_L01R3_CONFIRMATION`**.

---

## 1. New `L01R2` Findings Ledger (`FIND-L01R2-01` .. `FIND-L01R2-07`)

### `FIND-L01R2-01`
- **Severity & Original Finding Link:** **`P1`** | Resolves regression on **`FIND-L01R-03`**
- **Original Evidence (`L01R2`):** `design.md` §1.1 stripped cloud/session members and `DefaultAppContainer` out of `src/main/` `AppContainer` while `design.md` line 38, `tasks.md` line 72, and `t4-transition-proposal.md` line 52 simultaneously claimed all `157` existing tests in `app/src/test/java/` remain completely untouched — causing compile failures across `12` `src/test/` files + `PilotDeviceAcceptanceInstrumentationTest.kt` (`src/androidTest/`), while leaving `OfflineFixture*`/`SessionCoordinator`/`DeviceInventoryRepository`/`EndpointTrustPolicy`/`PilotSanitizedExporter` ownership ambiguous.
- **Minimal Correction (`FIX-01`):** Replaced the contradictory "untouched tests" claim with a behavioral preservation invariant; enumerated exact `src/main/` vs `src/cloudPilot/` class relocation (`DefaultPilotContainer`, `PilotAppContainer`, `PilotCapabilityBridgeImpl`, etc.); authored a complete per-file **Legacy Test & Instrumentation Migration Inventory** (`13` Group-1 adapted container/pilot-UI tests + `1` `PilotDeviceAcceptanceInstrumentationTest` + `32` Group-3 relocated cloud/gateway/keystore tests = `45` unit test files moved to `app/src/testCloudPilot/java/` in `L04A`); added explicit `Task 5` (`L04A`) allowed paths; and required approved inventory + clean compile + JUnit XML identity verification via `gradle_test_adapter.py` + `LocalAppContainerTest`/`LocalBuildSeparationTest` equivalents before `local` passes `L04A`. Zero migrations performed in `L01F2`.
- **Changed Document & Exact Section(s):** `proposal.md` (`ADR-L002`, `ADR-L009`), `requirements.md` (`AC-506.1`, `AC-506.2`, `AC-509.1`), `design.md` (§1.1–§1.2), `tasks.md` (`Task 5`), `t4-transition-proposal.md` (§2.1–§2.2), `validation.md` (`TEST-LOCAL-514`, `522`), `L01F_T4_ARCHITECTURE_DECISIONS.md` (§1).
- **Revised Acceptance Criterion:** `AC-506.1` (`L04A`), `AC-506.2` (`L04B`), `AC-509.1` (`L04A`).
- **Future Verification Oracle:** `TEST-LOCAL-514`, `TEST-LOCAL-521`, `TEST-LOCAL-522` (`L04A` `gradle_test_adapter.py` JUnit XML identity match across `testDebugUnitTest`, `testPilotUnitTest`, `testLocalUnitTest`, plus `assemblePilotAndroidTest`).
- **Remaining Limitations:** Migration and Gradle compilation execute strictly in `L04A` after T4 review; unit-test identity proof is specification-only until `L04A`.
- **Status:** **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`**

### `FIND-L01R2-02`
- **Severity & Original Finding Link:** **`P1`** | Resolves residual **`FIND-L01R-04`**
- **Original Evidence (`L01R2`):** `t4-transition-proposal.md` line 52 and `design.md` line 38 used `testLocal.java.setSrcDirs(listOf("src/testLocalShared/java", "src/testLocal/java"))`, excluding `app/src/test/{java,resources}` where `DashboardScreenControlTest`, `DomainPurityTest`, `TestDispatcherProvider`, and all planned `L02A`–`L03A` HOSLAM/replay tests & `hoslam/` resources live, while claiming those tests run under `testLocalUnitTest`.
- **Minimal Correction (`FIX-02`):** Eliminated `testLocal.java.setSrcDirs(...)` completely. Retained `app/src/test/{java,resources}` natively as the common offline/domain/UI/`L02A`–`L03A` source set inherited by **`testDebugUnitTest`**, **`testLocalUnitTest`**, AND **`testPilotUnitTest`**. Added `testDebug.java.srcDir("src/testCloudPilot/java")` & `testPilot.java.srcDir("src/testCloudPilot/java")`, native `app/src/testLocal/java/`, complete 7-row Source-Set Inclusion Matrix, and mandatory `L04A` JUnit XML identity verification proving every common test (`DashboardScreenControlTest`, `DomainPurityTest`, `TestDispatcherProvider`, `L02A`–`L03A`) + `local`-only test actually runs in `testLocalUnitTest`.
- **Changed Document & Exact Section(s):** `requirements.md` (`AC-506.1`, `AC-506.2`), `design.md` (§1.1 item 2 & §1.2), `tasks.md` (`Tasks 2–5`), `t4-transition-proposal.md` (§2.2 items 4–6), `validation.md` (`TEST-LOCAL-502`..`514`, `521`), `L01F_T4_ARCHITECTURE_DECISIONS.md` (§1.2), `L01_SPEC_REVIEW_PACKET.md` (§4).
- **Revised Acceptance Criterion:** `AC-506.1` (`L04A`), `AC-506.2` (`L04B`), `AC-509.1` (`L04A`).
- **Future Verification Oracle:** `TEST-LOCAL-514`, `TEST-LOCAL-521`, `TEST-LOCAL-522`.
- **Remaining Limitations:** JUnit XML execution verification runs in `L04A` when `local` buildType is introduced.
- **Status:** **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`**

### `FIND-L01R2-03`
- **Severity & Original Finding Link:** **`P1`** | Resolves residual **`FIND-L01R-03`** & **`FIND-L01R-04`**
- **Original Evidence (`L01R2`):** `apk_validator.py` line 174 uses `cls.startswith(prohibited)`, yet `t4-transition-proposal.md` lines 68–70 prescribed `;`-terminated descriptors (which miss `$Companion`, `$connect$1` coroutine state machines, and anonymous lambdas `$...`) and omitted `DefaultPilotContainer`/`PilotCapabilityBridgeImpl`, `PilotCapabilityRestrictedInventoryGateway`, `PilotReadOnlyWebSocketTransport`, `AndroidKeystoreAeadCipherProvider`, and `PilotReadinessSheet`.
- **Minimal Correction (`FIX-03`):** Specified `$`-aware class-family matching (`cls == f"{stem};" or cls.startswith(f"{stem}$")`) + package prefixes (`app/pilot/`, `app/session/`, `protocol/pilot/`, `ui/pilot/`) evaluated across **all `.dex` files** in the APK; inspected actual canonical packages/declarations in `184175a` and enumerated complete `CLOUD_PILOT_DEX_RULES` and `LOCAL_LIVE_DEX_RULES`; added 7 positive/negative oracle categories (`$Companion`, `$1` lambda, secondary `classes2.dex`, unrelated similar names, 3-way substitution) to `TEST-LOCAL-521`/`TEST-LOCAL-514`; required complete class-family verification before `L04A` approval. Zero edits to `apk_validator.py` in `L01F2`.
- **Changed Document & Exact Section(s):** `t4-transition-proposal.md` (§3 items 2–5), `requirements.md` (`AC-506.1`, `AC-506.2`, `AC-509.1`), `risk-assessment.md` (`THREAT-501`), `tasks.md` (`Task 5`), `validation.md` (`TEST-LOCAL-514`, `TEST-LOCAL-521`), `L01F_T4_ARCHITECTURE_DECISIONS.md` (§3).
- **Revised Acceptance Criterion:** `AC-506.1`, `AC-506.2`, `AC-509.1`.
- **Future Verification Oracle:** `TEST-LOCAL-514`, `TEST-LOCAL-521` (`test_apk_validator.py`).
- **Remaining Limitations:** `apk_validator.py` and `test_apk_validator.py` edits execute solely in approved `L04A`.
- **Status:** **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`**

### `FIND-L01R2-04`
- **Severity & Original Finding Link:** **`P1`** | Resolves residual **`FIND-L01R-05`**
- **Original Evidence (`L01R2`):** `design.md` §3 Mermaid diagram (`lines 123, 128, 133, 138`) transitioned `Background` and `Rotation` directly to `Idle`, contradicting §3.2 Retention Table (`lines 167–168`: `Disconnected(BACKGROUNDED)` and `Disconnected(CONFIG_CHANGE_RECONNECT_REQUIRED)`), and omitted `Rotation`/`UserStart` behavior from terminal `Disconnected`/`Error` states.
- **Minimal Correction (`FIX-04`):** Established one authoritative **Normative State Transition Table** (`design.md` §3.1) covering every state x event cell (`Connecting`, `WaitingForMap`, `Streaming`, `Stale`, `Disconnected(*)`, `Error(*)`); updated §3.2 Mermaid `stateDiagram-v2` 1-to-1 (`Stop / TargetChange / ScreenExit -> Idle`; `Background / Rotation -> Disconnected`; terminal `UserStart -> Connecting`, `Stop/Clear/Exit/TargetChange -> Idle`, `Background/Rotation -> Disconnected`); standardized `SESSION_LIMIT_REACHED`; and preserved HOSLAM framer `EndOfStream` vs `TruncatedFrame` unchanged.
- **Changed Document & Exact Section(s):** `design.md` (§3.1–§3.3), `requirements.md` (`AC-508.1`), `validation.md` (`TEST-LOCAL-518`, `519`), `risk-assessment.md` (`THREAT-507`), `tasks.md` (`Task 6`), `L01F_PROTOCOL_CONTRACT_DECISIONS.md` (§4).
- **Revised Acceptance Criterion:** `AC-508.1`.
- **Future Verification Oracle:** `TEST-LOCAL-518`, `TEST-LOCAL-519`.
- **Remaining Limitations:** Socket/lifecycle execution verified in future `L04B`.
- **Status:** **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`**

### `FIND-L01R2-05`
- **Severity & Original Finding Link:** **`P2`** | Resolves residual **`FIND-L01R-06`**
- **Original Evidence (`L01R2`):** `AC-508.2`, `design.md` line 191, `tasks.md` Task 6 line 87, and `validation.md` `TEST-LOCAL-517` mandated editing shared `SecretRedactor.kt` with a global IPv4 regex affecting frozen cloud pilot (`DiagnosticLogger`/`PilotSanitizedExporter`) and placing `SecretRedactorTest.kt` outside `testLocalUnitTest`.
- **Minimal Correction (`FIX-05`):** Removed `SecretRedactor.kt` and `SecretRedactorTest.kt` from `Task 6` allowed paths and all `LOC-001` requirements/tests (preserving frozen cloud/offline redaction untouched). Replaced with construction-time privacy-preserving `LocalDiagnosticEvent` (`app/local/`) + `LocalDiagnosticSanitizer` (`app/src/local/java/com/weback/robot/security/`), structurally excluding raw/formatted IPs, SSIDs/BSSIDs, raw socket exception messages, arbitrary `Throwable`s, JWTs/creds, binary payloads, and raster pixels, verified by negative tests in `LocalPrivacyRedactionTest` (`src/testLocal/`, `TEST-LOCAL-517`).
- **Changed Document & Exact Section(s):** `requirements.md` (`AC-508.2`), `design.md` (§1.2, §4 item 4), `tasks.md` (`Task 6`), `validation.md` (`TEST-LOCAL-517`), `risk-assessment.md` (`THREAT-505`), `t4-transition-proposal.md` (§3 `LOCAL_LIVE_DEX_RULES`), `L01F_T4_ARCHITECTURE_DECISIONS.md` (§4).
- **Revised Acceptance Criterion:** `AC-508.2`.
- **Future Verification Oracle:** `TEST-LOCAL-517` (`LocalPrivacyRedactionTest`).
- **Remaining Limitations:** Implemented and tested in `L04B`.
- **Status:** **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`**

### `FIND-L01R2-06`
- **Severity & Original Finding Link:** **`P2`** | Related to **`FIND-L01R-07`**
- **Original Evidence (`L01R2`):** `hoslam-evidence-and-unknowns.md` §2 (`lines 26, 46`) and `AC-504.1` conflated `E-002` observed framing (`(L-16)%8 == 0`, `1..144` slots) with unverified `"candidate float32 LE pairs"` and defensive `N=0`/`NaN`/`Infinity` codec policies.
- **Minimal Correction (`FIX-06`):** Split `hoslam-evidence-and-unknowns.md` §2 and `AC-504.1`/`AC-504.2` explicitly into three epistemic categories: (1) Capture-Observed (`E-002`: `(L-16)%8 == 0`, `1..144` 8-byte slots); (2) Defensive Codec Constraint (`L02B`: pure opaque octet-length check `L >= 16 && (L-16)%8 == 0`, zero IEEE-754 float parsing or coordinate arithmetic, explicit `N=0` defensive rule rather than vendor evidence); and (3) Unverified Vendor Semantics (`U04`: float32 hypothesis vs fixed-point/int32, `N=0`/non-finite firmware validity, units, pose, heading). Updated `TEST-LOCAL-508` to verify zero float decoding.
- **Changed Document & Exact Section(s):** `hoslam-evidence-and-unknowns.md` (§2–§3 `U04`), `requirements.md` (`AC-504.1`, `AC-504.2`), `validation.md` (`TEST-LOCAL-508`), `risk-assessment.md` (`THREAT-506`), `tasks.md` (`Task 3`), `traceability.json` (`AC-504.1`, `AC-504.2`), `L01F_PROTOCOL_CONTRACT_DECISIONS.md` (§3).
- **Revised Acceptance Criterion:** `AC-504.1`, `AC-504.2`.
- **Future Verification Oracle:** `TEST-LOCAL-508`.
- **Remaining Limitations:** `U04` semantics remain unresolved until future `L07+` physical research studies.
- **Status:** **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`**

### `FIND-L01R2-07`
- **Severity & Original Finding Link:** **`P3`** | Resolves residual **`FIND-L01R-04`**
- **Original Evidence (`L01R2`):** `t4-transition-proposal.md` §4.3 omitted post-`L04A`-merge synchronization of repository variable `vars.APPROVED_POLICY_SHA256` (which `.github/workflows/ci.yml` lines 72/122 and `transition.py` lines 253–287 use — stale value causes silent fallback to `11`-check `checks.baseline.json` during `L04B`); also `traceability.json` single-string schema omitted secondary checks on `AC-505.1` and `AC-506.2`.
- **Minimal Correction (`FIX-07`):** Added complete 9-item §4.3 **Governed Policy Activation Lifecycle & CI Synchronization** to `t4-transition-proposal.md` covering current `fd870ee6...`, `TO_BE_COMPUTED_IN_L04A_PR` digest, candidate-bound `TRANSITION_AUTHORIZATION`, baseline update `e688b6... -> fd870ee6...`, `pull_request` + single-parent squash-merge `push` verification, mandatory post-merge `vars.APPROVED_POLICY_SHA256` sync & clearing `TRANSITION_AUTHORIZATION` prior to `L04B`, and failure/fallback/recovery semantics. Added explicit `secondary_verification_checks` on `AC-501.2`, `AC-505.1`, `AC-506.1`, `AC-506.2`, `AC-509.1` in `traceability.json` without fabricating active checks.
- **Changed Document & Exact Section(s):** `t4-transition-proposal.md` (§4.3 items 1–9), `requirements.md` (`AC-509.1`), `tasks.md` (`Task 5`, `Task 6`), `validation.md` (`TEST-LOCAL-521`), `traceability.json` (`schema_limitation_note`, `AC-501.2`, `505.1`, `506.1`, `506.2`, `509.1`), `L01F_T4_ARCHITECTURE_DECISIONS.md` (§3), `L01F_UPDATED_TRACEABILITY_REPORT.md` (§1–§2).
- **Revised Acceptance Criterion:** `AC-509.1`, `AC-505.1`, `AC-506.2`.
- **Future Verification Oracle:** `TEST-LOCAL-521` (+ pre-`L04B` CI variable sync verification gate).
- **Remaining Limitations:** `.github/workflows/ci.yml`, `policies/`, and repository variables remain untouched in `L01F2`; maintainer action occurs strictly at `L04A` merge.
- **Status:** **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`**

---

## 2. Original `L01R` Findings Crosswalk (`FIND-L01R-01` .. `FIND-L01R-10`)

| Original Finding | `L01R2` Verdict | `L01F2` Linkage / Action | Current Status |
|---|---|---|---|
| `FIND-L01R-01` (`P1`) | `CLOSED` | Preserved intact (single-stage 22 ACs; `Task 2` `test/resources/hoslam/` + SHA import + merged-spec prereq; now inherited cleanly by all variants without `setSrcDirs`) | `PRESERVED_CLOSED_PENDING_L01R3_CONFIRMATION` |
| `FIND-L01R-02` (`P1`) | `CLOSED` | Preserved intact (`HoslamStreamFramer`, `0xFFL` shifts, `FrameTooLarge`, boundary `EndOfStream` vs `TruncatedFrame`, `frameStartOffset`, `MAP_LENGTH_*`, zero ZLIB) | `PRESERVED_CLOSED_PENDING_L01R3_CONFIRMATION` |
| **`FIND-L01R-03`** (`P1`) | `REGRESSION_INTRODUCED` | Closed regression via **`FIND-L01R2-01` (`FIX-01`)** and **`FIND-L01R2-03` (`FIX-03`)** (`design.md` §1.1, `t4-transition-proposal.md` §2–§3) | **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`** |
| **`FIND-L01R-04`** (`P1`) | `PARTIALLY_RESOLVED` | Closed remaining residuals via **`FIND-L01R2-02` (`FIX-02`)**, **`FIND-L01R2-03` (`FIX-03`)**, and **`FIND-L01R2-07` (`FIX-07`)** (`t4-transition-proposal.md` §2–§4, `tasks.md`) | **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`** |
| **`FIND-L01R-05`** (`P1`) | `PARTIALLY_RESOLVED` | Closed remaining FSM diagram/table mismatch via **`FIND-L01R2-04` (`FIX-04`)** (`design.md` §3.1–§3.2) | **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`** |
| **`FIND-L01R-06`** (`P1`) | `PARTIALLY_RESOLVED` | Closed remaining shared `SecretRedactor` scope issue via **`FIND-L01R2-05` (`FIX-05`)** (`design.md` §4, `requirements.md` `AC-508.2`, `tasks.md` `Task 6`) | **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`** |
| `FIND-L01R-07` (`P2`) | `CLOSED` | Preserved intact (`11` isolated temp-workspace mutants in `TEST-LOCAL-511`, `H=0`/inner pad/`> aligned+4` in `TEST-LOCAL-506`); trajectory framing vs U04 clarified cleanly in `FIND-L01R2-06` (`FIX-06`) | `PRESERVED_CLOSED_PENDING_L01R3_CONFIRMATION` |
| `FIND-L01R-08` (`P2`) | `CLOSED` | Preserved intact (`Task 4` `app/src/main/assets/synthetic_fixtures/` authored `valid_mixed.bin` + `manifest.json`) | `PRESERVED_CLOSED_PENDING_L01R3_CONFIRMATION` |
| `FIND-L01R-09` (`P2`) | `CLOSED` | Preserved intact (`AC-501.3` spy counter zero-fallback oracles in `TEST-LOCAL-513`, `TEST-LOCAL-516`) | `PRESERVED_CLOSED_PENDING_L01R3_CONFIRMATION` |
| `FIND-L01R-10` (`P3`) | `CLOSED` | Preserved intact (`RawHoslamFrame` & `RasterSnapshot` `copyOf()`/`equals`/`hashCode`/redacted `toString()`, manual `TEST-LOCAL-523` distinct from `android_smoke_test.py`) | `PRESERVED_CLOSED_PENDING_L01R3_CONFIRMATION` |
