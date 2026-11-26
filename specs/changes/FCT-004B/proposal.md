# Change Proposal: FCT-004B — Pre-INTERNET Security Corrections, Isolated Pilot Build & Verification Policy Transition

- **Change ID:** `FCT-004B`
- **Milestone:** Milestone 4 — Controlled Connectivity Pilot (Build & Policy Transition Candidate)
- **Risk Tier:** `T4 (Critical Risk)` per [policies/risk-tiers.md](../../../policies/risk-tiers.md) (`POL-RISK-01`)
- **Status:** `CANDIDATE_FOR_T4_POLICY_TRANSITION_REVIEW`
- **Prerequisite:** `FCT-004A` accepted offline Human UAT and squash-merged on `main` (`ed8b8c584fd45d9750be33945579ecbbbd93be20`, tree `9ae05075333d0a124323f9f7661e518cdc542f26`, post-merge CI `45754763099` `PASS`)
- **Governance Links:** [specs/constitution.md](../../constitution.md) | [specs/roadmap.md](../../roadmap.md) | [specs/tech-stack.md](../../tech-stack.md) | [FCT-004 Proposal](../FCT-004/proposal.md) | [risk-assessment.md](risk-assessment.md)

---

## 1. Problem Statement

`FCT-004A` delivered the offline-verified bounded pilot control plane while keeping `app/src/main/AndroidManifest.xml` strictly `INTERNET`-free (`com.weback.robot`). Before an isolated pilot APK with `android.permission.INTERNET` can be prepared for human-supervised device acceptance (`TEST-PILOT-414`), three pre-`INTERNET` security gaps (`B-SEC-01` .. `B-SEC-03`) must be closed, the pilot build must be isolated into a dedicated `pilot` build type (`com.weback.robot.pilot`), and the verification harness and APK validator must enforce explicit `--connectivity-policy {offline,pilot}` separation without weakening the existing offline APK or activating any policy transition automatically.

---

## 2. Objectives

1. **Mandatory Pre-`INTERNET` Security Corrections (`B-SEC-01`..`B-SEC-03`):**
   - `B-SEC-01`: Bind `PilotAdmissionTicket` to an internally issued, unique admission ID (`ticketId`) and authorized request attributes inside `PilotAuthorizationGate`; distinguish intermediate verification (`verifyTicket`) from atomic one-time consumption at network dispatch (`consumeTicketForDispatch` inside `PilotClientFactory`); block ticket replay, forged caller-created tickets, and tampered ticket fields; preserve generation-fenced revocation and cancellation.
   - `B-SEC-02`: Enforce exact authorized account identifier and normalized calling code inside `PilotCapabilityRestrictedAuthGateway` before credential serialization or network dispatch, independent of controller checks.
   - `B-SEC-03`: Eliminate `System.nanoTime()` from pilot admission and ticket expiry checks; use the injected `AuthMonotonicClock` (backed by Android `SystemClock.elapsedRealtime()`) across gate, controller, and HTTP dispatch layers; ensure deep-sleep or virtual-clock expiry triggers full generation-fenced 9-step cleanup rather than only clearing `activeContext`.
2. **Isolated Pilot Build Type (`com.weback.robot.pilot`):**
   - Add explicit `pilot` `buildType` (`applicationIdSuffix = ".pilot"`, `BuildConfig.PILOT_INTERNET_CAPABLE = true`) while preserving `debug` (`com.weback.robot`, `BuildConfig.PILOT_INTERNET_CAPABLE = false`) and keeping `app/src/main/AndroidManifest.xml` `INTERNET`-free.
   - Restrict `android.permission.INTERNET` solely to `app/src/pilot/AndroidManifest.xml`.
   - Separate external artifact verification (`sha256sum`, `apk_validator.py`) from runtime authorization identity (`RuntimeAppIdentity`) without embedding a self-referential APK checksum inside the APK.
3. **Unified APK Validator & Proposed Policy Transition:**
   - Extend `verification/checks/apk_validator.py` with `--connectivity-policy {offline,pilot}` (default `offline`), binding each mode to its exact expected APK path, package ID, internal permission, provider authority, and `INTERNET` requirement/prohibition.
   - Prepare the proposed candidate policy (`policies/checks.json`) while preserving the active baseline (`policies/checks.baseline.json` at `e688b6fef22290281facf91d26f42ae53ff527ca7f6b599a750d24d66873985e`) and keeping `TRANSITION_AUTHORIZATION` inactive until explicit human approval.

---

## 3. Verification Policy Evaluation & Smaller Alternative Analysis

- **Proposed Policy (`policies/checks.json`, SHA-256 `fd870ee6b2b324aa1d2bfbe84d9282df13708821cb7d291d2560d0a80a77bbc4`):** Preserves all 7 approved baseline checks (`spec-hygiene`, `harness-selftests`, `gradle-wrapper-validation`, `android-lint`, `android-unit-tests`, `android-build`, `apk-validation`) and adds `pilot-android-lint` (`lintPilot`), `pilot-android-unit-tests` (`testPilotUnitTest`), `pilot-android-build` (`assemblePilot`), and `pilot-apk-validation` (`apk_validator.py --connectivity-policy pilot`).
- **Measured Fast-Lane Runtime (`fct004b-measure`):** Total `273.56s` (`4m 33.6s`): `spec-hygiene` `0.12s`, `harness-selftests` `17.16s` (`193/193`), `gradle-wrapper-validation` `0.06s`, `android-lint` `16.20s`, `android-unit-tests` `87.69s` (`132/132`), `android-build` `19.26s`, `apk-validation` `5.47s`, `pilot-android-lint` `16.22s`, `pilot-android-unit-tests` `86.74s` (`132/132`), `pilot-android-build` `19.08s`, `pilot-apk-validation` `5.56s`.
- **Duplicated Work Identified:**
  1. `pilot-android-unit-tests` (`86.74s`) re-executes ~130 variant-agnostic domain/protocol/UI tests alongside `PilotBuildSeparationTest`, `MainActivityMockPilotFlowTest`, and `DashboardScreenControlTest` against the `pilot` merged manifest/resources/BuildConfig.
  2. Each separate `./gradlew ... --no-daemon` invocation incurs ~8-10s single-use Gradle daemon startup overhead, and `lintPilot` re-analyzes `src/main` Kotlin/Compose code already analyzed by `lintDebug`.
- **Equivalent Smaller Alternative Evaluated:** Consolidating `lintDebug`+`lintPilot` or `assembleDebug`+`assemblePilot` into combined Gradle invocations (saving ~20s daemon startup) or adding a variant-filtered `testPilotUnitTest` check (saving ~75s) would reduce total runtime to ~180s, **but** would either rename/alter the 7 approved baseline checks or require modifying `gradle_test_adapter.py` and skipping full Robolectric verification of `MainActivity`/`DashboardScreen` under `src/pilot` resources. Keeping all 11 explicit checks preserves 100% baseline continuity and full independent verification of both variants within comfortable CI margins.

---

## 4. Hard Stop Conditions & Out of Scope

- Zero production DNS probes, live cloud requests, real credentials, or physical robot communication/commands.
- Zero automatic activation of `TRANSITION_AUTHORIZATION` or retirement of `APPROVED_POLICY_SHA256`.
- Zero interaction with physical devices during this implementation/candidate preparation PR.
- No FCT-004B implementation merge without explicit human T4 review and policy transition authorization.
