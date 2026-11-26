# FCT-004B Technical Design — Pre-INTERNET Security, Pilot Build & Policy Transition

- **Change ID:** `FCT-004B`
- **Risk Tier:** `T4 (Critical Risk)`
- **References:** [proposal.md](proposal.md) | [requirements.md](requirements.md) | [validation.md](validation.md) | [FCT-004 Design](../FCT-004/design.md)

---

## 1. B-SEC-01: Single-Use Request-Bound Admission Tickets

1. **`IssuedTicketRecord` Ledger in `PilotAuthorizationGate`:**
   - `PilotAuthorizationGate` assigns a monotonic/unique `ticketId` (`"adm-<gen>-<seq>-<uuid>"`) on `acquireAdmission` and stores an authoritative `IssuedTicketRecord` inside `synchronized(lock)`.
   - `PilotAdmissionTicket` constructor becomes `internal` and carries `ticketId`, `authorizationId`, `pilotGeneration`, `operation`, `deadlineElapsedMs`, `environment`, `httpCallTimeoutMs`, `maxWsReconnectAttempts`, `issuedElapsedMs`, `remainingWindowAtIssueMs`, `boundAccountIdentifier`, `boundCallingCode`, and `boundRequestUrl`.
2. **Intermediate Verification vs. Atomic Dispatch Consumption:**
   - `verifyTicket(...)` (used by `PilotCapabilityRestrictedAuthGateway`, `PilotCapabilityRestrictedInventoryGateway`, and `OkHttpWebSocketTransport`) validates existence in `issuedTickets`, exact field equality against `IssuedTicketRecord` (blocking `.copy()` tampering), non-consumption, active generation, environment, deadline (`clock` / `nowElapsedMs`), operation, URL binding, and `endpointValidator` **without** consuming the ticket.
   - `consumeTicketForDispatch(...)` (invoked in `PilotClientFactory`'s OkHttp dispatch interceptor immediately before `activeCallRegistry.registerForGeneration` and `chain.proceed`) atomically validates all ticket/request bindings (`POST` + `"login"` JSON for `LOGIN`, `POST` + `"user_thing_list_get"` JSON for `USER_THING_LIST_GET`, `GET` + `Upgrade: websocket` for `WS_TELEMETRY_OBSERVE`) and marks `record.consumed = true` under `lock`. Any subsequent replay fails closed before touching the socket.

---

## 2. B-SEC-02: Gateway-Boundary Account & Region Binding

1. **`PilotCapabilityRestrictedAuthGateway.probeLogin`:**
   - Immediately after copying `secretChars` and verifying non-blank inputs, if `admissionGate != null`, call `admissionGate.verifyAccountCredentials(credentials.regionCallingCode, credentials.account)` **before** `RegionEndpointRouter.resolve`, `digestStrategy.encodeForTlsPayload`, `LoginRequestEnvelope` serialization, or OkHttp dispatch.
   - On mismatch: wipe `secretChars`, log `PILOT_AUTH_ACCOUNT_MISMATCH`, and return `PilotAuthProbeOutcome.Failed(AuthFailureReason.ConfigurationError, ...)` with zero network I/O.
2. **Ticket Account Binding:**
   - `PilotAuthorizationGate.acquireAdmission(PilotOperation.LOGIN, ...)` binds the active context's normalized `authorizedAccountIdentifier` and `regionCallingCode` on `IssuedTicketRecord` and re-verifies them if credentials are supplied.

---

## 3. B-SEC-03: Absolute Android Monotonic Deadline & Complete Cleanup

1. **Zero `System.nanoTime()` in Pilot Deadline Enforcement:**
   - Remove `issuedSystemNanoTime` and `System.nanoTime()` from `PilotAdmissionTicket` and `PilotAuthorizationGate`.
   - Define pure-Kotlin `PilotClock` in `com.weback.robot.domain.pilot` (`fun interface PilotClock { fun elapsedRealtimeMs(): Long }`), wired in `DefaultAppContainer` and `PilotSessionController` to `authClock.elapsedRealtimeMs()` (`AndroidAuthMonotonicClock` -> `SystemClock.elapsedRealtime()`).
   - `PilotClientFactory.buildPilotOkHttpClient` accepts `authClock: AuthMonotonicClock? = null` and passes `authClock?.elapsedRealtimeMs()` into `consumeTicketForDispatch`.
2. **Complete Generation-Fenced Expiry Cleanup:**
   - `PilotAuthorizationGate` exposes `attachOnWindowExpiredListener(listener: ((Long) -> Unit)?)`. Whenever `authorizeOperation`, `acquireAdmission`, `verifyTicket`, or `consumeTicketForDispatch` detects `nowElapsedMs >= observationDeadlineElapsedMs`, it revokes `activeContext` and notifies the listener with the revoked generation outside `lock`.
   - `PilotSessionController` handles gate window expiry (and `enforceDeadlineIfExpired()` / `scheduleObservationDeadline` deep-sleep wakeups) via generation-fenced 9-step cleanup: immediate `activeCallRegistry?.revokeAndCancelAll(revokedGen)`, `trustPolicy.clearConfirmedDynamicOrigins()`, `pilotTelemetryTransport.forceDisconnect`, `sessionCoordinator.signOutIfEpoch`, `TokenStore.clear()`, `OfflineDefault` restoration, and `PilotAbortReason.ObservationWindowExpired` state publication — ignoring stale generations.

---

## 4. Isolated `pilot` Build Type & Separate Identity (`com.weback.robot.pilot`)

1. **`app/build.gradle.kts`:**
   - Enable `buildFeatures.buildConfig = true`.
   - `defaultConfig`: `applicationId = "com.weback.robot"`, `minSdk = 31`, `targetSdk = 35`, `PILOT_INTERNET_CAPABLE = false`, `APP_CONNECTIVITY_MODE = "OFFLINE"`.
   - `buildTypes.create("pilot")`: `initWith(getByName("debug"))`, `applicationIdSuffix = ".pilot"`, `matchingFallbacks += listOf("debug")`, `PILOT_INTERNET_CAPABLE = true`, `APP_CONNECTIVITY_MODE = "PILOT"`.
   - Add `pilotImplementation(platform(libs.androidx.compose.bom))` and `pilotImplementation(libs.androidx.compose.ui.test.manifest)` so Robolectric Compose tests pass identically on `testPilotUnitTest`.
2. **Manifest & Resources:**
   - `app/src/main/AndroidManifest.xml`: unchanged (`INTERNET`-free).
   - `app/src/pilot/AndroidManifest.xml`: declares strictly `<uses-permission android:name="android.permission.INTERNET" />`.
   - `app/src/pilot/res/values/strings.xml`: sets `<string name="app_name">WeBack Robot Pilot (Read-Only)</string>` (preserving `app_version` compatibility with existing UI tests) and `PilotReadinessSheet` / `DashboardScreen` keep default `OFFLINE` startup with zero auto-auth or live commands.
3. **External Verification vs. Runtime Identity:**
   - Replace `nonActivatableLiveIdentity`'s placeholder strings with `RuntimeAppIdentity(applicationId, versionName, versionCode, pilotBuildEnabled)` populated from `BuildConfig` (`PILOT_INTERNET_CAPABLE`).
   - `PilotAuthorizationGate.activate(context, expectedRuntimeIdentity: RuntimeAppIdentity, ...)` verifies `applicationId`, `versionName`, `pilotBuildEnabled`, and valid 40-hex commit SHA + 64-hex external APK SHA-256 format without requiring the APK to embed its own final SHA-256.

---

## 5. `apk_validator.py` & Policy Transition

1. **`apk_validator.py --connectivity-policy {offline,pilot}`:**
   - Default `--connectivity-policy offline` (`app/build/outputs/apk/debug/app-debug.apk`, `com.weback.robot`, forbids `INTERNET`).
   - `--connectivity-policy pilot` (`app/build/outputs/apk/pilot/app-pilot.apk`, `com.weback.robot.pilot`, requires `INTERNET`, forbids all other external permissions, checks `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` and `${applicationId}.androidx-startup` authorities).
   - Explicit `--apk` pointing to the opposite variant path (`debug` vs `pilot`) is rejected before inspection as artifact substitution.
2. **Policy Files:**
   - `policies/checks.baseline.json` holds the currently approved 7-check baseline (`e688b6fef22290281facf91d26f42ae53ff527ca7f6b599a750d24d66873985e`).
   - `policies/checks.json` holds the proposed 11-check candidate policy (7 existing + `pilot-android-lint`, `pilot-android-unit-tests`, `pilot-android-build`, `pilot-apk-validation`), evaluated via `--lane fast` until `TRANSITION_AUTHORIZATION` is explicitly approved.
