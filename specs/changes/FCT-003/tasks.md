# FCT-003 Implementation Plan — Authentication, Device Inventory & Connectivity Readiness

> **Gate Boundary:** This specification baseline targets **Gate G3 (Implementation Readiness)** ONLY. **DO NOT execute any code implementation task (`Task 1`–`Task 6`) inside `spec/fct-003-authentication-connectivity`.** Implementation requires formal Product Owner + Lead Maintainer approval (`T4` milestone sign-off), isolated implementation branch creation from updated `main`, and Lead Maintainer confirmation of dependency pinning (`OkHttp 4.12.0`, `MockWebServer 4.12.0`, `kotlinx-serialization-json 1.7.3`).

**Goal:** Deliver `T4` clean-room authentication, single-flight session lifecycle, passwordless token expiry policy, Android Keystore AES-256-GCM encrypted token storage (without `EncryptedSharedPreferences`), automated secret redaction, and `T3` `user_thing_list_get` device inventory, `sub_type` capability filtering, WebSocket lifecycle transport, and hermetic Offline + `MockWebServer` adapters under zero live network access.

**Architecture:** Extends `:app` clean package structure (`core`, `domain`, `security`, `protocol`, `app/session`). Pure Kotlin `domain` remains 100% isolated (`C-002`). Default runtime stays strictly offline (`SimulatorRobotTransport` + `OfflineFixture*` adapters, zero `INTERNET` manifest permission), keeping live `grit-cloud` / AWS IoT / `FCT-004` pilot connectivity gated behind external authorization and a separate `POL-INT-01` policy/manifest PR.

**Spec & Governance:** [proposal.md](proposal.md) | [risk-assessment.md](risk-assessment.md) | [requirements.md](requirements.md) | [design.md](design.md) | [validation.md](validation.md) | [traceability.json](traceability.json)

---

## Global Pre-Implementation Constraints
- **Gate G3 Hard Stop:** Stop after PR creation and verification of this specification; await explicit human G3 approval before Task 1 begins.
- **Zero Policy/Harness Edits (`POL-INT-01`):** Keep `policies/checks.json` (7 checks) and `verification/` untouched. Keep `android.permission.INTERNET` excluded from `AndroidManifest.xml` throughout `FCT-003` offline verification (`apk_validator.py`).
- **Zero Plaintext Password Storage / Zero MD5 Defaults / Zero `EncryptedSharedPreferences` (`C-010`):** Strict TDD verification across `T4` security modules.
- **Zero Live Network or Production Secrets (`C-006`, `C-010`):** All unit/contract tests execute deterministically against in-process fixtures and loopback `okhttp3.mockwebserver.MockWebServer`.

---

### Task 1: Pure Kotlin Auth, Session, Expiry & Device Capability Domain (`domain`)

**Files (Target Scope Upon Implementation Approval):**
- Create: `app/src/main/java/com/weback/robot/domain/model/AuthModels.kt`
- Create: `app/src/main/java/com/weback/robot/domain/model/ConnectivityModels.kt`
- Create: `app/src/main/java/com/weback/robot/domain/auth/TokenExpiryPolicy.kt`
- Create: `app/src/main/java/com/weback/robot/domain/auth/SessionStateMachine.kt`
- Create: `app/src/main/java/com/weback/robot/domain/device/DeviceCapabilityClassifier.kt`
- Create: `app/src/test/java/com/weback/robot/domain/TokenExpiryPolicyTest.kt`
- Create: `app/src/test/java/com/weback/robot/domain/SessionStateMachineTest.kt`
- Create: `app/src/test/java/com/weback/robot/domain/DeviceCapabilityClassifierTest.kt`
- Update: `app/src/test/java/com/weback/robot/domain/DomainPurityTest.kt` (extend coverage automatically over new `domain/` files)

**Covers:** `REQ-AUTH-302` (`AC-302.1`), `REQ-AUTH-303` (`AC-303.1..3`), `REQ-AUTH-304` (`AC-304.1`), `REQ-CONN-307` (`AC-307.1..2`).

