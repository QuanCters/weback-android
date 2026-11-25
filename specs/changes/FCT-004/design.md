# FCT-004 Technical Design — Controlled Connectivity Pilot

- **Change ID:** `FCT-004`
- **Risk Classification:** `T4 (Critical Risk)`
- **References:** [proposal.md](proposal.md) | [risk-assessment.md](risk-assessment.md) | [requirements.md](requirements.md) | [validation.md](validation.md) | [specs/tech-stack.md](../../tech-stack.md) | [specs/changes/FCT-003/design.md](../FCT-003/design.md)

---

## 1. Architectural Extension Over FCT-003

`FCT-004` / `FCT-004A` preserves 100% of `FCT-003` pure-Kotlin domain isolation (`C-002`), `SessionCoordinator` commit/epoch serialization, `KeystoreAeadTokenStore` (`ADR-003`), and `SimulatorRobotTransport` default offline boot behavior, adding a **strictly opt-in, capability-restricted, read-only pilot layer**:

```text
app/src/main/java/com/weback/robot/
├── domain/pilot/                    [PURE KOTLIN]
│   ├── PilotModels.kt               (PilotAuthorizationContext, PilotRequestBudgets, PilotModeState,
│   │                                 PilotAbortReason, PilotEmergencyDisconnectResult, ReadOnlyTelemetrySnapshot)
│   ├── PilotAuthorizationGate.kt    (Default-disabled gate, immutable context validation, monotonic deadline)
│   └── ReadOnlyPilotGuard.kt        (Structural command guard; live command attempt -> Reject + PilotAbort)
├── security/
│   ├── AuthorizedPilotEndpointTrustPolicy.kt (Exact auth + /prod/oauth, pinned/provenance-verified dynamic origins,
│   │                                          explicit IPv4/IPv6 CIDR + socket destination validation, 15s callTimeout)
│   └── PilotSanitizedExporter.kt    (Allowlisted DiagnosticLogger + SecretRedactor evidence export)
├── protocol/pilot/
│   └── PilotReadOnlyTransportBoundary.kt (Capability-restricted HTTP op validator + ReadOnlyWebSocketTransport
│                                          blocking all outbound app text/binary frames while preserving WS control frames)
├── app/pilot/
│   └── PilotSessionController.kt    (Bounded budgets, cumulative WS reconnect cap, active HTTP Call cancellation,
│                                     quarantined dynamic origin gate, 9-step non-blocking Emergency Disconnect)
└── ui/pilot/
    └── PilotReadinessSheet.kt       (Opt-in Compose UI: Auth summary, FLAG_SECURE synthetic CharArray entry,
                                      endpoint review, read-only inventory, selected status, timer, Kill Switch)
```

---

## 2. Detailed Component Contracts

### 2.1 `PilotModels`, `PilotAuthorizationGate` & `ReadOnlyPilotGuard` (`SEC-401`, `SEC-402`)
1. **Default State & Zero Restart Restoration:** `PilotAuthorizationGate` starts in `PilotModeState.OfflineDefault` (`SimulatorRobotTransport` active, `"OFFLINE"` badge visible, zero network I/O). Context is held strictly in volatile memory and never serialized to `TokenStore` or `SavedStateHandle`.
2. **Immutable `PilotAuthorizationContext` Validation:** `PilotAuthorizationGate.activate(context, expectedRuntimeIdentity, nowElapsedMs)` rejects fail-closed if:
   - `authorizationId` is blank or external human approval metadata (`approvedByProductOwner`, `approvedByLeadMaintainer`) is blank;
   - `readOnlyMode != true` or `physicalRobotCommandsPermitted != false` or `permittedOperations` contains anything outside `{"login", "user_thing_list_get", "ws_telemetry_observe"}`;
   - `candidateCommitSha`, `expectedApplicationId`, `expectedVersionName`, or `expectedApkSha256` is blank or mismatches runtime `ExpectedCandidateIdentity`;
   - `accountScopeType != DEDICATED_ISOLATED_TEST_ACCOUNT`, `authorizedRegionCallingCode` fails calling-code format, or `authorizedAccountAlias` is blank;
   - `expectedSubTypeFamily != "LaserSlam"` or `expectedTargetThingName` is blank;
   - `approvedAuthOrigin` is not `https://user.grit-cloud.cn:443` or `https://user.grit-cloud.com:443` (path `/prod/oauth`), or `pinnedApiOrigin`/`pinnedWssOrigin` (when provided) violate `https`/`wss` `:443`;
   - Budgets exceed `SEC-404` caps (`maxLoginAttempts > 1`, `maxPasswordRetries > 0`, `maxCaptchaAutomation > 0`, `maxInventoryRequests > 1`, `maxInitialWsConnections > 1`, `maxWsReconnectAttempts > 3`, `httpCallTimeoutMs !in 1..15_000`, `observationWindowMs !in 1..1_800_000`, `maxPhysicalRobotCommands > 0`);
   - `nowElapsedMs >= observationDeadlineElapsedMs` or deadline exceeds `authorizedAtElapsedMs + observationWindowMs`.
