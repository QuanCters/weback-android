# FCT-004 Validation Plan & Independent Test Oracles

- **Change ID:** `FCT-004`
- **Risk Ceiling:** `T4 (Critical Risk)`
- **References:** [proposal.md](proposal.md) | [requirements.md](requirements.md) | [design.md](design.md) | [risk-assessment.md](risk-assessment.md) | [tasks.md](tasks.md) | [traceability.json](traceability.json)

---

## 1. Verification Tiers (Automated Offline vs Authorized Human-Operated Pilot)

| Tier | Scope & Mechanism | Active Policy Mapping | Closure Effect |
| :--- | :--- | :--- | :--- |
| **1. G3 Spec Baseline** | All `8` requirements, `19` ACs, and `14` test scenarios mapped as `PLANNED` in `traceability.json`. | `spec-hygiene` | Mandatory for Gate G3 (`Implementation Readiness`) exit. |
| **2. Automated Offline / Loopback Verification** | Pure Kotlin `ReadOnlyPilotGuard`, `AuthorizedPilotEndpointTrustPolicy` negative IP/origin/TLS tests, `MockWebServer` pilot probes, ephemeral `CharArray` UI tests, Kill Switch & `SecretRedactor` export tests. **Zero live cloud calls.** | `android-unit-tests`, `android-lint`, `android-build`, `apk-validation` | Mandatory `PASS` for any post-G3 implementation candidate. |
| **3. Governed `T4` Policy Transition Verification** | Isolated verification of `AndroidManifest.xml` (`INTERNET` only) and `apk_validator.py` pilot-mode transition under `TRANSITION_AUTHORIZATION`. | `apk-validation`, `harness-selftests` | Mandatory when `T4` pilot manifest PR is authorized. |
| **4. Authorized Physical-Device Live Pilot (`TEST-PILOT-414`)** | Human-supervised live read-only observation on authorized physical Android >= 12 device with dedicated test account & isolated test robot (`login` -> `user_thing_list_get` -> `LaserSlam` capability check -> read-only `thing_status_update` WS -> Emergency Disconnect & Wipe). | `android-smoke-test` (external human-gated; `NOT_RUN` in offline CI) | Mandatory for `FCT-004` pilot closure (`G7`/`G8`); never automated in CI. |

Per `C-004`, automated tests assert against independent literal origins, synthetic IP addresses, `MockWebServer` recorded requests, and captured redacted outputs — never invoking production helpers to compute expected values.

---

## 2. Independent Test Oracles Catalog (`TEST-PILOT-401` .. `TEST-PILOT-414`)

