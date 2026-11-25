# FCT-003 Requirements Specification — Authentication, Device Inventory & Connectivity Readiness

- **Change ID:** `FCT-003`
- **Milestone:** `FCT-003`
- **Document ID:** `SPEC-REQ-FCT003`
- **Milestone Risk Ceiling:** `T4 (Critical Risk)` (`T4` Auth/Credentials/Keystore + `T3` Protocol Contracts/Adapters)
- **Status:** `G0–G3 SPECIFICATION CANDIDATE (STOP AT GATE G3 IMPLEMENTATION READINESS)`
- **Provenance & Governance:** [proposal.md](proposal.md) | [risk-assessment.md](risk-assessment.md) | [specs/constitution.md](../../constitution.md) | [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md)

---

## 1. Evidence Classification Tags

In accordance with `C-001` (Evidence-First Development) and `C-014` (Clean-Room Implementation & IP Respect), every technical statement in `FCT-003` is labeled explicitly:

- **`[RE-OBSERVED]`:** Verified directly from primary decompiled Dalvik/JADX classes in the frozen RE workspace (`com.yugong.Backome` 6.1.7); overrides contradictory claims in `final/*.md` and `final/protocols.json` per [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md) §2.2.
- **`[UNRESOLVED]`:** Server-side or runtime-only behavior outside client APK observability; must not be guessed or hardcoded prior to controlled verification.
- **`[CLEAN-ROOM-DESIGN]`:** Newly specified security, storage, concurrency, or error-handling architecture authored independently for `com.weback.robot` (never inheriting legacy security vulnerabilities or proprietary code).

---

## 2. Quarantined Unresolved Protocol & Auth Details (`[UNRESOLVED]` Register)

Implementation agents MUST respect the following explicit knowledge boundaries and MUST NOT fabricate undocumented endpoints, error integers, JWT claims, or authentication secrets (`C-001`, `C-010`):