- [ ] **Step 1 (TDD RED):** Author unit tests for `TokenExpiryPolicy` (`80%` window exact boundaries + `Int.MAX_VALUE` overflow check), `SessionStateMachine` (epoch isolation + single-flight guards), `DeviceCapabilityClassifier` (`P0`, `q1`, `l1`, `m1`, `N0`, `Y0`, `v0`, `n1`, `e1`, unknown strings), and pure-Kotlin import boundaries (`DomainPurityTest`). Confirm compilation/test failure.
- [ ] **Step 2 (GREEN):** Implement pure Kotlin domain models, `TokenExpiryPolicy`, `SessionStateMachine`, and `DeviceCapabilityClassifier` with zero Android/coroutine/OkHttp/serialization imports.
- [ ] **Step 3 (Verify):** Run `./gradlew testDebugUnitTest --tests "com.weback.robot.domain.*"`.

---

### Task 2: T4 Security Enclave — Keystore AES-256-GCM Storage, URI Validator & Secret Redactor (`security`)

**Files:**
- Modify (Pre-approved dependency update): `gradle/libs.versions.toml`, `app/build.gradle.kts` (pin `kotlinx-serialization-json:1.7.3` + `kotlin.plugin.serialization:2.1.21` per [specs/tech-stack.md](../../tech-stack.md))
- Create: `app/src/main/java/com/weback/robot/security/TokenStore.kt`
- Create: `app/src/main/java/com/weback/robot/security/KeystoreAeadTokenStore.kt`
- Create: `app/src/main/java/com/weback/robot/security/UriValidator.kt`
- Create: `app/src/main/java/com/weback/robot/security/SecretRedactor.kt`
- Create: `app/src/main/java/com/weback/robot/security/PasswordDigestStrategy.kt`
- Create: `app/src/test/java/com/weback/robot/security/KeystoreAeadTokenStoreTest.kt`
- Create: `app/src/test/java/com/weback/robot/security/KeystoreAeadTokenStoreTamperTest.kt`
- Create: `app/src/test/java/com/weback/robot/security/UriValidatorTest.kt`
- Create: `app/src/test/java/com/weback/robot/security/SecretRedactorTest.kt`

**Covers:** `REQ-AUTH-301` (`AC-301.1..3`), `REQ-AUTH-305` (`AC-305.1..3`).

- [ ] **Step 1 (TDD RED):** Write tests verifying: unique 12B IVs per encryption, zero plaintext JWT bytes in raw disk blob, fail-closed wipe on bit-flip/AAD-mismatch/truncation/`KeyPermanentlyInvalidatedException`, `UriValidator` scheme/userinfo enforcement, zero `EncryptedSharedPreferences`/`setDefaultSSLSocketFactory` static checks, and `SecretRedactor` scrubbing of headers, JSON secrets, and `eyJ...` JWTs. Observe RED failures.
- [ ] **Step 2 (GREEN):** Implement `KeystoreAeadTokenStore` (ADR-003 `[0x01 | 12B IV | ciphertext+tag]`), `UriValidator`, `SecretRedactor`, and `PasswordDigestStrategy`.
- [ ] **Step 3 (Verify):** Run `./gradlew testDebugUnitTest --tests "com.weback.robot.security.*"`.

---

### Task 3: T4/T3 Auth & Inventory Gateways + Single-Flight `SessionCoordinator` (`protocol/auth`, `protocol/inventory`, `app/session`)

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
- Create: `app/src/test/java/com/weback/robot/protocol/inventory/OkHttpDeviceInventoryGatewayTest.kt`
- Create: `app/src/test/java/com/weback/robot/app/session/SessionCoordinatorTest.kt`

**Covers:** `REQ-AUTH-301` (`AC-301.1`), `REQ-AUTH-302` (`AC-302.2..3`), `REQ-AUTH-303` (`AC-303.1..3`), `REQ-AUTH-304` (`AC-304.2..3`), `REQ-CONN-306` (`AC-306.1..3`), `REQ-CONN-309` (`AC-309.1`).

- [ ] **Step 1 (TDD RED):** Write `MockWebServer` contract tests covering `POST` `login` envelope (`header`/`payload`), `user_thing_list_get` (`"token"` & `"region"` headers), negative HTTP `401`/`403`/`500` & malformed JSON/`code != 1`/empty-field rejections, defensive `thing_status` battery clamping, single-flight signIn/refresh, late-epoch callback rejection, and passwordless `ReauthenticationRequired` transition. Observe RED failures.
- [ ] **Step 2 (GREEN):** Implement `RegionEndpointRouter`, `OkHttp*` & `OfflineFixture*` gateways with redacted interceptor logging, `SessionCoordinator`, and `DeviceInventoryRepository`.
- [ ] **Step 3 (Verify):** Run `./gradlew testDebugUnitTest --tests "com.weback.robot.protocol.*" --tests "com.weback.robot.app.*"`.