3. **Structural Read-Only Boundary (`SEC-402`):**
   - `PilotHttpOperationGuard` enforces an allowlist of `{"login", "user_thing_list_get"}` on request `opt` payloads before any pilot HTTP request is built/dispatched; any other `opt` (including `"send_to_device"` or malformed JSON) is rejected before network I/O.
   - `PilotReadOnlyWebSocketTransport` wraps `RealtimeSessionTransport` / `OkHttpWebSocketTransport` without exposing `sendOutbound(payload, epoch)` or any `ByteString` send API to `PilotSessionController` or `PilotReadinessSheet`. Calling `sendOutbound` (or `sendApplicationBinary`) directly on the pilot boundary returns `false`, transmits zero frames, and invokes `onUnexpectedCommandAttempt` -> `PilotSessionController.abortPilot(PilotAbortReason.UnexpectedOutboundCommandAttempt)`. OkHttp continues handling RFC 6455 Ping/Pong/Close protocol control frames natively at the framing layer.
   - `ReadOnlyPilotGuard` wraps command dispatch when pilot mode is active, returning `CommandResult.Rejected(TRANSPORT_REJECTED, ...)` AND triggering pilot abort on any unexpected live physical robot command attempt, while leaving `SimulatorRobotTransport` completely isolated and functional in offline mode.

### 2.2 `AuthorizedPilotEndpointTrustPolicy` & Quarantined Origin Resolution (`SEC-403`)
1. **Exact Auth Origin + Path:** `authorizeAuthRequest(url)` requires `https`, host `user.grit-cloud.cn` or `user.grit-cloud.com` (or injected loopback test override in hermetic test instances), explicit/default port `443`, exact path `/prod/oauth`, zero userinfo, and zero credential query/fragment parameters.
2. **Quarantined Dynamic Origin Handling (`UNK-AUTH-07`):**
   - **Resolving `FCT-003` vs `FCT-004`:** `OkHttpAuthGateway` retains immediate rejection (`AuthFailureReason.UntrustedEndpoint`) whenever `EndpointTrustPolicy.authorize(apiUrl/wssUrl)` returns `Untrusted`, guaranteeing `SessionCoordinator` never enters `SessionState.Authenticated` and `TokenStore` never saves unapproved sessions.
   - When `PilotAuthorizationContext` has unpinned `api_url` / `wss_url`, a dedicated pilot quarantine evaluator validates syntax (`https`/`wss`, `:443`, no userinfo/query secrets, non-special-use host/IP), extracts ONLY normalized `(scheme, host, port)` origins, discards/quarantines the unapproved response without publishing `SessionState.Authenticated`, persisting tokens, or forwarding `"token"`/`"region"`, and transitions `PilotModeState` to `AwaitingOriginConfirmation(observedApiOrigin, observedWssOrigin, provenanceVerified = false)`.
   - Acceptance of an observed dynamic origin requires BOTH (a) verified endpoint provenance (`OriginProvenanceVerifier`: cryptographic/signed manifest or approved origin provenance record, NEVER human UI confirmation alone) AND (b) an updated explicit `PilotAuthorizationContext` pinning those exact origins. Clicking a UI confirmation alone transitions only if provenance + updated authorization are satisfied; otherwise the pilot remains quarantined or aborts.
3. **Explicit IPv4 & IPv6 Special-Use Address Isolation (`IpAddressPolicy`):**
   - Inspects raw 4-byte IPv4 and 16-byte IPv6 addresses directly (not relying solely on `InetAddress` convenience predicates):
   - Rejects IPv4 `0.0.0.0/8`, `10.0.0.0/8`, `100.64.0.0/10` (CGNAT), `127.0.0.0/8`, `169.254.0.0/16` (link-local/metadata), `172.16.0.0/12`, `192.0.0.0/24`, `192.0.2.0/24`, `192.88.99.0/24`, `192.168.0.0/16`, `198.18.0.0/15`, `198.51.100.0/24`, `203.0.113.0/24`, `224.0.0.0/4`, and `240.0.0.0/4`.
   - Rejects IPv6 `::/128`, `::1/128`, `::ffff:0:0/96` (extracting trailing 4 bytes and applying all IPv4 special-use checks), `64:ff9b::/96`, `64:ff9b:1::/48`, `100::/64`, `2001::/32`, `2001:2::/48`, `2001:db8::/32`, `2002::/16`, `fc00::/7` (ULA), `fe80::/10`, `fec0::/10`, and `ff00::/8`.
   - Validates ALL resolved addresses in `Dns` AND verifies the actual connected socket destination IP via OkHttp `EventListener.connectStart` / `connectionAcquired` before HTTP/WS traffic proceeds.
