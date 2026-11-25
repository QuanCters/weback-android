# FCT-004 Technical Design — Controlled Connectivity Pilot

- **Change ID:** `FCT-004`
- **Risk Classification:** `T4 (Critical Risk)`
- **References:** [proposal.md](proposal.md) | [risk-assessment.md](risk-assessment.md) | [requirements.md](requirements.md) | [validation.md](validation.md) | [specs/tech-stack.md](../../tech-stack.md) | [specs/changes/FCT-003/design.md](../FCT-003/design.md)

---

## 1. Architectural Extension Over FCT-003

`FCT-004` preserves 100% of `FCT-003` pure-Kotlin domain isolation (`C-002`), `SessionCoordinator` commit/epoch serialization, `KeystoreAeadTokenStore` (`ADR-003`), and `SimulatorRobotTransport` default boot behavior, adding a **strictly opt-in, read-only pilot layer**:

```text
app/src/main/java/com/weback/robot/
├── domain/pilot/                    [PURE KOTLIN]
│   ├── PilotModels.kt               (PilotAuthorizationContext, PilotModeState, ReadOnlyTelemetrySnapshot, PilotAbortReason)
│   └── ReadOnlyPilotGuard.kt        (Blocks outbound send_to_device commands while readOnlyMode == true)
├── security/
│   ├── AuthorizedPilotEndpointTrustPolicy.kt (Exact CN/Global auth origins + pinned/human-confirmed api_url/wss_url; public IP check)
│   └── PilotSanitizedExporter.kt    (Combines DiagnosticLogger ring buffer + SecretRedactor for safe pilot evidence export)
├── app/pilot/
│   └── PilotSessionController.kt    (Coordinates PilotAuthorizationGate, Emergency Disconnect & Wipe, observation window timer)
└── ui/pilot/
    └── PilotReadinessSheet.kt       (Opt-in Compose UI: Authorization summary, ephemeral CharArray sign-in, read-only device/telemetry card, Kill Switch)
```

---

## 2. Detailed Component Contracts

### 2.1 `PilotAuthorizationGate` & `AuthorizedPilotEndpointTrustPolicy` (`REQ-PILOT-401`, `REQ-PILOT-402`)
1. **Default State:** App boots with `PilotModeState.OfflineDefault` (`SimulatorRobotTransport` active, `"OFFLINE"` badge visible, zero network I/O).
2. **Exact Auth Origins:** `RegionEndpointRouter` resolves `https://user.grit-cloud.cn/prod/oauth` (`+86`/`0086`) or `https://user.grit-cloud.com/prod/oauth` (other valid calling codes). `AuthorizedPilotEndpointTrustPolicy` accepts **only** those exact `(scheme = "https", host, port = 443)` origins for login.
3. **Dynamic `api_url` / `wss_url` Handling (`UNK-AUTH-07`):**
   - Step A: `UriValidator.validateEndpoint(url, allowLoopbackCleartext = false)` rejects non-`https`/`wss`, non-`443` explicit ports, userinfo, and credential query params.
   - Step B: If `PilotAuthorizationContext.pinnedApiOrigin` / `pinnedWssOrigin` are provided in the signed Pilot Authorization Form, `AuthorizedPilotEndpointTrustPolicy` requires exact `(scheme, host, port)` equality.
   - Step C: If unpinned prior to first pilot observation, `OkHttpAuthGateway` extracts ONLY the normalized origin `(scheme, host, port)` (zero token forwarding) and pauses in `PilotModeState.AwaitingOriginConfirmation` until the human operator confirms or rejects the exact origin in UI before any inventory/WS call is dispatched.
4. **Public Unicast IP Enforcement:** Custom OkHttp `Dns` validates every resolved `InetAddress` is NOT `isLoopbackAddress`, `isSiteLocalAddress` (RFC1918), `isLinkLocalAddress` (`169.254.0.0/16` metadata), `isAnyLocalAddress`, or `isMulticastAddress`, failing closed with `UnknownHostException` before TCP/TLS socket open.
5. **TLS Hygiene (`UNK-AUTH-08`):** Standard Android platform `TrustManager` + `HostnameVerifier` (`TLS 1.2/1.3`), `.followRedirects(false)`, `.followSslRedirects(false)`, `.proxy(Proxy.NO_PROXY)`.

