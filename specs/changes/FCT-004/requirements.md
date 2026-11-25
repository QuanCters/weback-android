# FCT-004 Requirements Specification — Controlled Connectivity Pilot

- **Change ID:** `FCT-004`
- **Milestone:** `FCT-004`
- **Document ID:** `SPEC-REQ-FCT004`
- **Milestone Risk Ceiling:** `T4 (Critical Risk)`
- **Status:** `G0–G3 CALIBRATED SPECIFICATION BASELINE (READ-ONLY PILOT SPEC; NO IMPLEMENTATION)`
- **Provenance & Governance:** [proposal.md](proposal.md) | [risk-assessment.md](risk-assessment.md) | [specs/constitution.md](../../constitution.md) | [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md)

---

## 1. Evidence Classification Tags (`SEC-406`)

In accordance with `C-001` and `C-014`, every protocol field and design mechanism is explicitly classified:
- **`[RE-OBSERVED]`:** Verified directly from primary decompiled Dalvik/JADX classes in `com.yugong.Backome` 6.1.7 (`REG-06`..`REG-10`). Observed legacy client behavior does NOT confirm current live server acceptance.
- **`[UNRESOLVED]`:** Server-side or runtime-only behavior outside client APK observability; must be observed via controlled pilot without guessing.
- **`[CLEAN-ROOM-DESIGN]`:** Newly specified clean-room pilot safety, authorization, endpoint trust, read-only isolation, and emergency abort mechanisms for `com.weback.robot`.

---

## 2. Protocol Evidence Audit & Unresolved Matrix (`SEC-406`)

### 2.1 Intended Live Request Field Evidence & `AuthEnvelopeConfig` Classification

| Wire Surface & Field | Current Value / Config | Classification | Exact RE / Code Evidence Reference | Unresolved Current Server Behavior |
| :--- | :--- | :--- | :--- | :--- |
| Auth endpoint CN | `https://user.grit-cloud.cn/prod/oauth` | `[RE-OBSERVED]` | `function/b.java:28`, `e0.java:144-149` (`+86`/`0086`) | Live server availability & TLS CA chain (`UNK-AUTH-08`) |
| Auth endpoint Global | `https://user.grit-cloud.com/prod/oauth` | `[RE-OBSERVED]` | `function/b.java:31` (non-CN calling codes) | Live server availability & TLS CA chain (`UNK-AUTH-08`) |
| `header.api_version` | `AuthEnvelopeConfig.DEFAULT_PROTOCOL_API_VERSION = "6.1.7"` **(fixture/legacy placeholder)** | `[RE-OBSERVED]` / `[UNRESOLVED]` | `function/b.java:328` reads `v0.l(TApplication.b())` (`PackageInfo.versionName`, `"6.1.7"` in legacy APK); `b.smali:918-977` | `UNK-AUTH-07`: Whether current server accepts `"6.1.7"` is **unconfirmed**; not a confirmed server contract |
| `header.language` | `AuthEnvelopeConfig.languageTag = "en"` **(fixture-only default)** | `[CLEAN-ROOM-DESIGN]` / `[UNRESOLVED]` | `function/b.java:333` calls `v0.u()` (`locale.getLanguage()` or `"zh-TW"`); `"en"` is a clean-room fixture default in `AuthGateway.kt:10` | `UNK-AUTH-07`: Exact server-accepted language tags are **unconfirmed** |
| `header.app_name` | `AuthEnvelopeConfig.appName = "WeBack"` **(fixture/legacy default)** | `[RE-OBSERVED]` / `[UNRESOLVED]` | `function/b.java:329`, `b.smali:918-977` (`AuthGateway.kt:11`) | `UNK-AUTH-07`: Current server validation of `app_name` is **unconfirmed** |
| `header.client_id` | `AuthEnvelopeConfig.clientId = "yugong_app"` **(fixture/legacy default)** | `[RE-OBSERVED]` / `[UNRESOLVED]` | `function/b.java:332`, `b.smali:918-977` (`AuthGateway.kt:12`) | `UNK-AUTH-04`, `UNK-AUTH-07`: Current server client binding / CAPTCHA triggers are **unconfirmed** |
| `header.calling_code`, `header.account` | User-supplied calling code + trimmed account | `[RE-OBSERVED]` | `function/b.java:330-331`, `e0.java:144-149` | `UNK-AUTH-02`: Account error codes (`BaseResponse.code`) |
| `payload.opt` | `"login"` | `[RE-OBSERVED]` | `function/b.java:335` | `UNK-AUTH-02`, `UNK-AUTH-04` |
| `payload.pwd` | `Utf8TlsDefault` (raw UTF-8 over TLS) | `[CLEAN-ROOM-DESIGN]` | Legacy `function/b.java:336` sent `f.D(pwd)` (unsalted lowercase MD5 hex); `FCT-003` prohibits MD5 fallback | `UNK-AUTH-05`: Login rejection is an observed failure, **not** definitive proof of a password-encoding requirement |
| Inventory POST `{"opt":"user_thing_list_get"}` + headers `"token"`, `"region"` | Sent to `session.apiUrl` | `[RE-OBSERVED]` | `function/b.java:244-247,277-282`, `ThingBean.java:22-28`, `IdentityInfo.java:21-25` | `UNK-AUTH-07`, `UNK-INV-01`: Dynamic `api_url` origin & live `sub_type`/`thing_status` schema |
| WebSocket upgrade headers `"token"`, `"region"` | Sent to `session.wssUrl` | `[RE-OBSERVED]` | `websocket/e.java:159-169` | `UNK-AUTH-06`, `UNK-AUTH-07`: Dynamic `wss_url` origin, close codes, keepalive, whether server requires app-level subscription messages |