4. **Hardened Pilot `OkHttpClient`:** Platform default `TrustManager` + `HostnameVerifier`, `.followRedirects(false)`, `.followSslRedirects(false)`, `.retryOnConnectionFailure(false)`, `.proxy(Proxy.NO_PROXY)`, `.callTimeout(15_000L, TimeUnit.MILLISECONDS)`.

### 2.3 `PilotSessionController`, Cumulative Budgets & Non-Blocking Emergency Disconnect (`SEC-404`)
1. **Cumulative Budget Tracker:** Tracks `loginAttemptsUsed` (cap `1`), `inventoryRequestsUsed` (cap `1`), `initialWsConnectionsUsed` (cap `1`), and `cumulativeWsReconnectsUsed` (cap `3` across the entire pilot session; **never** reset on `ConnectionState.Connected`), plus monotonic observation deadline job (`<= 1_800_000ms`).
2. **9-Step Non-Blocking `emergencyDisconnectAndWipe(reason)`:**
   1. Immediately lock `PilotAuthorizationGate`, increment `pilotEpoch`, and deny all future network admission under short `commitLock` (never awaiting disk I/O).
   2. Cancel all tracked active HTTP `Call` instances and pending network coroutines.
   3. Cancel reconnect and monotonic observation deadline jobs.
   4. Close/cancel active `PilotReadOnlyWebSocketTransport` (`1000` + `cancel`).
   5. Invalidate `SessionCoordinator` ownership (`signOut()` / `invalidateForEpoch()`).
   6. Wipe `DeviceInventoryRepository` and selected device state (via epoch advance).
   7. Execute `NonCancellable` `TokenStore.clear()` without blocking steps 1–4.
   8. Restore `PilotModeState.OfflineDefault` (`SimulatorRobotTransport` active).
   9. Return `PilotEmergencyDisconnectResult(networkRevokedImmediately = true, storageCleared = ..., incompleteStorageReason = ...)` so partial storage cleanup is reported honestly.
   - Any late callback arriving with `callbackPilotEpoch != currentPilotEpoch` is dropped cleanly and cannot reactivate a revoked pilot.

### 2.4 `PilotReadinessSheet` & `PilotSanitizedExporter` (`SEC-406`)
- **`PilotReadinessSheet`:** Opt-in sheet/panel on `DashboardScreen` preserving FCT-002 offline controls intact. Clearly displays `"OFFLINE / SIMULATOR"` vs `"MOCK / BOUNDED PILOT"` labeling, `PilotAuthorizationContext` summary, `FLAG_SECURE`-protected synthetic credential entry (`CharArray` wiped on submit, cancel, error, and `DisposableEffect.onDispose`), quarantined origin review (reject/provenance status), read-only device inventory, selected `LaserSlam` status, connection state, monotonic observation countdown, and prominent **Emergency Disconnect & Wipe** button.
- **`PilotSanitizedExporter`:** Exports allowlisted `DiagnosticEvent` records, probe metadata, and emergency disconnect status scrubbed through `SecretRedactor` with zero raw requests, response bodies, tokens, or passwords.

---

## 3. Future `FCT-004B` Build & Policy Transition Design (`SEC-405` — Proposal Only)

1. **`app/build.gradle.kts` (FCT-004B only):** Define an explicit `pilot` `buildType` (inheriting debug signing, `matchingFallbacks = listOf("debug")`) or flavor separation keeping `assembleDebug` (`app/build/outputs/apk/debug/app-debug.apk`) strictly `INTERNET`-free while producing `app/build/outputs/apk/pilot/app-pilot.apk` for authorized pilot testing.
2. **`app/src/pilot/AndroidManifest.xml` (FCT-004B only):** Declares strictly `<uses-permission android:name="android.permission.INTERNET" />` while inheriting `usesCleartextTraffic="false"`, `allowBackup="false"`, and `networkSecurityConfig` from `src/main/AndroidManifest.xml`.
3. **`verification/checks/apk_validator.py` (FCT-004B only):** Add `--connectivity-policy` (`choices=["offline", "pilot"]`, `default="offline"`). `offline` forbids `INTERNET`; `pilot` permits ONLY `INTERNET` + `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` while keeping all location/BT/Wi-Fi permissions, cleartext, backup, signature, DEX, and vendor-binary checks strictly enforced.
4. **`policies/checks.json` & `TRANSITION_AUTHORIZATION`:** Activated solely via candidate-specific `TRANSITION_AUTHORIZATION` + dual human sign-off (`PO` + `Lead Maintainer`), followed by post-merge `APPROVED_POLICY_SHA256` rotation and retirement of `TRANSITION_AUTHORIZATION`.
