# FCT-004 Implementation Plan (Deferred Pending Gate G3 & External Pilot Authorization)

> **Gate Boundary:** This PR is **specification-only** and stops at Gate G3 (`Specification Readiness`). Do NOT author Kotlin, Gradle, manifest, or policy changes until Gate G3 passes AND a separate authorized implementation phase begins. Live cloud execution additionally requires a signed `Pilot Authorization Form` and an isolated `T4` `INTERNET`/policy transition PR.

**Goal:** Enable an opt-in, strictly read-only live connectivity pilot (`login` -> `user_thing_list_get` -> capability verification -> read-only `thing_status_update` telemetry -> Emergency Disconnect & Wipe) while preserving `OFFLINE` `SimulatorRobotTransport` defaults and blocking all live physical robot commands.

**Spec & Governance:** [proposal.md](proposal.md) | [risk-assessment.md](risk-assessment.md) | [requirements.md](requirements.md) | [design.md](design.md) | [validation.md](validation.md) | [traceability.json](traceability.json)

---

### Task 1: Pure Kotlin Pilot Models & Read-Only Command Guard (`domain/pilot`)

**Planned Files (Post-G3 Implementation Only):**
- Create: `app/src/main/java/com/weback/robot/domain/pilot/PilotModels.kt`
- Create: `app/src/main/java/com/weback/robot/domain/pilot/ReadOnlyPilotGuard.kt`
- Create: `app/src/test/java/com/weback/robot/domain/pilot/ReadOnlyPilotGuardTest.kt`
- Update: `app/src/test/java/com/weback/robot/domain/DomainPurityTest.kt`

**Covers:** `REQ-PILOT-401` (`AC-401.1..2`), `REQ-PILOT-408` (`AC-408.2`).

- [ ] **Step 1 (TDD RED):** Author unit tests for `PilotAuthorizationContext`, default `OfflineDefault` gate state, `ReadOnlyPilotGuard` rejection of `Clean`/`Pause`/`Resume`/`Dock` commands, and pure-Kotlin import purity.
- [ ] **Step 2 (GREEN):** Implement pure-Kotlin pilot models and `ReadOnlyPilotGuard`.

---

### Task 2: Exact Pilot Endpoint Trust Policy, Public IP Guard & Sanitized Exporter (`security`)

**Planned Files (Post-G3 Implementation Only):**
- Create: `app/src/main/java/com/weback/robot/security/AuthorizedPilotEndpointTrustPolicy.kt`
- Create: `app/src/main/java/com/weback/robot/security/PilotSanitizedExporter.kt`
- Create: `app/src/test/java/com/weback/robot/security/AuthorizedPilotEndpointTrustPolicyTest.kt`
- Create: `app/src/test/java/com/weback/robot/security/PilotTlsHygieneTest.kt`
- Create: `app/src/test/java/com/weback/robot/security/PilotSanitizedExporterTest.kt`

**Covers:** `REQ-PILOT-402` (`AC-402.1..3`), `REQ-PILOT-408` (`AC-408.3`).

- [ ] **Step 1 (TDD RED):** Write negative tests rejecting wildcards, `http://`/`ws://`, non-443 ports, redirects, RFC1918/loopback/link-local/metadata IPs, TLS mismatches, and raw secret leakage in pilot exports.
- [ ] **Step 2 (GREEN):** Implement `AuthorizedPilotEndpointTrustPolicy` and `PilotSanitizedExporter` preserving `ProductionEndpointTrustPolicy` deny-all default.

---

### Task 3: Pilot Protocol Probes, Bounded WebSocket Config & `PilotSessionController` (`protocol`, `app/pilot`)

**Planned Files (Post-G3 Implementation Only):**
- Create: `app/src/main/java/com/weback/robot/app/pilot/PilotSessionController.kt`
- Create: `app/src/test/java/com/weback/robot/app/pilot/PilotProtocolProbeTest.kt`
- Create: `app/src/test/java/com/weback/robot/app/pilot/PilotInventoryCapabilityTest.kt`
- Create: `app/src/test/java/com/weback/robot/app/pilot/PilotWebSocketBoundsTest.kt`
- Create: `app/src/test/java/com/weback/robot/app/pilot/PilotKillSwitchTest.kt`

