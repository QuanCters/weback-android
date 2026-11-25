# FCT-004 Requirements Specification — Controlled Connectivity Pilot

- **Change ID:** `FCT-004`
- **Milestone:** `FCT-004`
- **Document ID:** `SPEC-REQ-FCT004`
- **Milestone Risk Ceiling:** `T4 (Critical Risk)`
- **Status:** `G0–G3 SPECIFICATION CANDIDATE (READ-ONLY PILOT SPEC; NO IMPLEMENTATION)`
- **Provenance & Governance:** [proposal.md](proposal.md) | [risk-assessment.md](risk-assessment.md) | [specs/constitution.md](../../constitution.md) | [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md)

---

## 1. Evidence Classification Tags

- **`[RE-OBSERVED]`:** Verified directly from primary decompiled Dalvik/JADX classes in `com.yugong.Backome` 6.1.7 (`REG-06`..`REG-10`).
- **`[UNRESOLVED]`:** Server-side or runtime-only behavior outside client APK observability; must be resolved via controlled pilot observation without guessing.
- **`[CLEAN-ROOM-DESIGN]`:** Newly specified clean-room pilot safety, trust, read-only isolation, and emergency abort mechanisms for `com.weback.robot`.

---

## 2. Unresolved Protocol & Auth Matrix (`UNK-AUTH-01` .. `UNK-INV-01`)

| Unknown ID | Unresolved Question | Observation Probe in FCT-004 Pilot | Required Pass / Abort Outcome |
| :--- | :--- | :--- | :--- |
| **`UNK-AUTH-01`** | Does Grit Cloud support a passwordless refresh `opt`, or does `IdentityInfo` omit `refresh_token` completely? | Inspect redacted login response keys in `OkHttpAuthGateway` (`jwt_token`, `expired_time`, absence/presence of refresh fields). | Keep `DisabledPasswordlessRefreshStrategy` active; never cache passwords or MD5; transition to `ReauthenticationRequired` on expiry. |
| **`UNK-AUTH-02`** | What exact HTTP status codes and `BaseResponse.code` values map to bad password, locked account, expired token, or region mismatch? | Observe HTTP status + integer `BaseResponse.code` + safe error category during sanitized pilot auth/inventory calls. | Map cleanly via `AuthFailureReason.ServerRejected(httpStatus, serverCode, safeCategory)`; never log raw server message bodies unredacted. |
| **`UNK-AUTH-03`** | How does the server enforce `expired_time` and JWT expiration across REST & WS calls? | Compare `expired_time` from `IdentityInfo` against `TokenExpiryPolicy` monotonic elapsed lifetime during read-only pilot window. | Enforce dual-clock `TokenExpiryPolicy` + pre-send/post-response checks; never parse or trust unverified JWT claims. |
| **`UNK-AUTH-04`** | Does `/prod/oauth` trigger `get_image_captcha` or `get_verify_code_captcha` during `login`? | Observe whether `login` returns a challenge indicator or succeeds cleanly on dedicated test account. | **Abort / Halt on Challenge:** Map to `AuthFailureReason.ChallengeRequired` and stop. Automated CAPTCHA solving/bypass is strictly prohibited. |
| **`UNK-AUTH-05`** | Does Grit Cloud `/prod/oauth` accept raw UTF-8 `pwd` over TLS (`Utf8TlsDefault`) or require legacy lowercase MD5 hex (`f.D(pwd)`)? | Execute single authorized sign-in using `Utf8TlsDefault` (`FCT-003` default). | If accepted: record confirmation. If rejected (`code != 1` / `401`): **STOP** pilot, record safe status/code, and escalate to Lead Maintainer before any `PasswordDigestStrategy` decision. |
| **`UNK-AUTH-06`** | What WebSocket close codes, revocation frames, and server ping/pong intervals does `wss_url` emit? | Observe `onOpen`, inbound frames, `onClosing(code)`, `onClosed(code)`, and `onFailure(httpCode)` during bounded read-only WS observation. | Keep `20_000ms` outbound send-stall config distinct from keepalive; abort reconnects immediately on `401`/`403`/`3xx` or auth close frame. |
| **`UNK-AUTH-07`** | Does Grit Cloud accept configured `protocolApiVersion` / `language`, and what exact `api_url` / `wss_url` origins are returned? | Inspect `UriValidator.NormalizedOrigin` of returned `api_url` and `wss_url` (`scheme`, `host`, `port`) in `PilotOriginCheck`. | Require `https://`/`wss://` `:443`, public IP, and exact pinned or human-confirmed origin; never infer wildcard host lists prior to observation. |
| **`UNK-AUTH-08`** | Does Android 12+ (`minSdk = 31`) system CA store trust `user.grit-cloud.*`, `api_url`, and `wss_url` without custom `AmazonRootCA1.cer`? | Observe standard `OkHttpClient` platform TLS 1.2/1.3 handshake (`SSLSocketFactory` default). | If handshake succeeds: confirm standard system CAs suffice. If `SSLHandshakeException`: **ABORT** pilot; do not add trust-all or global SSL mutations. |
| **`UNK-INV-01`** | What exact `sub_type` and `thing_status` fields does the PO's physical Laser SLAM robot report live? | Inspect `user_thing_list_get` response and read-only `thing_status_update` frames via `DeviceCapabilityClassifier`. | Verify `LaserSlam` (`MvpSupportedShadowWs`) classification and safe `0..100` telemetry hydration; ignore unknown keys safely. |