---

### Task 4: T3 Cloud WebSocket Lifecycle Transport, Heartbeat/Reconnect & Idempotent Close (`protocol/ws`)

**Files:**
- Create: `app/src/main/java/com/weback/robot/protocol/ws/ConnectionConfig.kt`
- Create: `app/src/main/java/com/weback/robot/protocol/ws/RealtimeSessionTransport.kt`
- Create: `app/src/main/java/com/weback/robot/protocol/ws/OkHttpWebSocketTransport.kt`
- Create: `app/src/main/java/com/weback/robot/protocol/ws/OfflineFixtureRealtimeTransport.kt`
- Create: `app/src/test/java/com/weback/robot/protocol/ws/OkHttpWebSocketTransportTest.kt`

**Covers:** `REQ-CONN-308` (`AC-308.1..3`), `REQ-CONN-309` (`AC-309.1..2`), `REQ-CONN-310` (`AC-310.1..2`).

- [ ] **Step 1 (TDD RED):** Write loopback `MockWebServer.withWebSocketUpgrade` tests under `StandardTestDispatcher` verifying `"token"`/`"region"` upgrade headers (zero query params), `"thing_status_update"` message hydration, `20_000ms` send-stall trigger and `4_000ms` reconnect scheduling, HTTP `401`/`403` reconnect short-circuit + session invalidation, non-destructive telemetry retention on socket drop, and `FIX-201`-grade synchronized idempotent `close()` (`1000` code, zero post-close mutation). Observe RED failures.
- [ ] **Step 2 (GREEN):** Implement `ConnectionConfig`, `RealtimeSessionTransport`, `OkHttpWebSocketTransport`, and `OfflineFixtureRealtimeTransport`.
- [ ] **Step 3 (Verify):** Run `./gradlew testDebugUnitTest --tests "com.weback.robot.protocol.ws.*"`.

---

### Task 5: DI Container Integration, Offline Mode Preservation & Error Recovery Tests (`core`, Integration Tests)

**Files:**
- Modify: `app/src/main/java/com/weback/robot/core/AppContainer.kt` (wire `TokenStore`, `SecretRedactor`, `SessionCoordinator`, `DeviceInventoryRepository`, `OfflineFixture*` defaults while preserving FCT-002 `SimulatorRobotTransport`)
- Create: `app/src/test/java/com/weback/robot/ConnectivityRecoveryTest.kt`
- Modify: `app/src/test/java/com/weback/robot/core/AppContainerTest.kt` (add session/inventory cleanup checks without weakening existing assertions)

**Covers:** `REQ-CONN-309` (`AC-309.1..3`), `REQ-CONN-310` (`AC-310.1..3`).

- [ ] **Step 1 (TDD RED):** Write end-to-end offline integration & fault-recovery tests verifying `DefaultAppContainer` starts offline with `"OFFLINE"` identity intact, end-to-end signIn -> inventory -> capability filter -> WS disconnect/reconnect -> signOut wipe cycle, and zero FCT-001/FCT-002 test regressions.
- [ ] **Step 2 (GREEN):** Extend `DefaultAppContainer` (`close()` tears down `SessionCoordinator` + transports + simulator deterministically).
- [ ] **Step 3 (Verify):** Execute full `./gradlew testDebugUnitTest` confirming all FCT-001, FCT-002, and FCT-003 tests pass within 180s.

---

### Task 6: Candidate Verification Under Existing Policy & Gate G4–G6 Package

**Files (Upon Implementation Completion Only):**
- Modify: `specs/changes/FCT-003/traceability.json` (populate actual `VERIFIED` execution evidence refs strictly from observed test runs)
- Update: `verification/defects.jsonl` / `regression-backlog.md` (strictly for any real defects uncovered during implementation, never fabricated)

**Covers:** `REQ-AUTH-301..305`, `REQ-CONN-306..310` (Gate readiness & APK verification).

- [ ] **Step 1:** Run full 7-check candidate pipeline (`python3 verification/run.py --lane candidate --event-name pull_request --base-ref main --run-id fct003-candidate`) confirming 7/7 `PASS`, `AndroidManifest.xml` offline (`INTERNET`-free until `FCT-004` policy transition), and unchanged `policies/checks.json`.
- [ ] **Step 2:** Run `evidence.py` audit and independent `T4`/`T3` review before human G4–G6 sign-off (keeping live `FCT-004` hardware/cloud testing strictly separate and `NOT_RUN` unless authorized).
