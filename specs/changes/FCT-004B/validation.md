# FCT-004B Validation Plan & Independent Test Oracles

- **Change ID:** `FCT-004B`
- **Risk Tier:** `T4 (Critical Risk)`
- **References:** [proposal.md](proposal.md) | [requirements.md](requirements.md) | [design.md](design.md) | [traceability.json](traceability.json)

---

## 1. Independent Test Oracles Catalog (`TEST-PILOT-451` .. `TEST-PILOT-460`)

| Oracle ID | Target ACs | Execution Mechanism | Independent Oracle & Expected Outcome | Policy Check ID |
| :--- | :--- | :--- | :--- | :--- |
| `TEST-PILOT-451` | `AC-451.1` | `Fct004BPreInternetSecurityTest` | Verifies forged/unknown `ticketId` and `.copy()`-modified `PilotAdmissionTicket` fields (`httpCallTimeoutMs`, `operation`, `deadlineElapsedMs`, `pilotGeneration`) are rejected fail-closed. | `android-unit-tests` |
| `TEST-PILOT-452` | `AC-451.2` | `Fct004BPreInternetSecurityTest` (`MockWebServer`) | Replays an admitted HTTP `PilotAdmissionTicket` and WS ticket across `PilotClientFactory` `OkHttpClient`: asserts intermediate `verifyTicket` succeeds without consuming, first dispatch increments `MockWebServer.requestCount` to `1`, and replay fails closed with `MockWebServer.requestCount` unchanged (`1`). | `android-unit-tests` |
| `TEST-PILOT-453` | `AC-452.1` | `Fct004BPreInternetSecurityTest` (`MockWebServer`) | Invokes `PilotCapabilityRestrictedAuthGateway.signIn` / `probeLogin` directly while authorization is active using a different account in the same calling-code region: asserts failure, secret buffer zeroing, and `MockWebServer.requestCount == 0`. | `android-unit-tests` |
| `TEST-PILOT-454` | `AC-453.1` | `Fct004BPreInternetSecurityTest` + Static Source Scan | Verifies zero `System.nanoTime` references in `PilotAuthorizationGate`/`PilotModels`, and proves `PilotClientFactory` + `PilotAuthorizationGate` enforce expiry strictly from injected `AuthMonotonicClock`. | `android-unit-tests` |
| `TEST-PILOT-455` | `AC-453.2` | `Fct004BPreInternetSecurityTest` (`MockWebServer` + Deterministic Clock) | Advances deterministic `AuthMonotonicClock` past `observationDeadlineElapsedMs` (simulating deep sleep) without advancing virtual dispatcher uptime: asserts gate/controller rejects admission AND executes full generation-fenced cleanup (active calls cancelled, WS disconnected, session signed out, `OfflineDefault` restored) without affecting a newer generation. | `android-unit-tests` |
| `TEST-PILOT-456` | `AC-454.1`, `AC-454.3` | `lintDebug`, `lintPilot`, `assembleDebug`, `assemblePilot`, `testPilotUnitTest` | Verifies `app-debug.apk` (`com.weback.robot`, zero `INTERNET`) and `app-pilot.apk` (`com.weback.robot.pilot`, `INTERNET` only), matching `minSdk=31`/`targetSdk=35`, `RuntimeAppIdentity` without self-referential APK checksum, and full `testPilotUnitTest` pass. | `pilot-android-build` |
| `TEST-PILOT-457` | `AC-454.2` | `PilotBuildSeparationTest` + `apkanalyzer` | Verifies `DefaultAppContainer` in both builds starts in `OfflineDefault` (`isAuthorized == false`), separate internal permissions and provider authorities (`com.weback.robot.pilot.*`), clear pilot app label, and blocked live physical commands. | `pilot-android-unit-tests` |
| `TEST-PILOT-458` | `AC-455.1`, `AC-455.2` | `verification/selftests/test_apk_validator.py` + `apk_validator.py` | Verifies `--connectivity-policy offline` (default) rejects `INTERNET`, `--connectivity-policy pilot` requires `INTERNET` and rejects missing `INTERNET`/extra permissions/wrong package/cross-artifact substitution, and both reject cleartext/backup/invalid signature/vendor DEX or `.so` binaries. | `harness-selftests` |
| `TEST-PILOT-459` | `AC-456.1`, `AC-456.2` | `verification/run.py` + `evidence.py` | Executes approved baseline (`policies/checks.baseline.json`, `candidate` lane) and proposed policy (`policies/checks.json`, `fast` lane), audits both reports, and confirms `TRANSITION_AUTHORIZATION` remains inactive. | `pilot-apk-validation` |
| `TEST-PILOT-460` | `AC-457.1` | External Human Device Acceptance Checklist (`NOT_RUN`) | Documents side-by-side install, storage isolation, Keystore, simulator preservation, read-only boundary, emergency disconnect, and rollback steps on physical Android >= 12 without automated device execution. | `android-smoke-test` |