### 2.2 Unresolved Protocol & Auth Matrix (`UNK-AUTH-01` .. `UNK-INV-01`)

| Unknown ID | Unresolved Question | Observation Probe in FCT-004 Pilot | Required Pass / Abort Outcome |
| :--- | :--- | :--- | :--- |
| **`UNK-AUTH-01`** | Does Grit Cloud support a passwordless refresh `opt`, or does `IdentityInfo` omit `refresh_token` completely? | Inspect redacted login response keys in `OkHttpAuthGateway` (`jwt_token`, `expired_time`, absence/presence of refresh fields). | Keep `DisabledPasswordlessRefreshStrategy` active; never implement passwordless refresh without verified server support; transition to `ReauthenticationRequired` on expiry. |
| **`UNK-AUTH-02`** | What exact HTTP status codes and `BaseResponse.code` values map to bad password, locked account, expired token, or region mismatch? | Observe HTTP status + integer `BaseResponse.code` + safe error category via allowlisted diagnostics. | Map cleanly via `AuthFailureReason.ServerRejected(httpStatus, serverCode, safeCategory)`; never log raw server bodies. |
| **`UNK-AUTH-03`** | How does the server enforce `expired_time` and JWT expiration across REST & WS calls? | Compare `expired_time` from `IdentityInfo` against `TokenExpiryPolicy` monotonic elapsed lifetime during read-only pilot window. | Enforce dual-clock `TokenExpiryPolicy` + pre-send/post-response checks; never parse or trust unverified JWT claims. |
| **`UNK-AUTH-04`** | Does `/prod/oauth` trigger `get_image_captcha` or `get_verify_code_captcha` during `login`? | Observe whether `login` returns a challenge indicator or succeeds cleanly on dedicated test account. | **Abort / Halt on Challenge:** Map to `AuthFailureReason.ChallengeRequired` and stop. Automated CAPTCHA solving/bypass is strictly prohibited. |
| **`UNK-AUTH-05`** | Does Grit Cloud `/prod/oauth` accept raw UTF-8 `pwd` over TLS (`Utf8TlsDefault`) or require legacy lowercase MD5 hex (`f.D(pwd)`)? | Execute single authorized sign-in (`1` attempt, `0` retries) using `Utf8TlsDefault`. | If accepted: record confirmation. If rejected: **STOP** pilot, treat rejection as an observed failure (not definitive proof of encoding requirement), and escalate to Lead Maintainer. Never implement MD5 fallback. |
| **`UNK-AUTH-06`** | What WebSocket close codes, revocation frames, ping/pong intervals, and subscription messages (if any) does `wss_url` use? | Observe `onOpen`, inbound frames, `onClosing(code)`, `onClosed(code)`, and `onFailure(httpCode)` during read-only WS observation. | Keep `20_000ms` send-stall distinct from keepalive; abort reconnects on `401`/`403`/`3xx`; if server requires outbound app-level subscription messages, **STOP** and obtain a separately verified protocol contract. |
| **`UNK-AUTH-07`** | Does Grit Cloud accept fixture `AuthEnvelopeConfig` values (`api_version`, `language`, `client_id`, `app_name`), and what exact `api_url`/`wss_url` origins are returned? | Inspect normalized `(scheme, host, port)` of returned `api_url` and `wss_url` under quarantine. | Require `https://`/`wss://` `:443`, globally reachable IP, verified provenance, and explicit authorization; never infer wildcard host lists. |
| **`UNK-AUTH-08`** | Does Android 12+ (`minSdk = 31`) system CA store trust `user.grit-cloud.*`, `api_url`, and `wss_url` without custom `AmazonRootCA1.cer`? | Observe standard `OkHttpClient` platform TLS 1.2/1.3 handshake (`SSLSocketFactory` default). | If handshake succeeds: confirm system CAs suffice. If `SSLHandshakeException`: **ABORT** pilot; zero trust-all or global SSL mutations. |
| **`UNK-INV-01`** | What exact `sub_type` and `thing_status` fields does the PO's physical Laser SLAM robot report live? | Inspect `user_thing_list_get` response and read-only `thing_status_update` frames via `DeviceCapabilityClassifier`. | Verify `LaserSlam` (`MvpSupportedShadowWs`) classification and safe `0..100` telemetry hydration; ignore unknown keys safely. |

