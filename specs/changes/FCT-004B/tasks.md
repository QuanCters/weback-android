# FCT-004B Implementation Tasks

- **Change ID:** `FCT-004B`
- **Risk Tier:** `T4 (Critical Risk)`
- **References:** [proposal.md](proposal.md) | [requirements.md](requirements.md) | [design.md](design.md) | [validation.md](validation.md)

---

### Task 1: Pre-`INTERNET` Ticket & Clock Hardening (`B-SEC-01`, `B-SEC-03` Domain/Gate)
- Add `PilotClock` and `RuntimeAppIdentity` in `PilotModels.kt`.
- Remove `System.nanoTime()` from `PilotAdmissionTicket` and `PilotAuthorizationGate`.
- Implement `IssuedTicketRecord` ledger, intermediate `verifyTicket`, atomic `consumeTicketForDispatch`, and `attachOnWindowExpiredListener` in `PilotAuthorizationGate`.
- Add unit tests in `PilotAuthorizationGateTest` / `Fct004BPreInternetSecurityTest`.

### Task 2: Gateway Account Binding & Dispatch Interceptor Enforcement (`B-SEC-01`, `B-SEC-02`, `B-SEC-03` Transport/Controller)
- Enforce `verifyAccountCredentials` in `PilotCapabilityRestrictedAuthGateway.probeLogin` prior to credential serialization or network dispatch.
- Update `PilotClientFactory.buildPilotOkHttpClient` to accept `AuthMonotonicClock` and perform atomic `consumeTicketForDispatch` with HTTP method/body `opt` and WebSocket upgrade verification.
- Wire `PilotSessionController` full 9-step cleanup on gate window expiration and `enforceDeadlineIfExpired()`, plus `DefaultAppContainer` `RuntimeAppIdentity`.
- Add `MockWebServer` replay, direct-gateway different-account, and deterministic deep-sleep expiry tests.

### Task 3: Isolated `pilot` Build Type & Separate Manifest (`com.weback.robot.pilot`)
- Configure `pilot` `buildType` (`applicationIdSuffix = ".pilot"`, `BuildConfig` fields) in `app/build.gradle.kts`.
- Add `app/src/pilot/AndroidManifest.xml` (only `INTERNET`) and `app/src/pilot/res/values/strings.xml` (`WeBack Robot Pilot (Read-Only)`).
- Verify `./gradlew assembleDebug assemblePilot lintDebug lintPilot testDebugUnitTest testPilotUnitTest` and manifest/DEX separation.

### Task 4: Unified `apk_validator.py` Connectivity-Policy Transition & Selftests
- Extend `verification/checks/apk_validator.py` with `--connectivity-policy {offline,pilot}` (`offline` default), exact path/package binding, required `INTERNET` in `pilot`, provider authority check, and substitution rejection.
- Add negative selftests in `verification/selftests/test_apk_validator.py`.

### Task 5: Verification Policy Proposal, Dual-Lane Evidence & Security Review
- Preserve approved baseline in `policies/checks.baseline.json` (`e688b6fef22290281facf91d26f42ae53ff527ca7f6b599a750d24d66873985e`) and prepare proposed policy in `policies/checks.json`.
- Execute both baseline (`candidate` lane) and proposed (`fast` lane) runs, audit evidence reports, measure per-check runtime, and document smaller-policy evaluation.

### Task 6: Device Acceptance Preparation & Candidate Handoff
- Export offline debug APK, isolated pilot APK, and evidence ZIP without executing on physical hardware.
- Update `specs/changes/FCT-004B/traceability.json` and prepare candidate handoff artifacts.