| Unknown ID | Unresolved Surface | Why Unobservable in Legacy APK Bytecode | Clean-Room Boundary Required in FCT-003 |
| :--- | :--- | :--- | :--- |
| **`UNK-AUTH-01`** | **Refresh-Token Grant Availability** | `IdentityInfo` (`IdentityInfo.java:21-25`) stores zero `refresh_token`; legacy `BaseActivity.n1()` re-runs `opt="login"` using stored plaintext password (`p0.f43937e`). Whether Grit Cloud `/prod/oauth` or `api_url` supports an explicit token-refresh `opt` is server-side (`OOS-03`). | Design single-flight `RefreshGrantStrategy` abstraction allowing optional server `refreshToken` renewal if present, while defaulting cleanly to `SessionState.ReauthenticationRequired` when absent (NEVER persisting passwords or MD5 digests). |
| **`UNK-AUTH-02`** | **HTTP Status & `BaseResponse.code` Auth Error Taxonomy** | `BaseResponse.success()` checks only `this.code == 1` (`BaseResponse.java:65`); `utils/net/d.java:120` collapses all HTTP codes outside `200..210` into a generic localized error string (`R.string.request_error`). Exact server codes for invalid password, locked account, CAPTCHA challenge, expired JWT, or invalid region are unverified. | Model structured extensible `AuthFailureReason` (`InvalidCredentials`, `ChallengeRequired`, `TokenExpiredOrRevoked`, `ServerRejected(code: Int?, safeCategory: String)`, `MalformedResponse`) without asserting unverified magic numbers beyond HTTP `401`/`403` & `code != 1` fail-closed defaults. |
| **`UNK-AUTH-03`** | **Server JWT Claim Schema & Lifetime Enforcement** | Client receives opaque `jwt_token` + integer `expired_time` seconds (`IdentityInfo.java:22-23`); APK contains no client-side JWT signature verification or claim decoder. | Treat `jwtToken` strictly as an opaque credential string driven by `expired_time` (`expiresInSeconds`) + monotonic elapsed time + server HTTP/WS rejection signals; never parse or trust unverified JWT payloads for security decisions. |
| **`UNK-AUTH-04`** | **CAPTCHA / Verification Challenge Triggers on Login** | `function/b.java:94-106,553-566` defines `get_image_captcha` and `get_verify_code_captcha` on `/prod/oauth`, alongside `client_id = "yugong_app"` in `login`. Exact triggers during login are server-enforced. | Map any server challenge response into non-crashing `AuthFailureReason.ChallengeRequired`; exclude automated CAPTCHA bypass/UI automation from `FCT-003`. |
| **`UNK-AUTH-05`** | **Server Acceptance of Plaintext TLS vs Legacy MD5 Hex Password** | `function/b.java:336` hashes entered password with `f.D(pwd)` (unsalted lowercase MD5 hex) inside JSON POST over HTTPS. Whether Grit Cloud accepts TLS-only raw credentials vs requires legacy MD5 wire compatibility is unconfirmed server-side. | Isolate wire password encoding behind `PasswordDigestStrategy` (`Utf8TlsDefault` vs isolated `LegacyCompatibilityDigest` quarantined behind explicit `FCT-004` security sign-off); persist **neither** input password nor digest. |
| **`UNK-AUTH-06`** | **WebSocket Auth Rejection Payloads & Ping Configuration** | `websocket/e.java:159-169,343-353` sends `"token"`/`"region"` upgrade headers and reconnects every `4000ms` or `20000ms` stall; exact server close code / error payload on mid-session revocation and exact keepalive interval are obfuscated/server-owned. | Parameterize reconnect backoff (`4000ms` base), stall/inactivity threshold (`20000ms`), and optional ping configuration via `ConnectionConfig`; treat HTTP `401`/`403` upgrade failures or auth close frames as non-retriable session invalidation triggers. |
| **`UNK-INV-01`** | **Post-Release `sub_type` Extensions & Full `thing_status` Variants** | `enums/p.java` lists 300+ legacy hardware prefixes and `utils/c.java::P0/l1/m1/N0/Y0/v0/n1/e1` classifies them statically; new robot models or firmware extensions outside `6.1.7` cannot be enumerated statically. | `DeviceCapabilityClassifier` cleanly classifies known prefixes, filters out peripherals/video/legacy XMPP, and assigns deterministic `UnknownModel` safe handling to unrecognized `sub_type` strings. |

---

## 3. Traceable Requirements Catalog

### REQ-AUTH-301: Protocol Evidence Provenance, Endpoint Routing & Security Boundary
*(Workstream: `T4` — Auth/Storage | Legacy Reconciliation: `REG-06`, `REG-10`)*

The application must resolve authentication and session endpoints deterministically from validated region/response inputs while enforcing strict HTTPS/WSS transport boundaries and rejecting insecure legacy practices.

- **AC-301.1:** A pure Kotlin `RegionEndpointRouter` resolves China region codes (`"+86"`, `"0086"`, normalized whitespace) to canonical CN auth URL (`"https://user.grit-cloud.cn/prod/oauth"` `[RE-OBSERVED: function/b.java:28]`) and non-CN calling codes (e.g. `"+1"`, `"+44"`, `"+49"`) to canonical Global auth URL (`"https://user.grit-cloud.com/prod/oauth"` `[RE-OBSERVED: function/b.java:31]`), strictly when configured for future cloud mode, while test/offline configs override endpoints solely with loopback/fixture URLs and blank/invalid region codes are rejected cleanly without I/O.
- **AC-301.2:** Clean-room `UriValidator` validates dynamic `apiUrl` and `wssUrl` values before storage or socket creation: non-test configurations MUST require `"https"` for `apiUrl` and `"wss"` for `wssUrl`, reject embedded `user:pass@` userinfo, reject blank hosts/invalid ports, and allow loopback HTTP/WS (`localhost` / `127.0.0.1` / `[::1]`) strictly when constructed via explicit test-only `MockServerConfig`.
- **AC-301.3:** Clean-room code MUST NOT include process-global `HttpsURLConnection.setDefaultSSLSocketFactory` overrides (`REG-10`), hardcoded test accounts/tokens, Firebase/ad trackers, or third-party P2P binaries (`C-010`, `C-014`).