---

## 3. Traceable Requirements Catalog

### REQ-PILOT-401: Separated Pilot Authorization Contract & Default Offline Isolation (`SEC-401`)
*(Workstream: `T4` — Governance & Pilot Gate)*

Live cloud connectivity must remain strictly disabled by default and gated behind an immutable, externally approved authorization contract that never persists across process restarts.

- **AC-401.1:** `PilotAuthorizationGate` defaults to `Disabled` (`OfflineDefault`) at application startup and in all automated unit/Robolectric/CI test suites, preserving `SimulatorRobotTransport` and the `"OFFLINE"` badge; pilot authorization MUST NEVER be persisted to disk or restored across process restart.
- **AC-401.2:** Every authorized pilot session requires a validated immutable `PilotAuthorizationContext` binding `authorizationId`, exact accepted candidate SHA & APK identity (`expectedApplicationId`, `expectedVersionName`, `expectedApkSha256`), authorized test account scope, exact target robot identity (`LaserSlam` + `expectedTargetThingName`), permitted operation set (`login`, `user_thing_list_get`, `ws_telemetry_observe`), approved endpoint configuration, `readOnlyMode = true` / `physicalRobotCommandsPermitted = false`, request budgets, and monotonic observation deadline (`authorizedAtElapsedMs`, `observationDeadlineElapsedMs`). Expired, mismatched, or incomplete contexts MUST be rejected fail-closed before any network call.
- **AC-401.3:** Governance separates (1) G3 implementation authorization (`FCT-004A`), (2) T4 pilot-build/policy transition authorization (`FCT-004B`), and (3) external live-pilot execution authorization without unnecessary PKI infrastructure. An in-app UI checkbox MUST NEVER silently substitute for required external human approval, and the specification documents actual trust properties and limitations of the human-supervised mechanism.

