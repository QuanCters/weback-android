# FCT-004B Risk Assessment & Threat Model Delta (`T4 Critical Risk`)

- **Change ID:** `FCT-004B`
- **Risk Tier:** `T4 (Critical Risk)` per [policies/risk-tiers.md](../../../policies/risk-tiers.md)
- **References:** [proposal.md](proposal.md) | [requirements.md](requirements.md) | [FCT-004 Risk Assessment](../FCT-004/risk-assessment.md)

---

## 1. T4 Threat Matrix

| Threat ID | Threat Vector | Residual Pre-FCT-004B Risk | FCT-004B Control | Verification Oracle |
| :--- | :--- | :--- | :--- | :--- |
| **`THREAT-451`** | **Ticket Replay & Tampering (`B-SEC-01`)** | Caller constructs or copies `PilotAdmissionTicket` or replays a single ticket across multiple OkHttp requests. | Internal `IssuedTicketRecord` ledger keyed by unique `ticketId`, full-field verification, and atomic `consumeTicketForDispatch` at OkHttp network dispatch. | `TEST-PILOT-451`, `TEST-PILOT-452` |
| **`THREAT-452`** | **Direct Gateway Account Substitution (`B-SEC-02`)** | Direct invocation of `PilotCapabilityRestrictedAuthGateway` with a different account in the same region bypasses controller checks. | `admissionGate.verifyAccountCredentials(...)` enforced inside `probeLogin` before secret encoding or HTTP dispatch. | `TEST-PILOT-453` |
| **`THREAT-453`** | **Deep-Sleep Deadline Drift & Incomplete Expiry (`B-SEC-03`)** | `System.nanoTime()` pauses during Android deep sleep and gate expiry only cleared `activeContext` without cancelling active calls/WS/tokens. | `AuthMonotonicClock` (`SystemClock.elapsedRealtime()`) used everywhere; expiry triggers complete generation-fenced 9-step cleanup. | `TEST-PILOT-454`, `TEST-PILOT-455` |
| **`THREAT-454`** | **`INTERNET` Leak into Offline APK or Artifact Substitution** | Enabling `INTERNET` in `src/main` or swapping `app-debug.apk` and `app-pilot.apk` defeats offline isolation. | `src/main` stays `INTERNET`-free; `src/pilot` adds `INTERNET` under `com.weback.robot.pilot`; `apk_validator.py --connectivity-policy` enforces package ID, path, and permissions. | `TEST-PILOT-456`, `TEST-PILOT-457`, `TEST-PILOT-458` |
| **`THREAT-455`** | **Unapproved Policy Self-Activation** | Modifying `policies/checks.json` without dual human transition approval weakens or bypasses CI verification. | `policies/checks.baseline.json` preserves `e688b6...`; `TRANSITION_AUTHORIZATION` remains unset until human candidate review. | `TEST-PILOT-459` |