---

### REQ-AUTH-302: Authentication Contract, Wire Header Placement & Negative Auth Rejection
*(Workstream: `T4` — Auth | Legacy Reconciliation: `REG-06`, `REG-08`, `REG-09`, `UNK-AUTH-02`, `UNK-AUTH-04`, `UNK-AUTH-05`)*

Authentication sign-in must execute through typed clean-room domain models and fail closed on every malformed, incomplete, or negative authentication response.

- **AC-302.1:** Pure Kotlin `com.weback.robot.domain` defines typed immutable auth contracts (`AuthCredentials`, `SessionCredentials`, `AuthResult`, sealed `AuthFailureReason`) containing zero `android.*`, `androidx.*`, `kotlinx.coroutines.*`, `kotlinx.serialization.*`, or `okhttp3.*` imports (`C-002`).
- **AC-302.2:** `OkHttpAuthGateway` serializes JSON `POST` envelope (`"header"` containing `"api_version"`, `"app_name"`, `"calling_code"`, `"account"`, `"client_id"`, `"language"`, and `"payload"` containing `"opt": "login"`, `[RE-OBSERVED: function/b.java:326-341]`) via `kotlinx.serialization.json` with `PasswordDigestStrategy` isolated behind an explicit security boundary; authenticated REST and WebSocket upgrade calls send credentials exclusively via HTTP headers `"token"` and `"region"` (`[RE-OBSERVED: function/b.java:244-247, websocket/e.java:165]`) rather than query parameters or unverified `Bearer` assumptions.
- **AC-302.3:** Negative authentication validation MUST reject HTTP `401`/`403`/`5xx` statuses, malformed JSON, `BaseResponse.code != 1`, empty/blank `jwt_token`, invalid/blank `api_url`, invalid/blank `wss_url`, blank `region_name`, or `expired_time <= 0`, returning typed `AuthResult.Failure(reason)` without modifying any currently active session or throwing uncaught exceptions.

---

### REQ-AUTH-303: Account & Session Lifecycle State Machine & Forced Session Invalidation
*(Workstream: `T4` — Auth/Session | Legacy Reconciliation: `REG-07`)*

Session state must follow a deterministic, concurrency-safe lifecycle preventing duplicate sign-ins, split-brain sessions, and stale cross-account token reuse.

- **AC-303.1:** `SessionStateMachine` models mutually exclusive states (`SignedOut`, `Authenticating`, `Authenticated(session, epoch)`, `Refreshing(session, epoch)`, `ReauthenticationRequired(reason, epoch)`) protected by single-flight synchronization so concurrent `signIn()` requests while `Authenticating` or concurrent `refresh()` requests while `Refreshing` do not fire duplicate parallel auth calls or violate transition ordering.
- **AC-303.2:** `signOut()` and forced invalidation (`invalidateSession(reason)` triggered by auth rejection `401`/`403`, Keystore corruption, or user logout) atomically transition state to `SignedOut` or `ReauthenticationRequired`, wipe persisted tokens from `TokenStore`, close active WebSocket transports cleanly, and increment monotonic `sessionEpoch` (`[CLEAN-ROOM-DESIGN]`).
- **AC-303.3:** Late network callbacks carrying a superseded `sessionEpoch` MUST be discarded cleanly without overwriting a newer session or restoring an invalidated `SignedOut` state.

---

### REQ-AUTH-304: Token Expiry Calculation, 80% Renewal Window & Passwordless Re-Auth Boundary
*(Workstream: `T4` — Auth/Session | Legacy Reconciliation: `REG-07`, `UNK-AUTH-01`, `UNK-AUTH-03`)*

Token lifetime and renewal eligibility must evaluate deterministically using `MonotonicClock` without relying on unsafe float arithmetic or legacy plaintext password replay.