---

### REQ-PILOT-402: Endpoint Trust, Quarantined Dynamic Origins & IPv4/IPv6 Address Isolation (`SEC-403`)
*(Workstream: `T4` — Endpoint Trust & Network Security)*

Authorized pilot connections must enforce exact origin/path validation, quarantined dynamic origin handling with verified provenance, explicit special-use IPv4/IPv6 CIDR blocking, redirect/proxy isolation, and standard platform TLS verification.

- **AC-402.1:** `AuthorizedPilotEndpointTrustPolicy` authorizes auth requests ONLY to exact RE-grounded origins + exact path (`https://user.grit-cloud.cn:443/prod/oauth` or `https://user.grit-cloud.com:443/prod/oauth`), enforces `https`/`wss` ONLY, port `443` ONLY, and rejects embedded userinfo, credential query parameters/fragments, wildcard/suffix allowlists, redirects (`followRedirects(false)`, `followSslRedirects(false)`), and proxies (`Proxy.NO_PROXY`).
- **AC-402.2:** Resolves `FCT-003` immediate response-origin rejection vs `FCT-004` `AwaitingOriginConfirmation`: unapproved dynamic `api_url`/`wss_url` origins keep the session strictly **quarantined** (never publishing `SessionState.Authenticated`, never persisting to `TokenStore`, and never forwarding `"token"`/`"region"` headers). Newly observed dynamic origins require verified endpoint provenance AND explicit authorization before acceptance; human UI confirmation alone MUST NOT be treated as proof that an arbitrary HTTPS host belongs to WeBack.
- **AC-402.3:** Explicit IPv4 and IPv6 special-use CIDR validation inspects raw IP bytes across ALL resolved DNS addresses AND the actual connection destination where applicable, rejecting loopback (`127.0.0.0/8`, `::1`), link-local & cloud metadata (`169.254.0.0/16`, `fe80::/10`), private RFC1918 (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), ULA (`fc00::/7`), CGNAT (`100.64.0.0/10`), IPv4-mapped IPv6 (`::ffff:0:0/96` unwrapped to IPv4 rules), multicast (`224.0.0.0/4`, `ff00::/8`), unspecified (`0.0.0.0/8`, `::`), NAT64/Teredo/6to4/TEST-NET/benchmarking/reserved/broadcast ranges. Tests use injected DNS fixtures and hermetic loopback adapters with zero real cloud DNS queries, and specifications do not claim generic `InetAddress` predicates alone prove public reachability.
- **AC-402.4:** Pilot `OkHttpClient` instances MUST use standard Android platform TLS 1.2/1.3 `TrustManager` and `HostnameVerifier` (`network_security_config.xml` `cleartextTrafficPermitted="false"`), failing closed on any `SSLHandshakeException` without global `SSLContext.setDefault` / `setDefaultSSLSocketFactory` overrides or trust-all verifiers.

---

### REQ-PILOT-403: Protocol Unknown Probes, Fixture Classification & Non-Bypass Fail-Closed Guards (`SEC-406`)
*(Workstream: `T4` — Protocol Safety & Integrity)*

Outstanding authentication protocol unknowns (`UNK-AUTH-01`..`08`) must be observed via allowlisted diagnostics with strict fail-closed handling and zero security downgrades.

- **AC-403.1:** Pilot gateways record allowlisted protocol metadata (`httpStatus`, `BaseResponse.code`, `expired_time` bounds, normalized `api_url`/`wss_url` origin, TLS handshake outcome) via `DiagnosticLogger` without logging raw requests or server responses, and abort immediately on `AuthFailureReason.ChallengeRequired` (`UNK-AUTH-04`) with zero CAPTCHA automation.
- **AC-403.2:** Pilot authentication uses `Utf8TlsDefault` exclusively (`UNK-AUTH-05`) and keeps `DisabledPasswordlessRefreshStrategy` disabled (`UNK-AUTH-01`). Login rejection is treated as an observed failure rather than definitive proof of a password-encoding requirement, and zero legacy MD5 fallback or passwordless refresh is implemented.

