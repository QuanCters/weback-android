# FCT-003 Implementation Plan — Authentication, Device Inventory & Connectivity Readiness

> **Gate Boundary (Updated Per PO & Lead Maintainer G3 Decision):** Specification PR #7 is approved subject to bounded clarifications `IC-301`–`IC-304`. After squash-merging PR #7 onto `main`, execute `Task 1`–`Task 6` ONLY on isolated branch `feature/fct-003-authentication-connectivity` using pinned dependencies (`OkHttp 4.12.0`, `MockWebServer 4.12.0` test-only, `kotlinx-serialization-json 1.7.3`, Kotlin serialization compiler plugin `2.1.21`, and pinned instrumentation test runner deps). Keep production connectivity disabled, `AndroidManifest.xml` `INTERNET`-free, and implementation merge gated for human review.

**Goal:** Deliver `T4` clean-room authentication, endpoint trust & redirect isolation, single-flight `sessionEpoch` ownership, dual-clock token expiry/restart/reboot/rollback semantics, finalized Android Keystore AES-256-GCM storage (without `EncryptedSharedPreferences` or software fallbacks), deny-by-default logging, and `T3` `user_thing_list_get` device inventory, RE-grounded `sub_type` capability filtering, generation-fenced WebSocket transport, and hermetic Offline + loopback `MockWebServer` adapters under zero live network access.

**Architecture:** Extends `:app` clean package structure (`core`, `domain`, `security`, `protocol`, `app/session`). Pure Kotlin `domain` remains 100% isolated (`C-002`). Default runtime stays strictly offline (`SimulatorRobotTransport` + `OfflineFixture*` adapters, `ProductionEndpointTrustPolicy` disabled, zero `INTERNET` manifest permission), keeping live `grit-cloud` / AWS IoT / `FCT-004` pilot connectivity gated behind external authorization and a separate `T4` policy/manifest PR.

**Spec & Governance:** [proposal.md](proposal.md) | [risk-assessment.md](risk-assessment.md) | [requirements.md](requirements.md) | [design.md](design.md) | [validation.md](validation.md) | [traceability.json](traceability.json)

---

## Global Pre-Implementation Constraints
- **Isolated Implementation Branch:** Squash-merge `spec/fct-003-authentication-connectivity` via `--match-head-commit` first, then implement strictly inside `feature/fct-003-authentication-connectivity`.
- **Zero Policy/Harness Edits (`POL-INT-01`):** Keep `policies/checks.json` (7 checks), `verification/`, and `.github/workflows/` untouched. Keep `android.permission.INTERNET` excluded from `AndroidManifest.xml` throughout `FCT-003` offline verification (`apk_validator.py`).
- **Zero Plaintext Password Storage / Zero Legacy MD5 Wire Encoding / Zero `EncryptedSharedPreferences` / Zero Runtime Software-Key Fallback (`C-010`):** Strict TDD verification across `T4` security modules.
- **Zero Live Network or Production Secrets (`C-006`, `C-010`):** All unit/contract tests execute deterministically against in-process fixtures and loopback `okhttp3.mockwebserver.MockWebServer` with `Proxy.NO_PROXY` + destination IP loopback verification.

---

### Task 1: Pure Kotlin Auth, Dual-Clock Expiry, Session FSM & Device Capability Domain (`domain`)

**Files (Target Scope Upon Implementation Approval):**
- Create: `app/src/main/java/com/weback/robot/core/BootSessionTimeProvider.kt`
- Create: `app/src/main/java/com/weback/robot/domain/model/AuthModels.kt`
- Create: `app/src/main/java/com/weback/robot/domain/model/ConnectivityModels.kt`
- Create: `app/src/main/java/com/weback/robot/domain/auth/TokenExpiryPolicy.kt`
- Create: `app/src/main/java/com/weback/robot/domain/auth/SessionStateMachine.kt`
- Create: `app/src/main/java/com/weback/robot/domain/device/DeviceCapabilityClassifier.kt`
- Create: `app/src/test/java/com/weback/robot/domain/TokenExpiryPolicyTest.kt`
- Create: `app/src/test/java/com/weback/robot/domain/SessionStateMachineTest.kt`
- Create: `app/src/test/java/com/weback/robot/domain/DeviceCapabilityClassifierTest.kt`
- Create: `app/src/test/java/com/weback/robot/domain/AuthModelsTest.kt`
- Update: `app/src/test/java/com/weback/robot/domain/DomainPurityTest.kt`

**Covers:** `REQ-AUTH-302` (`AC-302.1`), `REQ-AUTH-303` (`AC-303.1..3`), `REQ-AUTH-304` (`AC-304.1..2`), `REQ-CONN-307` (`AC-307.1..2`).