- **AC-304.1:** `TokenExpiryPolicy` evaluates `SessionCredentials(expiresInSeconds > 0, issuedAtElapsedMs)` using integer/Long millisecond math (`C-004`): `isExpired = (nowMs - issuedAtElapsedMs) >= expiresInSeconds * 1000L` and `shouldProactivelyRefresh = (nowMs - issuedAtElapsedMs) > expiresInSeconds * 800L` (matching the observed `0.8` window `[RE-OBSERVED: p0.java:368]` safely without `Double.NaN` or `Int` overflow).
- **AC-304.2:** When proactive or reactive renewal triggers and a valid `RefreshGrantHandler` / `refreshToken` is explicitly supplied, `SessionManager` executes single-flight renewal across concurrent callers and atomically updates persisted `TokenStore` + `sessionEpoch` on success.
- **AC-304.3:** By default (given zero passwordless refresh grant observed in legacy `IdentityInfo`), and whenever an explicit refresh attempt fails or expires, `SessionManager` MUST NEVER read, store, or replay plaintext passwords or MD5 hashes; it clears expired bearer tokens and transitions deterministically to `ReauthenticationRequired`.

---

### REQ-AUTH-305: Android Keystore AES-256-GCM Storage Boundary & Automated Secret Redaction
*(Workstream: `T4` — Cryptography & Privacy | Governance: [specs/tech-stack.md](../../tech-stack.md) §3.3, `C-010`, `REG-07`, `REG-09`)*

Persisted session tokens must use hardware/platform-backed Android Keystore AES-256-GCM encryption without `EncryptedSharedPreferences`, fail closed on tampering/corruption, and redact all sensitive artifacts from logs and diagnostics.

- **AC-305.1:** `KeystoreAeadTokenStore` implements `TokenStore` using a non-exportable symmetric key alias in `AndroidKeyStore` (`KeyProperties.KEY_ALGORITHM_AES`, `256`-bit key size, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, `PURPOSE_ENCRYPT or PURPOSE_DECRYPT`), generating a fresh 12-byte IV via `SecureRandom`/Keystore Cipher per encryption and persisting `[schemaVersion(1B) | iv(12B) | ciphertext + 16B GCM tag]` exclusively in app-private storage (`android:allowBackup="false"`). `EncryptedSharedPreferences`, plaintext `SharedPreferences`, and disk persistence of passwords/MD5 hashes are strictly prohibited (`C-010`).
- **AC-305.2:** Any decryption or Keystore error (`AEADBadTagException`, `KeyPermanentlyInvalidatedException`, truncated record length `< 1 + 12 + 16`, unknown schema version, corrupted JSON inside plaintext payload) MUST fail closed by deleting/clearing the corrupt stored blob atomically and returning `TokenLoadResult.CorruptedOrInvalidated`, NEVER throwing an unhandled crash or falling back to unencrypted storage (`[CLEAN-ROOM-DESIGN]`).
- **AC-305.3:** `SecretRedactor` deterministically scrubs HTTP headers (`token`, `authorization`, `cookie`, `set-cookie`), JSON fields (`"jwt_token"`, `"pwd"`, `"password"`, `"refresh_token"`, `"tutk_secret"`), and bearer/JWT token substrings (`eyJ...`) from HTTP interceptors, WebSocket debug messages, logger facades, and exception telemetry (`RSK-302` / `[CLEAN-ROOM-DESIGN]` eliminating `d.java:50,181` plaintext leaks).

---

### REQ-CONN-306: Device Inventory Retrieval (`user_thing_list_get`) & Defensive Telemetry Hydration
*(Workstream: `T3` — Network Protocol & Inventory | Legacy Reconciliation: `REG-06`, `REG-08`)*

Authorized sessions must retrieve the user's cloud device inventory deterministically and map initial status fields into clean-room models without runtime crashes on partial or malformed fields.