---

## 3. Traceable Requirements Catalog

### REQ-PILOT-401: Explicit External Connectivity Authorization & Default Offline Isolation
*(Workstream: `T4` — Governance & Pilot Gate)*

Live cloud connectivity must remain strictly disabled by default and gated behind explicit external authorization.

- **AC-401.1:** `PilotAuthorizationGate` defaults to `Disabled` (`OfflineSimulator`) at application startup and in all automated unit/Robolectric/CI test suites, preserving `SimulatorRobotTransport` and the `"OFFLINE"` badge until an operator explicitly enters an authorized read-only pilot session.
- **AC-401.2:** Every authorized pilot session requires an explicit `PilotAuthorizationContext` capturing non-empty `authorizationId`, `readOnlyMode = true`, bounded `observationWindowMs`, and exact endpoint policy configuration before any network call is permitted.

---

### REQ-PILOT-402: Exact Trusted Endpoint Policy & Standard Platform TLS Verification
*(Workstream: `T4` — Endpoint Trust & Network Security)*

Authorized pilot connections must enforce exact origin validation, public IP verification, redirect isolation, and standard Android TLS verification without inferring unverified hosts.

- **AC-402.1:** `AuthorizedPilotEndpointTrustPolicy` authorizes auth requests ONLY to the exact RE-grounded CN/Global origins (`https://user.grit-cloud.cn:443` / `https://user.grit-cloud.com:443`, path `/prod/oauth` `[RE-OBSERVED: function/b.java:28,31]`) and authorizes dynamic `api_url` / `wss_url` ONLY against exact `(scheme, host, port)` origins (`https`/`wss`, `:443`) that are either pinned in `PilotAuthorizationContext` or explicitly confirmed by the human operator pre-flight (zero wildcard/suffix allowlists, zero cleartext `http://`/`ws://`).
- **AC-402.2:** `AuthorizedPilotEndpointTrustPolicy.validateResolvedAddresses(...)` MUST verify resolved `InetAddress` lists are non-empty and reject loopback (`127.0.0.0/8`, `::1`), link-local/metadata (`169.254.0.0/16`, `fe80::/10`), private/site-local RFC1918 (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `fc00::/7`), multicast, and unspecified addresses prior to socket connection (`Proxy.NO_PROXY`, `followRedirects(false)`, `followSslRedirects(false)`).
- **AC-402.3:** Pilot `OkHttpClient` instances MUST use standard Android system TLS 1.2/1.3 `TrustManager` and `HostnameVerifier` (`network_security_config.xml` `cleartextTrafficPermitted="false"`), failing closed on any `SSLHandshakeException` without global `SSLContext.setDefault` / `setDefaultSSLSocketFactory` overrides or trust-all verifiers.

---

### REQ-PILOT-403: Protocol Unknown Resolution Probes & Non-Bypass Fail-Closed Guards
*(Workstream: `T4` — Protocol Safety & Integrity)*

Outstanding authentication protocol unknowns (`UNK-AUTH-01`..`08`) must be observed deterministically with strict fail-closed handling and zero security downgrades.

- **AC-403.1:** `OkHttpAuthGateway` in pilot mode records sanitized protocol metadata (`httpStatus`, `BaseResponse.code`, `expired_time` bounds, `api_url`/`wss_url` origin scheme/host/port, TLS handshake outcome) via `DiagnosticLogger` while aborting immediately on `AuthFailureReason.ChallengeRequired` (`UNK-AUTH-04`) with zero CAPTCHA automation.
- **AC-403.2:** Pilot authentication uses `Utf8TlsDefault` (`PasswordDigestStrategy`) exclusively (`UNK-AUTH-05`); if the live server rejects UTF-8 password encoding, the pilot fails closed to `ProtocolMismatchAbort` and records safe status/code diagnostics without silently switching to legacy unsalted MD5.

---

### REQ-PILOT-404: Secure Ephemeral Credential Entry & Handling
*(Workstream: `T4` — Credential Privacy & Keystore)*

User-entered pilot test credentials must remain ephemeral, wiped from mutable buffers after login serialization, and never persisted to disk.

- **AC-404.1:** The opt-in pilot sign-in UI collects `regionCallingCode`, `account`, and password using a mutable `CharArray` buffer with `PasswordVisualTransformation`, `KeyboardType.Password`, `autoCorrectEnabled = false`, and no plaintext password retention in `ViewModel` state or saved-state handles, wiping the buffer in `finally` immediately after request serialization.
- **AC-404.2:** `KeystoreAeadTokenStore` (`AndroidKeyStore` AES-256-GCM) persists ONLY `PersistedSessionRecord` (never input passwords or MD5 digests), while `SessionCoordinator` preserves conservative `ReauthenticationRequired` behavior on cold process restart.