### 2.2 Ephemeral Credential Entry & Unknown-Resolution Probes (`REQ-PILOT-403`, `REQ-PILOT-404`)
- `PilotReadinessSheet` collects `regionCallingCode`, `account`, and `password` (`CharArray`), configures `KeyboardType.Password` and `autoCorrectEnabled = false`, and passes `AuthCredentials` ephemerally into `SessionCoordinator.signIn()`. `AuthCredentials.wipeSecret()` executes in `finally`.
- `OkHttpAuthGateway` records a sanitized `PilotProbeOutcome`: HTTP status, `BaseResponse.code`, `expired_time`, TLS protocol/cipher suite, and observed `api_url`/`wss_url` origins (never raw headers/tokens/passwords).
- Any `ChallengeRequired` (`UNK-AUTH-04`) or `Utf8TlsDefault` rejection (`UNK-AUTH-05`) aborts the pilot cleanly without CAPTCHA automation or silent MD5 downgrade.

### 2.3 Read-Only Inventory, Capability Gate & Telemetry (`REQ-PILOT-406`, `REQ-PILOT-407`, `REQ-PILOT-408`)
- `DeviceInventoryRepository.refreshInventory()` fetches `user_thing_list_get`, classifies every item via `DeviceCapabilityClassifier`, and presents the read-only list (`nickname`, normalized `subType`, `category`, `compatibility`, `isAdmin`).
- Only items with `ControlCompatibility.MvpSupportedShadowWs` (`LaserSlam`) may be selected for read-only `OkHttpWebSocketTransport.connect(session, epoch)`.
- `ReadOnlyPilotGuard` wraps `RobotTransport` / UI command dispatch while pilot mode is active: any `Clean`, `Pause`, `Resume`, or `Dock` command targeting live cloud returns `CommandResult.Rejected(TRANSPORT_REJECTED, "Read-only FCT-004 pilot blocks physical robot commands")`.
- `ConnectionConfig` sets `maxReconnectAttempts = 3`, `reconnectDelayMs = 4_000L`, and `PilotSessionController` schedules an observation-window deadline timer triggering automatic `emergencyDisconnectAndWipe()`.

### 2.4 Emergency Disconnect & Wipe (`REQ-PILOT-408`)
Invoking `PilotSessionController.emergencyDisconnectAndWipe(reason)`:
1. Cancels pilot observation timer and locks `PilotAuthorizationGate` back to `Disabled`.
2. Calls `SessionCoordinator.signOut()` (increments `sessionEpoch`, closes `OkHttpWebSocketTransport(1000)`, wipes `DeviceInventoryRepository`, executes `NonCancellable` `TokenStore.clear()`).
3. Switches active UI surface back to offline `SimulatorRobotTransport` (`OFFLINE` badge) and exports a redacted `PilotSanitizedReport`.

---

## 3. Future `T4` Policy & Manifest Transition Delta (Not Activated in Spec PR)

When implementation PR `FCT-004` is separately authorized for live pilot builds:
1. **`app/src/main/AndroidManifest.xml`:** Add `<uses-permission android:name="android.permission.INTERNET" />` only under the approved `T4` transition PR (keeping `android:usesCleartextTraffic="false"` and `android:allowBackup="false"`).
2. **`verification/checks/apk_validator.py`:** Add explicit `--allow-internet-pilot` policy mode (controlled solely by `policies/checks.json` transition) while continuing to reject all other permissions (`ACCESS_FINE_LOCATION`, `BLUETOOTH*`, `ACCESS_WIFI_STATE`, etc.) and all prohibited vendor binaries/DEX packages.
3. **`policies/checks.json`:** Update via `TRANSITION_AUTHORIZATION` (`verification/checks/transition.py`) with dual Product Owner + Lead Maintainer approval.