- **AC-306.1:** `OkHttpDeviceInventoryGateway` dispatches HTTPS `POST` JSON `{"opt": "user_thing_list_get"}` (`[RE-OBSERVED: function/b.java:277-282]`) to validated `session.apiUrl` carrying HTTP headers `"token"` and `"region"`, parsing `ThingsList` (`thing_list` array of `thing_name`, `sub_type`, `thing_nickname`, `is_admin`, optional `brand_icon`, optional `thing_status` object, `[RE-OBSERVED: ThingBean.java:22-28]`) exclusively using `kotlinx.serialization.json` (`ignoreUnknownKeys = true`; zero Gson).
- **AC-306.2:** Blank `thing_name`, null/missing `data`, or `BaseResponse.code != 1` rejects malformed payloads cleanly via typed `ConnectivityError` without silently discarding prior valid inventory; a valid `code == 1` response with empty `thing_list` transitions cleanly to `InventoryState.Empty`.
- **AC-306.3:** Optional initial `thing_status` payloads (`connected`, `working_status`, `battery_level`, `error_info`) normalize safely into `RobotState` bounds (`battery_level` clamped/validated into `0..100` rather than throwing uncaught `IllegalArgumentException` on noisy telemetry `[CLEAN-ROOM-DESIGN]`, preserving `isAdmin` ownership metadata).

---

### REQ-CONN-307: Deterministic Device Capability Classification (`sub_type`) & MVP Filtering
*(Workstream: `T3` — Protocol/Device Capabilities | Provenance: `utils/c.java::c2/P0/l1/q1/m1/N0/Y0/v0/n1/e1/g1`, [specs/mission.md](../../mission.md) §3.1, §3.3)*

The application must inspect device identifiers (`sub_type` and legacy JID indicators) deterministically to identify supported MVP Laser SLAM vacuums and safely isolate out-of-scope hardware.

- **AC-307.1:** Pure Kotlin `DeviceCapabilityClassifier` implements canonical RE prefix normalization (`subType.lowercase() + "-"` prefix match `[RE-OBSERVED: utils/c.java:1131-1142]`) over immutable lookup sets grounded in `utils/c.java`: `LaserSlam` (`P0`, including visual-laser sub-variant `q1` awareness), `GyroVacuum` (`l1`/`L0` non-laser robot vacuums), `XmppLegacy` (`e1`/`T0`/`H0`), excluded peripherals (`Thermostat` `m1`, `SmartLamp` `N0`, `SmartPlugOrSwitch` `Y0`, `AirPurifierOrHumidifier` `v0`, `SetTopBox` `g1`), `ThroughTekCameraVacuum` (`n1`), and fallback `UnknownDevice` for unrecognized strings.
- **AC-307.2:** Capability policy assigns `ControlCompatibility.MvpSupportedShadowWs` strictly to verified `LaserSlam` cloud devices (`P0` excluding incompatible P2P-only paths if flagged) and marks excluded peripherals (`CAT-04`), ThroughTek camera classes (`CAT-03`), `XmppLegacy`, and `UnknownDevice` cleanly as unsupported for MVP shadow control with explanatory user-safe status (never dispatching Laser SLAM shadow commands to incompatible devices or crashing).

---

### REQ-CONN-308: Cloud WebSocket Connection Lifecycle FSM, Liveness & Reconnect Discipline
*(Workstream: `T3` — Network Connectivity | Legacy Reconciliation: `REG-09`, `UNK-AUTH-06`)*

The WebSocket transport adapter must manage upgrade headers, stateful liveness, non-blocking locking, auth-rejection short-circuiting, and idempotent closure deterministically.