---

### REQ-PILOT-405: Governed `INTERNET` Permission & APK Policy Transition Proposal
*(Workstream: `T4` — Verification Policy & Manifest Governance)*

Enabling `android.permission.INTERNET` for pilot execution must occur solely through a separately authorized, auditable `T4` policy transition that preserves all baseline security invariants.

- **AC-405.1:** The specification defines the exact future `T4` transition delta for `AndroidManifest.xml` (`android.permission.INTERNET` only) and `verification/checks/apk_validator.py` (mode-scoped permission allowlist keeping location, Bluetooth, Wi-Fi, and legacy permissions prohibited, and retaining `usesCleartextTraffic="false"`, `allowBackup="false"`, `networkSecurityConfig`, and DEX/binary bans non-waivable).
- **AC-405.2:** Until that `T4` policy transition PR is explicitly authorized and active via `TRANSITION_AUTHORIZATION`, `AndroidManifest.xml` and `policies/checks.json` MUST remain unmodified and `INTERNET`-free across the specification and automated offline verification baselines.

---

### REQ-PILOT-406: Session Isolation, Read-Only Device Inventory & Capability Verification
*(Workstream: `T3`/`T4` — Session Ownership & Device Identity)*

Pilot sessions must enforce `sessionEpoch` ownership, execute read-only inventory retrieval, and verify target device compatibility before enabling telemetry display.

- **AC-406.1:** `SessionCoordinator` and `DeviceInventoryRepository` enforce `sessionEpoch` isolation across pilot sign-in, inventory fetch (`POST` `{"opt":"user_thing_list_get"}`), account replacement, and logout, discarding stale-epoch HTTP/WS callbacks cleanly.
- **AC-406.2:** `DeviceCapabilityClassifier` evaluates each returned `DeviceInventoryItem.subType` against RE-grounded prefix sets (`P0`, `q1`, `l1`/`L0`, `m1`, `N0`, `Y0`, `v0`, `g1`, `n1`, `H0`/`U0`), permitting read-only live telemetry subscription ONLY for verified `MvpSupportedShadowWs` (`LaserSlam`) devices and isolating unsupported/unknown devices cleanly.
- **AC-406.3:** Pilot UI surfaces read-only device identity verification metadata (nickname, normalized `sub_type` family, `isAdmin` boolean, `ControlCompatibility`, redacted `thing_name` summary) without exposing raw tokens or triggering device commands.

---

### REQ-PILOT-407: Read-Only Realtime Telemetry & Bounded Connection/Retry Behavior
*(Workstream: `T3` — WebSocket Transport & Resilience)*

The live WebSocket pilot must receive and hydrate read-only status telemetry under strict generation fencing and bounded retry budgets.

- **AC-407.1:** `OkHttpWebSocketTransport` enforces `(sessionEpoch, connectionGeneration)` fencing, pre-connect/connected session-expiry enforcement, active selected `thing_name` + `MvpSupportedShadowWs` ownership filtering, and read-only `thing_status_update` hydration (`battery_level` clamped `0..100`, `working_status`, `error_info`).
- **AC-407.2:** Pilot `ConnectionConfig` enforces bounded retry behavior (`maxReconnectAttempts <= 3`, base delay `>= 4_000ms`), immediate zero-retry short-circuit on HTTP `401`/`403`/`3xx` or auth close codes, and automatic disconnect when the authorized `observationWindowMs` expires.

---

### REQ-PILOT-408: Emergency Disconnect, Rollback, Sanitized Evidence & Command Dispatch Prohibition
*(Workstream: `T4` — Safety Kill Switch, Sanitization & Physical Robot Guard)*

The pilot must provide an immediate kill switch, prohibit all live physical robot actuation, export strictly sanitized evidence, and document rollback to `FCT-003`.

- **AC-408.1:** A one-tap **Emergency Disconnect & Wipe** action (`PilotKillSwitch`) immediately increments `sessionEpoch`, closes `OkHttpWebSocketTransport` (`1000`), clears `DeviceInventoryRepository`, executes `TokenStore.clear()`, revokes `PilotAuthorizationGate`, and restores offline `SimulatorRobotTransport` (`OFFLINE` mode).
- **AC-408.2:** `ReadOnlyPilotGuard` structurally blocks all outbound robot control commands (`Clean`, `Pause`, `Resume`, `Dock`, `send_to_device`) on live cloud transports during `FCT-004` (`CommandResult.Rejected`), requiring a separate future authorization milestone before any physical robot actuation is enabled.
- **AC-408.3:** Pilot diagnostic export passes every event, exception, and origin summary through `DiagnosticLogger` + `SecretRedactor` (scrubbing `jwt_token`, `"token"`/`"region"` headers, passwords, email aliases, and device identifiers) before operator review, and preserves deterministic rollback to frozen `FCT-003` APK (`weback-fct003-debug.apk`).