- [x] **Step 1 (TDD RED):** Author unit tests for `TokenExpiryPolicy` (80%/100% exact boundaries, `Long` overflow guard, same-boot restart `priorElapsedMs`, reboot/clock rollback rejection, unverified JWT `exp` non-use), `SessionStateMachine` (`sessionEpoch` isolation + single-flight guards), `AuthModelsTest` (secret-safe `toString()` masking), `DeviceCapabilityClassifier` (`P0`, `q1`, `l1`/`L0`, `m1`, `N0`, `Y0`, `v0`, `g1`, `n1`, `H0`/`U0`, unknown strings grounded in `enums/p.java` + `utils/c.java`), and pure-Kotlin import boundaries (`DomainPurityTest`). Confirm failure.
- [x] **Step 2 (GREEN):** Implement pure Kotlin domain models, `BootSessionTimeProvider`, `TokenExpiryPolicy`, `SessionStateMachine`, and `DeviceCapabilityClassifier` with zero Android/coroutine/OkHttp/serialization imports in `domain`.
- [x] **Step 3 (Verify):** Run `./gradlew testDebugUnitTest --tests "com.weback.robot.domain.*"`.

---

### Task 2: T4 Security Enclave — Endpoint Trust, Keystore AES-256-GCM Storage & Deny-by-Default Logging (`security`)

**Files:**
- Modify (Pre-approved dependency update): `gradle/libs.versions.toml`, `app/build.gradle.kts` (pin `kotlinx-serialization-json:1.7.3` + `kotlin.plugin.serialization:2.1.21` + pinned instrumentation runner deps per [specs/tech-stack.md](../../tech-stack.md) & `IC-304`)
- Create: `app/src/main/java/com/weback/robot/security/EndpointTrustPolicy.kt`
- Create: `app/src/main/java/com/weback/robot/security/UriValidator.kt`
- Create: `app/src/main/java/com/weback/robot/security/TokenStore.kt`
- Create: `app/src/main/java/com/weback/robot/security/KeystoreAeadTokenStore.kt`
- Create: `app/src/main/java/com/weback/robot/security/DiagnosticLogger.kt`
- Create: `app/src/main/java/com/weback/robot/security/SecretRedactor.kt`
- Create: `app/src/main/java/com/weback/robot/security/PasswordDigestStrategy.kt`
- Create: `app/src/test/java/com/weback/robot/security/EndpointTrustAndRedirectTest.kt`
- Create: `app/src/test/java/com/weback/robot/security/KeystoreAeadTokenStoreTest.kt`
- Create: `app/src/test/java/com/weback/robot/security/KeystoreAeadTokenStoreTamperTest.kt`
- Create: `app/src/test/java/com/weback/robot/security/SecretRedactorTest.kt`
- Create: `app/src/test/java/com/weback/robot/security/DiagnosticLoggerTest.kt`
- Create (Device-gated plan, `NOT_RUN` in offline CI): `app/src/androidTest/java/com/weback/robot/security/KeystoreHardwareSecurityLevelTest.kt`

**Covers:** `REQ-AUTH-301` (`AC-301.1..3`), `REQ-AUTH-305` (`AC-305.1..4`).

- [x] **Step 1 (TDD RED):** Write tests verifying: exact origin trust authorization (`ProductionEndpointTrustPolicy` disabled in FCT-003; `TestLoopbackTrustPolicy` rejects non-loopback/external DNS/IP, userinfo, credential query params, scheme downgrade); cipher-generated 12B IVs, 128-bit GCM tag, canonical AAD, `[29..16_384]` size bounds, atomic temp-write-replace in `noBackupFilesDir`, fail-closed wipe/cleanup on bit-flip/version/AAD/truncation/oversize/missing-key/`KeyPermanentlyInvalidatedException`/bad post-decrypt JSON, explicit `save()`/`clear()` failure handling, mutable buffer cleanup, zero `EncryptedSharedPreferences`/`setDefaultSSLSocketFactory`/trust-all TLS/software-key fallback; allowlisted `DiagnosticLogger` + `SecretRedactor` coverage of case-insensitive headers, opaque tokens, `eyJ...` JWTs, nested/malformed JSON, URL-encoded secrets, and exception chains. Observe RED failures.
- [x] **Step 2 (GREEN):** Implement `EndpointTrustPolicy`, `UriValidator`, `KeystoreAeadTokenStore` (ADR-003), `DiagnosticLogger`, `SecretRedactor`, and `PasswordDigestStrategy` (`Utf8TlsDefault` only). Author `KeystoreHardwareSecurityLevelTest` (`KeyInfo.getSecurityLevel()`) with an isolated test Keystore alias and storage namespace for authorized physical Android >= 12 device runs, and verify `./gradlew assembleDebugAndroidTest` compiles cleanly.
- [x] **Step 3 (Verify):** Run `./gradlew testDebugUnitTest --tests "com.weback.robot.security.*"`.