- **AC-308.1:** `RealtimeSessionTransport` exposes reactive `StateFlow<ConnectionState>` (`Disconnected`, `Connecting`, `Connected`, `Reconnecting(attempt, retryDelayMs)`, `Closed`) with synchronized state ownership (`stateMutex`/`commitLock` discipline, zero locks held across socket delays or backoff sleeps `[CLEAN-ROOM-DESIGN]`).
- **AC-308.2:** `OkHttpWebSocketTransport` constructs upgrade requests to `wssUrl` carrying HTTP headers `"token"` and `"region"` (`[RE-OBSERVED: websocket/e.java:159-169]`, zero query tokens), parses inbound JSON `notify_info == "thing_status_update"` frames (`[RE-OBSERVED: websocket/e.java:198,272-277]`), enforces injectable send-stall detection (`default 20_000ms`) and bounded backoff reconnection (`default 4_000ms` base delay up to `maxReconnectAttempts`) via injected `DispatcherProvider` & `MonotonicClock`, and emits WebSocket close code `1000` on graceful `close()`.
- **AC-308.3:** Receiving HTTP `401`/`403` during handshake or an explicit server invalid-token failure cancels scheduled reconnect attempts immediately, triggers session invalidation / single-flight renewal coordination (`REQ-AUTH-303`/`304`), and guarantees `close()` is idempotent so post-close callbacks or frames never mutate `ConnectionState` or `RobotState`.

---

### REQ-CONN-309: Dual Offline & MockWebServer Protocol Adapters and Manifest/Policy Boundary
*(Workstream: `T3` — Verification & Testability | Governance: `C-006`, `C-010`, `POL-INT-01`)*

All `FCT-003` authentication, inventory, and WebSocket flows must be completely testable offline without altering active verification policies or live cloud access boundaries.

- **AC-309.1:** `OfflineFixtureAuthGateway`, `OfflineFixtureInventoryGateway`, and `OfflineFixtureRealtimeTransport` provide deterministic synthetic offline flows plugged into `DefaultAppContainer` (retaining `SimulatorRobotTransport` as default runtime control mode and preserving `"OFFLINE"` dashboard identity unless authenticated against a configured transport `[C-011]`).
- **AC-309.2:** `OkHttpAuthGateway`, `OkHttpDeviceInventoryGateway`, and `OkHttpWebSocketTransport` execute end-to-end HTTP POST, TLS/loopback upgrade, header verification, chunked/malformed payload, timeout, and reconnection contract tests against local in-process `okhttp3.mockwebserver.MockWebServer` inside `./gradlew testDebugUnitTest`, executing zero calls to public hostnames or live AWS/Grit Cloud servers (`[C-006]`).
- **AC-309.3:** `FCT-003` preserves active `policies/checks.json` and `verification/checks/apk_validator.py` unchanged: `AndroidManifest.xml` MUST NOT add `android.permission.INTERNET` until an isolated governance/policy transition authorizes it (specifically, future live `FCT-004` connectivity requires signed external PO + Lead Maintainer approval plus a separate `POL-INT-01` policy/manifest PR).

---

### REQ-CONN-310: Connectivity Error Recovery & Backward Compatibility with FCT-001/FCT-002
*(Workstream: `T3` — Reliability & Regression Safety)*

Authentication and connectivity faults must map cleanly to deterministic recovery paths while preserving all existing FCT-001 and FCT-002 invariants.

- **AC-310.1:** Typed `ConnectivityError` distinguishes `Unauthenticated`, `TokenExpired`, `AuthorizationDenied`, `ChallengeRequired`, `TransportTimeout`, `NetworkUnavailable`, `HandshakeRejected(httpCode)`, `MalformedPayload`, and `UnsupportedDevice`, exposing recoverable actions (`Retry`, `Reauthenticate`, `UnsupportedInfo`) without exposing raw tokens, headers, or stack traces.
- **AC-310.2:** WebSocket disconnects or reconnect cycles preserve the last known committed `RobotState` telemetry cleanly, block live remote commands with `CommandResult.Rejected(TRANSPORT_REJECTED)` / `Disconnected` while offline, and cleanly re-subscribe / sync state once `Connected` resumes.
- **AC-310.3:** All existing `54/54` FCT-001/FCT-002 JVM and Robolectric Compose UI tests (15% single battery threshold, `SimulatorRobotTransport.close()` commit synchronization, persistent `"OFFLINE"` simulator badge in simulator mode, synchronized milestone label mechanism, and zero live network traffic) continue passing without test weakening (`C-008`).