---

### REQ-PILOT-404: Ephemeral Credential Entry, Screen Protection & Buffer Zeroization (`SEC-406`)
*(Workstream: `T4` — Credential Privacy & Keystore)*

User-entered pilot test credentials must have bounded in-memory secret lifetime, zero persistence or saved-state retention, screenshot protection during entry, and immediate mutable-buffer cleanup.

- **AC-404.1:** `PilotReadinessSheet` collects synthetic credentials via a mutable `CharArray` buffer with `PasswordVisualTransformation`, `KeyboardType.Password`, `autoCorrectEnabled = false`, `WindowManager.LayoutParams.FLAG_SECURE` screenshot protection while credential entry is active, zero password persistence, and zero saved-state password retention (`SavedStateHandle`), wiping mutable `CharArray` buffers on submit, cancel, error, and screen exit (`DisposableEffect` + `finally`) without claiming complete JVM immutable `String` zeroization.
- **AC-404.2:** `KeystoreAeadTokenStore` (`AndroidKeyStore` AES-256-GCM) persists ONLY `PersistedSessionRecord` (never input passwords, MD5 digests, or `PilotAuthorizationContext`), while `SessionCoordinator` preserves conservative `ReauthenticationRequired` on cold restart and `PilotAuthorizationGate` boots `Disabled`.

---

### REQ-PILOT-405: Separate Offline & Pilot Build Transition Proposal (`SEC-405` — Design & Proposal Only)
*(Workstream: `T4` — Verification Policy & Manifest Governance)*

Future pilot `INTERNET` enablement must isolate a distinct pilot artifact from the `INTERNET`-free offline APK through a separately authorized policy transition.

- **AC-405.1:** The specification defines the complete future `FCT-004B` transition proposal (`proposal.md` §5 & `design.md` §3): preserving `app/src/main/AndroidManifest.xml` (`INTERNET`-free) for the offline APK, isolating `INTERNET`-only permission into a separate pilot variant/artifact, unifying `apk_validator.py` around `--connectivity-policy {offline,pilot}` (with `offline` default), specifying exact build tasks/artifact paths/allowlists/negative tests, preserving backup/cleartext/signature/DEX/vendor-binary invariants, and defining candidate-specific `TRANSITION_AUTHORIZATION` activation and post-merge retirement.
- **AC-405.2:** Neither `FCT-004` nor `FCT-004A` modifies Gradle variants, `AndroidManifest.xml`, `policies/checks.json`, `verification/`, or `.github/workflows/`, and `TRANSITION_AUTHORIZATION` remains inactive.

---

### REQ-PILOT-406: Session Isolation, Bounded Auth/Inventory Budgets & Capability Verification (`SEC-404`)
*(Workstream: `T3`/`T4` — Session Ownership, Budgets & Device Identity)*

Pilot sessions must enforce `sessionEpoch` ownership, strict one-shot HTTP request budgets, 15-second call timeouts, and target Laser SLAM device verification.

- **AC-406.1:** `PilotSessionController` + `SessionCoordinator` enforce `sessionEpoch` isolation, `1` login attempt, `0` automatic password retries, `1` inventory request (`{"opt":"user_thing_list_get"}`), `retryOnConnectionFailure(false)`, and `15_000ms` HTTP total call timeout, discarding stale-epoch callbacks cleanly.
- **AC-406.2:** `DeviceCapabilityClassifier` classifies returned `DeviceInventoryItem.subType` against RE-grounded prefix sets, permitting read-only live telemetry ONLY for verified `MvpSupportedShadowWs` (`LaserSlam`) devices matching `PilotAuthorizationContext` target device identity.
- **AC-406.3:** Pilot UI (`PilotReadinessSheet`) displays clear offline/mock labeling, authorization summary, endpoint review, read-only device inventory (nickname, normalized `sub_type`, `isAdmin`, `ControlCompatibility`, redacted `thing_name`), selected robot status, connection state, and monotonic observation timer without exposing raw tokens.

