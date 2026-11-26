# FCT-004B Requirements Specification — Pilot Build, Pre-INTERNET Security & Policy Transition

- **Change ID:** `FCT-004B`
- **Milestone:** `FCT-004B`
- **Document ID:** `SPEC-REQ-FCT004B`
- **Risk Tier:** `T4 (Critical Risk)`
- **Provenance & Governance:** [proposal.md](proposal.md) | [design.md](design.md) | [risk-assessment.md](risk-assessment.md) | [specs/constitution.md](../../constitution.md)

---

## 1. Traceable Requirements Catalog

### REQ-PILOT-451: Single-Use Request-Bound Admission Tickets (`B-SEC-01`)
*(Workstream: `T4` — Network Admission Boundary)*

Every pilot network dispatch must consume an internally issued, single-use, request-bound `PilotAdmissionTicket` at the network dispatch boundary while preserving non-consuming intermediate verification and generation-fenced cancellation.

- **AC-451.1:** `PilotAuthorizationGate` issues `PilotAdmissionTicket` instances with an internal `ticketId` tracked in an internal authoritative ledger binding `authorizationId`, `pilotGeneration`, `operation`, `deadlineElapsedMs`, `environment`, `httpCallTimeoutMs`, `maxWsReconnectAttempts`, `issuedElapsedMs`, and bound request URL/account scope; caller-constructed or field-modified tickets are rejected fail-closed.
- **AC-451.2:** Intermediate layers (`PilotCapabilityRestrictedAuthGateway`, `PilotCapabilityRestrictedInventoryGateway`, `OkHttpWebSocketTransport`) verify tickets via non-consuming `verifyTicket`, whereas `PilotClientFactory`'s OkHttp dispatch interceptor atomically consumes each ticket once (`consumeTicketForDispatch`) after verifying HTTP method/body `opt` or WebSocket upgrade headers; ticket replay produces zero additional `MockWebServer` requests while preserving generation-fenced revocation.

### REQ-PILOT-452: Gateway-Boundary Account & Region Binding (`B-SEC-02`)
*(Workstream: `T4` — Credential & Account Isolation)*

The capability-restricted authentication gateway must enforce exact authorized account and calling code binding independently before credential serialization or network dispatch.

- **AC-452.1:** When `PilotCapabilityRestrictedAuthGateway` is configured with `admissionGate`, `probeLogin` / `signIn` verifies `admissionGate.verifyAccountCredentials(regionCallingCode, account)` prior to `digestStrategy.encodeForTlsPayload` and `LoginRequestEnvelope` serialization; direct gateway invocation with a different account in the same region while authorization is active wipes the secret buffer and results in zero `MockWebServer` requests.

### REQ-PILOT-453: Absolute Android Monotonic Deadline & Complete Expiry Cleanup (`B-SEC-03`)
*(Workstream: `T4` — Monotonic Time & Expiry Enforcement)*

Pilot admission, request verification, and expiry handling must rely exclusively on the injected `AuthMonotonicClock` (backed by Android `SystemClock.elapsedRealtime()`) and trigger complete generation-fenced cleanup on expiry.

- **AC-453.1:** `PilotAuthorizationGate` and `PilotAdmissionTicket` contain zero `System.nanoTime()` deadline calculations; `PilotAuthorizationGate`, `PilotClientFactory`, and `PilotSessionController` evaluate deadlines strictly against the injected `AuthMonotonicClock` (`SystemClock.elapsedRealtime()` in production).
- **AC-453.2:** When the monotonic deadline expires before network admission or during active pilot operation (including across simulated deep sleep where `AuthMonotonicClock` advances independently of coroutine virtual uptime), `PilotAuthorizationGate` / `PilotSessionController` triggers full generation-fenced 9-step cleanup (cancelling active calls, clearing dynamic origins, disconnecting WebSocket, signing out matching session epoch, clearing inventory, and restoring `OfflineDefault`), without disturbing newer generations.

### REQ-PILOT-454: Isolated `pilot` Build Type & Separate Manifest Identity
*(Workstream: `T4` — Build & Manifest Separation)*

The application build must produce an isolated `pilot` APK (`com.weback.robot.pilot`) with `INTERNET` while preserving the existing `INTERNET`-free `debug` APK (`com.weback.robot`).