---

### Task 3: T4/T3 Auth & Inventory Gateways + Epoch-Owned `SessionCoordinator` (`protocol/auth`, `protocol/inventory`, `app/session`)

**Files:**
- Modify (Pre-approved dependency update): `gradle/libs.versions.toml`, `app/build.gradle.kts` (pin `okhttp:4.12.0` + `testImplementation` `mockwebserver:4.12.0`)
- Create: `app/src/main/java/com/weback/robot/protocol/auth/RegionEndpointRouter.kt`
- Create: `app/src/main/java/com/weback/robot/protocol/auth/AuthGateway.kt`
- Create: `app/src/main/java/com/weback/robot/protocol/auth/OkHttpAuthGateway.kt`
- Create: `app/src/main/java/com/weback/robot/protocol/auth/OfflineFixtureAuthGateway.kt`
- Create: `app/src/main/java/com/weback/robot/protocol/inventory/DeviceInventoryGateway.kt`
- Create: `app/src/main/java/com/weback/robot/protocol/inventory/OkHttpDeviceInventoryGateway.kt`
- Create: `app/src/main/java/com/weback/robot/protocol/inventory/OfflineFixtureInventoryGateway.kt`
- Create: `app/src/main/java/com/weback/robot/app/session/SessionCoordinator.kt`
- Create: `app/src/main/java/com/weback/robot/app/session/DeviceInventoryRepository.kt`
- Create: `app/src/test/java/com/weback/robot/protocol/auth/OkHttpAuthGatewayTest.kt`
- Create: `app/src/test/java/com/weback/robot/protocol/auth/OkHttpAuthGatewayNegativeTest.kt`
- Create: `app/src/test/java/com/weback/robot/protocol/inventory/OkHttpDeviceInventoryGatewayTest.kt`
- Create: `app/src/test/java/com/weback/robot/app/session/SessionCoordinatorTest.kt`
- Create: `app/src/test/java/com/weback/robot/app/session/SessionTimeSemanticsTest.kt`
- Create: `app/src/test/java/com/weback/robot/app/session/CrossAccountIsolationTest.kt`

**Covers:** `REQ-AUTH-301` (`AC-301.1..3`), `REQ-AUTH-302` (`AC-302.2..3`), `REQ-AUTH-303` (`AC-303.1..3`), `REQ-AUTH-304` (`AC-304.1..3`), `REQ-CONN-306` (`AC-306.1..3`), `REQ-CONN-309` (`AC-309.1..2`).

- [x] **Step 1 (TDD RED):** Write loopback `MockWebServer` contract tests covering `POST` `login` envelope (`AuthEnvelopeConfig.protocolApiVersion`, `"login"` opt), `user_thing_list_get` (`"token"`/`"region"` headers), `followRedirects(false)` `302`/`307`/`308` rejection, untrusted HTTPS host rejection, pre-send & in-flight expiry checks, negative HTTP `401`/`403`/`5xx`/malformed JSON/`code != 1` rejections, defensive `thing_status` battery clamping, single-flight `signIn`/synthetic refresh, logout during login/persist, `save()`/`clear()` failure fail-closed behavior, restart/reboot/clock rollback handling, and Account A -> Account B cross-account race isolation. Observe RED failures.
- [x] **Step 2 (GREEN):** Implement `RegionEndpointRouter`, `OkHttp*` (with `followRedirects(false)`, `followSslRedirects(false)`, `EndpointTrustPolicy`, `DiagnosticLogger`) & `OfflineFixture*` gateways, `SessionCoordinator`, and `DeviceInventoryRepository`.
- [x] **Step 3 (Verify):** Run `./gradlew testDebugUnitTest --tests "com.weback.robot.protocol.*" --tests "com.weback.robot.app.*"`.

---

### Task 4: T3 Cloud WebSocket Lifecycle Transport, Send-Stall/Reconnect & Telemetry Ownership (`protocol/ws`)