| Oracle ID | Target ACs | Verification Tier & Execution Mechanism | Independent Oracle & Expected Verification | Policy Check ID |
| :--- | :--- | :--- | :--- | :--- |
| `TEST-PILOT-401` | `AC-401.1`, `AC-401.2` | Offline Unit (`PilotAuthorizationGateTest`) | Asserts `DefaultAppContainer` boots in `OfflineDefault` (`OFFLINE` badge intact), rejects pilot execution without valid `PilotAuthorizationContext`, and executes zero network I/O by default. | `android-unit-tests` |
| `TEST-PILOT-402` | `AC-401.1` | Offline Integration (`PilotDefaultIsolationTest`) | Verifies all automated unit/Robolectric suites run with `ProductionEndpointTrustPolicy` disabled and zero live `grit-cloud` DNS lookups. | `android-unit-tests` |
| `TEST-PILOT-403` | `AC-402.1`, `AC-402.2` | Offline Security Unit (`AuthorizedPilotEndpointTrustPolicyTest`) | Asserts CN/Global exact `https://user.grit-cloud.(cn|com):443` acceptance, unpinned dynamic origin pause (`AwaitingOriginConfirmation`), rejection of wildcards/`http://`/`ws://`/non-443 ports/redirects, and rejection of `127.0.0.1`, `10.0.0.1`, `172.16.0.1`, `192.168.1.1`, `169.254.169.254`, `::1`, and `fe80::1`. | `android-unit-tests` |
| `TEST-PILOT-404` | `AC-402.3` | Offline Security / TLS Scan (`PilotTlsHygieneTest`) | Verifies pilot `OkHttpClient` retains default platform `TrustManager` + `HostnameVerifier` (`followRedirects=false`, `Proxy.NO_PROXY`), fails closed on handshake mismatch, and contains zero trust-all/global SSL factory mutations. | `android-unit-tests` |
| `TEST-PILOT-405` | `AC-403.1`, `AC-403.2` | Offline `MockWebServer` (`PilotProtocolProbeTest`) | Injects CAPTCHA challenge response and password-rejection response into `MockWebServer`: asserts fail-closed `ChallengeRequired` (zero CAPTCHA automation) and `ProtocolMismatchAbort` under `Utf8TlsDefault` (zero silent MD5 downgrade). | `android-unit-tests` |
| `TEST-PILOT-406` | `AC-404.1`, `AC-404.2` | Offline Compose UI + Security (`PilotSignInSheetTest`) | Enters credentials in `PilotReadinessSheet`: verifies `CharArray` wiped (`'\u0000'`) in `finally`, `ViewModel` state holds zero plaintext password, `KeystoreAeadTokenStore` stores zero password/MD5, and restart remains `ReauthenticationRequired`. | `android-unit-tests` |
| `TEST-PILOT-407` | `AC-405.1`, `AC-405.2` | Policy & Spec Hygiene (`spec-hygiene`, `apk-validation`) | Verifies `FCT-004` spec PR leaves `AndroidManifest.xml` (`INTERNET`-free) and `policies/checks.json` unmodified, and documents exact future `apk_validator.py` pilot-mode transition guards. | `spec-hygiene` |
| `TEST-PILOT-408` | `AC-406.1`, `AC-406.2` | Offline `MockWebServer` (`PilotInventoryCapabilityTest`) | Runs `SessionCoordinator` + `DeviceInventoryRepository` against loopback `MockWebServer`: verifies `sessionEpoch` isolation, `LaserSlam` (`P0`) selection eligibility, and exclusion of `q1`/`n1`/`m1`/`UnknownDevice` from live telemetry subscription. | `android-unit-tests` |
| `TEST-PILOT-409` | `AC-406.3` | Offline Compose UI (`PilotDeviceIdentityCardTest`) | Verifies `PilotReadinessSheet` displays nickname, normalized `sub_type` family, `isAdmin`, `ControlCompatibility`, and redacted `thing_name` without exposing raw tokens. | `android-unit-tests` |
| `TEST-PILOT-410` | `AC-407.1`, `AC-407.2` | Offline `MockWebServer` WS + `StandardTestDispatcher` (`PilotWebSocketBoundsTest`) | Verifies `(sessionEpoch, connectionGeneration)` fencing, read-only `thing_status_update` hydration, bounded `maxReconnectAttempts = 3`, zero retries on `401`/`403`/`3xx`, and automatic disconnect on `observationWindowMs` expiry. | `android-unit-tests` |
| `TEST-PILOT-411` | `AC-408.2` | Offline Domain Guard (`ReadOnlyPilotGuardTest`) | Dispatches `Clean`, `Pause`, `Resume`, and `Dock` while `readOnlyMode == true`: asserts every command returns `CommandResult.Rejected` with zero bytes written to `OkHttpWebSocketTransport.sendOutbound`. | `android-unit-tests` |
| `TEST-PILOT-412` | `AC-408.1` | Offline Integration (`PilotKillSwitchTest`) | Triggers `emergencyDisconnectAndWipe()` mid-session: verifies WS close `1000`, `TokenStore.clear()`, `DeviceInventoryRepository` wipe, `PilotAuthorizationGate` reset to `Disabled`, and immediate revert to `OFFLINE` `SimulatorRobotTransport`. | `android-unit-tests` |
| `TEST-PILOT-413` | `AC-408.3` | Offline Redaction (`PilotSanitizedExporterTest`) | Feeds synthetic `jwt_token`, `"token"`/`"region"` headers, email aliases, and `thing_name` into `PilotSanitizedExporter` and asserts zero raw secrets/PII in exported report. | `android-unit-tests` |
| `TEST-PILOT-414` | `AC-405.1`, `AC-406.2`, `AC-407.1`, `AC-408.1` | **External Authorized Pilot (`android-smoke-test` — Human-Operated)** | Executed ONLY after explicit PO + Lead Maintainer signed Pilot Authorization Form and `T4` `INTERNET` transition PR: verifies live read-only login, `user_thing_list_get`, `LaserSlam` match, read-only `thing_status_update`, Emergency Disconnect, and sanitized capture; remains `NOT_RUN` in offline CI. | `android-smoke-test` |
