# FCT-004 Implementation Plan (Calibrated Offline Workstream FCT-004A & Deferred FCT-004B)

> **Gate Boundary:** This specification PR stops at Gate G3 (`Specification Readiness`). Upon G3 approval, **Phase `FCT-004A`** implements Tasks 1–4 strictly offline on an isolated branch (`feature/fct-004a-offline-pilot`) with zero `INTERNET` permission, zero policy/CI edits, and synthetic/MockWebServer verification only. **Tasks 5–6** (`FCT-004B` build/policy transition and physical live pilot) remain deferred until separate human authorization.

**Goal:** Deliver a structurally read-only, budget-bounded, non-blocking emergency-disconnectable pilot layer (`FCT-004A`) verified completely offline before any `INTERNET` permission is enabled.

**Spec & Governance:** [proposal.md](proposal.md) | [risk-assessment.md](risk-assessment.md) | [requirements.md](requirements.md) | [design.md](design.md) | [validation.md](validation.md) | [traceability.json](traceability.json)

---

### Task 1: Pure Kotlin Pilot Authorization Contract & Read-Only Guard (`domain/pilot`)

**Planned Files (`FCT-004A`):**
- Create: `app/src/main/java/com/weback/robot/domain/pilot/PilotModels.kt`
- Create: `app/src/main/java/com/weback/robot/domain/pilot/PilotAuthorizationGate.kt`
- Create: `app/src/main/java/com/weback/robot/domain/pilot/ReadOnlyPilotGuard.kt`
- Create: `app/src/test/java/com/weback/robot/domain/pilot/PilotAuthorizationGateTest.kt`
- Create: `app/src/test/java/com/weback/robot/domain/pilot/ReadOnlyPilotGuardTest.kt`
- Verify: `app/src/test/java/com/weback/robot/domain/DomainPurityTest.kt`

**Covers:** `REQ-PILOT-401` (`AC-401.1..3`), `REQ-PILOT-408` (`AC-408.2`).

- [ ] **Step 1 (TDD RED):** Author unit tests for default `OfflineDefault` startup, zero restart persistence, immutable `PilotAuthorizationContext` binding (candidate/APK/account/device/operations/endpoints/read-only/budgets/monotonic deadline), rejection of UI-only consent, `ReadOnlyPilotGuard` rejection + abort trigger on live physical commands, and `domain/pilot` purity.
- [ ] **Step 2 (GREEN):** Implement pure-Kotlin `PilotModels`, `PilotAuthorizationGate`, and `ReadOnlyPilotGuard`.

---

### Task 2: Endpoint Trust, Quarantined Origins, IPv4/IPv6 Isolation & Sanitized Exporter (`security`)

**Planned Files (`FCT-004A`):**
- Create: `app/src/main/java/com/weback/robot/security/AuthorizedPilotEndpointTrustPolicy.kt`
- Create: `app/src/main/java/com/weback/robot/security/PilotSanitizedExporter.kt`
- Create: `app/src/test/java/com/weback/robot/security/AuthorizedPilotEndpointTrustPolicyTest.kt`
- Create: `app/src/test/java/com/weback/robot/security/PilotIpAddressIsolationTest.kt`
- Create: `app/src/test/java/com/weback/robot/security/PilotTlsHygieneTest.kt`
- Create: `app/src/test/java/com/weback/robot/security/PilotSanitizedExporterTest.kt`

**Covers:** `REQ-PILOT-402` (`AC-402.1..4`), `REQ-PILOT-408` (`AC-408.3`).

- [ ] **Step 1 (TDD RED):** Write negative tests for exact auth origin + `/prod/oauth` path, `:443` `https`/`wss` only, quarantined dynamic origins (no session publish or token forwarding; provenance required), special-use IPv4/IPv6 CIDR rejection (ULA, CGNAT, IPv4-mapped IPv6, loopback, link-local, metadata, multicast, unspecified, etc.) with injected DNS fixtures, TLS/redirect/retry/proxy hygiene (`15s` callTimeout, `retryOnConnectionFailure=false`), and `PilotSanitizedExporter` redaction.
- [ ] **Step 2 (GREEN):** Implement `AuthorizedPilotEndpointTrustPolicy` and `PilotSanitizedExporter` preserving `ProductionEndpointTrustPolicy` deny-all default.

---

### Task 3: Capability-Restricted Pilot Transport Boundary, Budgets & `PilotSessionController` (`protocol/pilot`, `app/pilot`)

**Planned Files (`FCT-004A`):**
- Create: `app/src/main/java/com/weback/robot/protocol/pilot/PilotReadOnlyTransportBoundary.kt`
- Create: `app/src/main/java/com/weback/robot/app/pilot/PilotSessionController.kt`
- Create: `app/src/test/java/com/weback/robot/protocol/pilot/PilotStructuralBoundaryTest.kt`
- Create: `app/src/test/java/com/weback/robot/app/pilot/PilotProtocolProbeTest.kt`
- Create: `app/src/test/java/com/weback/robot/app/pilot/PilotInventoryCapabilityTest.kt`
- Create: `app/src/test/java/com/weback/robot/app/pilot/PilotWebSocketBoundsTest.kt`
- Create: `app/src/test/java/com/weback/robot/app/pilot/PilotBudgetEnforcementTest.kt`
- Create: `app/src/test/java/com/weback/robot/app/pilot/PilotKillSwitchTest.kt`
- Create: `app/src/test/java/com/weback/robot/app/pilot/PilotEmergencyCancellationTest.kt`