**Files:**
- Create: `app/src/main/java/com/weback/robot/protocol/ws/ConnectionConfig.kt`
- Create: `app/src/main/java/com/weback/robot/protocol/ws/RealtimeSessionTransport.kt`
- Create: `app/src/main/java/com/weback/robot/protocol/ws/OkHttpWebSocketTransport.kt`
- Create: `app/src/main/java/com/weback/robot/protocol/ws/OfflineFixtureRealtimeTransport.kt`
- Create: `app/src/test/java/com/weback/robot/protocol/ws/OkHttpWebSocketTransportTest.kt`
- Create: `app/src/test/java/com/weback/robot/protocol/ws/WebSocketGenerationIsolationTest.kt`
- Create: `app/src/test/java/com/weback/robot/protocol/ws/TelemetryOwnershipTest.kt`

**Covers:** `REQ-CONN-308` (`AC-308.1..5`), `REQ-CONN-309` (`AC-309.1..2`), `REQ-CONN-310` (`AC-310.1..2`).

- [x] **Step 1 (TDD RED):** Write loopback `MockWebServer.withWebSocketUpgrade` tests under `StandardTestDispatcher` verifying endpoint trust + pre-connect expiry check, `"token"`/`"region"` upgrade headers (zero query params, `followRedirects(false)`), `"thing_status_update"` hydration, `20_000ms` outbound send-stall trigger and `4_000ms` reconnect scheduling, HTTP `401`/`403`/`302` short-circuit + session invalidation, `(sessionEpoch, connectionGeneration)` fencing on callbacks/reconnect timers, cross-device/unsupported `thing_name` telemetry rejection, non-destructive telemetry retention on socket drop, and synchronized idempotent `close(1000)`. Observe RED failures.
- [x] **Step 2 (GREEN):** Implement `ConnectionConfig`, `RealtimeSessionTransport`, `OkHttpWebSocketTransport`, and `OfflineFixtureRealtimeTransport`.
- [x] **Step 3 (Verify):** Run `./gradlew testDebugUnitTest --tests "com.weback.robot.protocol.ws.*"`.

---

### Task 5: DI Container Integration, Offline Mode Preservation & Error Recovery Tests (`core`, Integration Tests)

**Files:**
- Modify: `app/src/main/java/com/weback/robot/core/AppContainer.kt` (wire `TokenStore`, `EndpointTrustPolicy`, `DiagnosticLogger`, `SecretRedactor`, `SessionCoordinator`, `DeviceInventoryRepository`, `OfflineFixture*` defaults while preserving FCT-002 `SimulatorRobotTransport`)
- Create: `app/src/test/java/com/weback/robot/ConnectivityRecoveryTest.kt`
- Modify: `app/src/test/java/com/weback/robot/core/AppContainerTest.kt`

**Covers:** `REQ-CONN-309` (`AC-309.1..3`), `REQ-CONN-310` (`AC-310.1..3`).

- [x] **Step 1 (TDD RED):** Write end-to-end offline integration & fault-recovery tests verifying `DefaultAppContainer` starts offline with `"OFFLINE"` identity intact and `ProductionEndpointTrustPolicy` disabled, end-to-end signIn -> inventory -> capability filter -> WS disconnect/reconnect -> signOut wipe cycle, and zero FCT-001/FCT-002 test regressions.
- [x] **Step 2 (GREEN):** Extend `DefaultAppContainer` (`close()` tears down `SessionCoordinator` + transports + simulator deterministically).
- [x] **Step 3 (Verify):** Execute full `./gradlew testDebugUnitTest` confirming all FCT-001, FCT-002, and FCT-003 feature-level security tests pass within `180s`.

---

### Task 6: Candidate Verification Under Existing Policy & Gate G4–G6 Package

**Files (Upon Implementation Completion Only):**
- Modify: `specs/changes/FCT-003/traceability.json` (populate actual `VERIFIED` execution evidence refs strictly from observed test runs; retain `AC-305.4` / `TEST-AUTH-312` as `NOT_RUN` unless an authorized Android >= 12 physical device executes it)
- Update: `verification/defects.jsonl` / `regression-backlog.md` (strictly for any real defects uncovered during implementation, never fabricated)

**Covers:** `REQ-AUTH-301..305`, `REQ-CONN-306..310` (Gate readiness & APK verification).

- [x] **Step 1:** Run full 7-check candidate pipeline (`python3 verification/run.py --lane candidate --event-name pull_request --base-ref main --run-id fct003-candidate`) confirming 7/7 `PASS`, `AndroidManifest.xml` offline (`INTERNET`-free until `FCT-004` policy transition), and unchanged `policies/checks.json`.
- [ ] **Step 2:** Run `evidence.py` audit, execute real-device `TEST-AUTH-312` (`KeyInfo.getSecurityLevel()`) if an authorized device is attached (otherwise keep `NOT_RUN` and block T4 hardware/release closure), and complete independent `T4`/`T3` review + manual UAT + dual Product Owner & Lead Maintainer approval before any G6 merge.