- **AC-454.1:** `app/build.gradle.kts` defines `pilot` `buildType` (`applicationIdSuffix = ".pilot"`, `BuildConfig.PILOT_INTERNET_CAPABLE = true`), preserves `debug` (`com.weback.robot`, `minSdk = 31`, `targetSdk = 35`, `BuildConfig.PILOT_INTERNET_CAPABLE = false`), preserves `app/src/main/AndroidManifest.xml` `INTERNET`-free, and adds `<uses-permission android:name="android.permission.INTERNET" />` solely in `app/src/pilot/AndroidManifest.xml`.
- **AC-454.2:** Pilot APK provides side-by-side installability (`com.weback.robot.pilot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` and `com.weback.robot.pilot.androidx-startup` authorities), separate application storage sandbox, clear pilot-specific labeling (`WeBack Robot Pilot (Read-Only)`), default `OfflineDefault` startup, zero automatic auth, zero persistent live authorization, and zero live physical robot commands.
- **AC-454.3:** No self-referential final APK checksum is embedded inside the APK; external artifact verification (`sha256sum` / `apk_validator.py`) is separated from `RuntimeAppIdentity` (`BuildConfig.APPLICATION_ID`, `VERSION_NAME`, `VERSION_CODE`, `PILOT_INTERNET_CAPABLE`).

### REQ-PILOT-455: Unified Connectivity-Mode APK Validator
*(Workstream: `T4` — Artifact Security Validation)*

`verification/checks/apk_validator.py` must enforce strict mode-bound artifact validation across both `offline` and `pilot` APKs.

- **AC-455.1:** `apk_validator.py` exposes `--connectivity-policy` (`offline` default, `pilot`), binding `offline` to `app/build/outputs/apk/debug/app-debug.apk` / `com.weback.robot` (forbidding `INTERNET`) and `pilot` to `app/build/outputs/apk/pilot/app-pilot.apk` / `com.weback.robot.pilot` (requiring `INTERNET` and forbidding all other external permissions).
- **AC-455.2:** Both modes enforce cross-artifact substitution rejection, `usesCleartextTraffic="false"`, `allowBackup="false"`, `networkSecurityConfig`, `apksigner` signature verification, provider authority isolation, and prohibited vendor binary / DEX package exclusion, backed by focused negative selftests.

### REQ-PILOT-456: Proposed Verification Policy & Candidate Transition Evidence
*(Workstream: `T4` — Verification Policy Governance)*

The candidate must preserve all seven approved baseline checks and evaluate an expanded candidate policy without automatically activating `TRANSITION_AUTHORIZATION`.

- **AC-456.1:** The proposed policy preserves all 7 existing required checks (`spec-hygiene`, `harness-selftests`, `gradle-wrapper-validation`, `android-lint`, `android-unit-tests`, `android-build`, `apk-validation`) and adds independent pilot checks (`pilot-android-lint`, `pilot-android-unit-tests`, `pilot-android-build`, `pilot-apk-validation`) using `lintPilot`, `testPilotUnitTest`, `assemblePilot`, and `apk_validator.py --connectivity-policy pilot`, with runtime measurement and smaller-alternative evaluation documented.
- **AC-456.2:** Both the currently approved baseline policy (`policies/checks.baseline.json` matching `e688b6fef22290281facf91d26f42ae53ff527ca7f6b599a750d24d66873985e`) and the complete proposed policy (`policies/checks.json`) are executed and audited via `verification/run.py` and `verification/checks/evidence.py` with `TRANSITION_AUTHORIZATION` left inactive.

### REQ-PILOT-457: Device Acceptance Preparation Without Unauthorized Hardware Execution
*(Workstream: `T4` — Physical Device Acceptance Readiness)*

Both exact candidate APKs and a structured acceptance plan must be prepared without interacting with physical hardware.

- **AC-457.1:** Exported offline debug APK, isolated pilot APK, evidence archive, and device acceptance checklist cover side-by-side install, package identity, manifest permission separation, zero startup networking, isolated storage, Android Keystore behavior, simulator preservation, read-only boundary, emergency disconnect, and rollback without affecting the offline app, while `TEST-PILOT-414` and live device execution remain `NOT_RUN`.