**Covers:** `REQ-PILOT-403` (`AC-403.1..2`), `REQ-PILOT-406` (`AC-406.1..2`), `REQ-PILOT-407` (`AC-407.1..2`), `REQ-PILOT-408` (`AC-408.1`).

- [ ] **Step 1 (TDD RED):** Write `MockWebServer` tests for CAPTCHA fail-closed halt, `Utf8TlsDefault` mismatch abort (no MD5 fallback), `LaserSlam`-only live telemetry subscription, `maxReconnectAttempts = 3`, observation-window expiration, and `emergencyDisconnectAndWipe()`.
- [ ] **Step 2 (GREEN):** Implement `PilotSessionController` integrating `SessionCoordinator`, `DeviceInventoryRepository`, and `OkHttpWebSocketTransport`.

---

### Task 4: Opt-In Ephemeral Credential Entry & Read-Only Pilot UI (`ui/pilot`, `core`)

**Planned Files (Post-G3 Implementation Only):**
- Create: `app/src/main/java/com/weback/robot/ui/pilot/PilotReadinessSheet.kt`
- Modify: `app/src/main/java/com/weback/robot/core/AppContainer.kt`
- Create: `app/src/test/java/com/weback/robot/ui/pilot/PilotSignInSheetTest.kt`
- Create: `app/src/test/java/com/weback/robot/ui/pilot/PilotDeviceIdentityCardTest.kt`

**Covers:** `REQ-PILOT-401` (`AC-401.1`), `REQ-PILOT-404` (`AC-404.1..2`), `REQ-PILOT-406` (`AC-406.3`).

- [ ] **Step 1 (TDD RED):** Write Robolectric Compose tests verifying default `OFFLINE` simulator startup, ephemeral `CharArray` input wiping, origin confirmation prompt, read-only device capability card, and one-tap Emergency Disconnect button.
- [ ] **Step 2 (GREEN):** Implement `PilotReadinessSheet` and wire `PilotSessionController` into `DefaultAppContainer` without altering default offline mode.

---

### Task 5: Isolated `T4` `INTERNET` Permission & `apk_validator.py` Policy Transition PR

**Planned Files (Separate Authorized `T4` Governance PR Only):**
- Modify (when authorized): `app/src/main/AndroidManifest.xml` (add `android.permission.INTERNET`)
- Modify (when authorized): `verification/checks/apk_validator.py` (mode-scoped `INTERNET` allowlist)
- Update (when authorized): `policies/checks.json` via `TRANSITION_AUTHORIZATION`

**Covers:** `REQ-PILOT-405` (`AC-405.1..2`).

- [ ] **Step 1:** Verify offline spec PR preserves unmodified `AndroidManifest.xml` and `policies/checks.json`.
- [ ] **Step 2:** When externally authorized for live pilot build, execute dedicated `T4` policy transition preserving all non-waivable security checks.

---

### Task 6: Controlled Physical-Device Read-Only Pilot Execution & Evidence Review

**Covers:** `REQ-PILOT-405` (`AC-405.1`), `REQ-PILOT-406` (`AC-406.2`), `REQ-PILOT-407` (`AC-407.1`), `REQ-PILOT-408` (`AC-408.1`, `AC-408.3`).

- [ ] **Step 1:** Obtain signed `Pilot Authorization Form` (PO + Lead Maintainer) targeting isolated test account and `LaserSlam` robot.
- [ ] **Step 2:** Execute `TEST-PILOT-414` supervised read-only pilot (`login` -> `user_thing_list_get` -> `LaserSlam` verification -> read-only `thing_status_update` -> Emergency Disconnect & Wipe -> sanitized log audit), keeping `TEST-PILOT-414` as `NOT_RUN` in automated CI.