---

### REQ-PILOT-407: Read-Only Realtime Telemetry, Cumulative Reconnect Budget & Monotonic Deadline (`SEC-402`, `SEC-404`)
*(Workstream: `T3`/`T4` — WebSocket Transport & Bounded Execution)*

The pilot WebSocket connection must receive read-only status telemetry under `(sessionEpoch, connectionGeneration)` fencing, cumulative session reconnect caps, and an absolute monotonic observation deadline.

- **AC-407.1:** `OkHttpWebSocketTransport` / pilot transport wrapper enforces `(sessionEpoch, connectionGeneration)` fencing, pre-connect/connected expiry checks, `1` initial WebSocket connection, active selected `thing_name` + `MvpSupportedShadowWs` ownership filtering, and read-only `thing_status_update` hydration (`battery_level` clamped `0..100`, `working_status`, `error_info`). If a future live server requires application-level subscription messages, execution MUST stop for a separately verified protocol contract.
- **AC-407.2:** Pilot session enforces at most `3` WebSocket reconnect attempts **cumulatively across the complete pilot session** (NEVER resetting the cumulative counter after successful reconnects), `>= 4_000ms` delay, immediate zero-retry short-circuit on `401`/`403`/`3xx` or auth close codes, and automatic Emergency Disconnect when the injected monotonic observation deadline (`<= 30` minutes) expires.

---

### REQ-PILOT-408: Structural Read-Only Boundary, Non-Blocking Emergency Disconnect & Sanitized Evidence (`SEC-402`, `SEC-404`, `SEC-406`)
*(Workstream: `T4` — Safety Kill Switch, Structural Command Prohibition & Sanitization)*

The pilot must structurally prohibit all outbound application commands, execute non-blocking Emergency Disconnect & Wipe, and export strictly allowlisted sanitized diagnostics.

- **AC-408.1:** Emergency Disconnect (`PilotSessionController.emergencyDisconnectAndWipe`) executes in non-blocking order: (1) revoke authorization & future network admission immediately (without waiting for disk I/O), (2) cancel active HTTP calls & pending network work, (3) cancel reconnect & expiry jobs, (4) close/cancel active WebSocket connections, (5) invalidate session ownership (`sessionEpoch`), (6) clear inventory & selected device state, (7) attempt durable `TokenStore.clear()`, (8) restore offline `SimulatorRobotTransport`, and (9) report incomplete storage cleanup honestly. Late callbacks MUST NOT reactivate a revoked pilot, and local disconnect does NOT claim server-side bearer token revocation.
- **AC-408.2:** Structural read-only network boundary: pilot HTTP gateways reject non-allowlisted `opt` payloads (`login` and `user_thing_list_get` only); pilot WebSocket boundary prohibits ALL outbound application-level text and binary messages in read-only pilot mode while preserving RFC 6455 protocol control frames; no unrestricted `sendOutbound` transport is exposed to pilot UI or controller; offline simulator commands remain functional and isolated; unexpected attempts to send physical robot commands (`Clean`, `Pause`, `Resume`, `Dock`, `send_to_device`) trigger immediate pilot-abort (`UnexpectedOutboundCommandAttempt`) and `CommandResult.Rejected`.
- **AC-408.3:** `PilotSanitizedExporter` combines allowlisted `DiagnosticLogger` events and `SecretRedactor` scrubbing (`jwt_token`, `"token"`/`"region"` headers, passwords, emails, device IDs) with zero raw request/response logging, and preserves deterministic rollback to frozen `FCT-003` APK (`weback-fct003-debug.apk`).