**Covers:** `REQ-PILOT-403` (`AC-403.1..2`), `REQ-PILOT-406` (`AC-406.1..2`), `REQ-PILOT-407` (`AC-407.1..2`), `REQ-PILOT-408` (`AC-408.1..2`).

- [ ] **Step 1 (TDD RED):** Write `MockWebServer` + virtual-time tests for capability-restricted HTTP & WS boundary (capturing requests/frames to prove zero unauthorized ops or outbound WS app frames reach the network while preserving WS control frames), CAPTCHA & `Utf8TlsDefault` abort, `LaserSlam` + target device matching, cumulative `<= 3` WS reconnect budget (no reset on `onOpen`), `15s` HTTP timeout, monotonic observation deadline, and 9-step non-blocking Emergency Disconnect cancellation across all active phases + stale callbacks + incomplete storage cleanup reporting.
- [ ] **Step 2 (GREEN):** Implement `PilotReadOnlyTransportBoundary` and `PilotSessionController` integrating `SessionCoordinator`, `DeviceInventoryRepository`, and `OkHttpWebSocketTransport`.

---

### Task 4: Opt-In Ephemeral Credential Entry & Read-Only Pilot UI (`ui/pilot`, `core`)

**Planned Files (`FCT-004A`):**
- Create: `app/src/main/java/com/weback/robot/ui/pilot/PilotReadinessSheet.kt`
- Modify: `app/src/main/java/com/weback/robot/core/AppContainer.kt` (exposing pilot dependencies safely while defaulting to offline simulator)
- Modify: `app/src/main/java/com/weback/robot/MainActivity.kt` / `app/src/main/java/com/weback/robot/ui/screens/DashboardScreen.kt` (opt-in sheet trigger preserving FCT-002 dashboard intact)
- Create: `app/src/test/java/com/weback/robot/ui/pilot/PilotSignInSheetTest.kt`
- Create: `app/src/test/java/com/weback/robot/ui/pilot/PilotDeviceIdentityCardTest.kt`

**Covers:** `REQ-PILOT-401` (`AC-401.1`), `REQ-PILOT-404` (`AC-404.1..2`), `REQ-PILOT-406` (`AC-406.3`).

- [ ] **Step 1 (TDD RED):** Write Robolectric Compose tests verifying default `OFFLINE` simulator startup, `FLAG_SECURE` window flag during credential entry, ephemeral `CharArray` cleanup on submit/cancel/error/screen exit, quarantined origin review UI, read-only device capability card, monotonic observation timer, clear offline/mock labeling, and Emergency Disconnect button.
- [ ] **Step 2 (GREEN):** Implement `PilotReadinessSheet` and wire `PilotSessionController` into `DefaultAppContainer` while keeping production access disabled and FCT-002 simulator isolated.

---

### Task 5: Isolated `FCT-004B` Separate Build & `apk_validator.py` Policy Transition (Deferred Proposal Only)

**Planned Files (Separate Authorized `FCT-004B` Governance PR Only — NOT Executed in `FCT-004` or `FCT-004A`):**
- Modify (when authorized): `app/build.gradle.kts` (`pilot` build variant separation)
- Create (when authorized): `app/src/pilot/AndroidManifest.xml` (`android.permission.INTERNET` only)
- Modify (when authorized): `verification/checks/apk_validator.py` (`--connectivity-policy {offline,pilot}` CLI)
- Modify (when authorized): `verification/selftests/test_apk_validator.py` (negative offline & pilot permission tests)
- Update (when authorized): `policies/checks.json` via `TRANSITION_AUTHORIZATION`

**Covers:** `REQ-PILOT-405` (`AC-405.1..2`).

- [ ] **Step 1:** Verify `FCT-004` spec PR and `FCT-004A` offline implementation leave `AndroidManifest.xml` (`INTERNET`-free), `app/build.gradle.kts`, `policies/checks.json`, and `verification/` unmodified.
- [ ] **Step 2:** When externally authorized for `FCT-004B`, execute dedicated `T4` policy transition preserving all non-waivable security checks and retire `TRANSITION_AUTHORIZATION` post-merge.

---

### Task 6: Controlled Physical-Device Read-Only Pilot Execution & Evidence Review (Deferred)

**Covers:** `REQ-PILOT-405` (`AC-405.1`), `REQ-PILOT-406` (`AC-406.2`), `REQ-PILOT-407` (`AC-407.1`), `REQ-PILOT-408` (`AC-408.1`, `AC-408.3`).

- [ ] **Step 1:** Obtain signed `Pilot Authorization Form` (PO + Lead Maintainer) targeting isolated test account and `LaserSlam` robot.
- [ ] **Step 2:** Execute `TEST-PILOT-414` supervised read-only pilot (`login` -> `user_thing_list_get` -> `LaserSlam` verification -> read-only `thing_status_update` -> Emergency Disconnect & Wipe -> sanitized log audit), keeping `TEST-PILOT-414` as `NOT_RUN` in automated CI.
