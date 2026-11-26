# FCT-004C Risk Assessment & Threat Modeling

- **Change ID:** `FCT-004C`
- **Milestone:** `FCT-004C`
- **Document ID:** `SPEC-RISK-FCT004C`
- **Risk Tier:** `T4 (Critical Risk — Live Network Admission & Credential Entry)`
- **Provenance & Governance:** [proposal.md](proposal.md) | [requirements.md](requirements.md) | [specs/constitution.md](../../constitution.md)

---

## 1. Threat Modeling & Risk Ledger

| Threat ID | Threat Scenario | Severity | Inherent Risk | Mitigation Strategy | Residual Risk |
| :--- | :--- | :---: | :---: | :--- | :---: |
| **THREAT-01** | Account lockout via automated brute force or retry loops | Critical | High | Bound execution to at most ONE `LOGIN` attempt (`maxLoginAttempts = 1`, `maxPasswordRetries = 0`). Zero automatic retries on failure and zero passwordless refresh. | **Low (Eliminated)** |
| **THREAT-02** | Credential leakage via logcat, `rememberSaveable`, heap retention, or screen capture | Critical | High | `FLAG_SECURE` enforced during entry; no `rememberSaveable`/disk/log persistence; `EphemeralPasswordBuffer` and mutable `CharArray` buffers wiped on submit/serialization/cancel/error/exit/teardown; immutable `String`/request-body lifetimes minimized (without claiming complete JVM heap zeroization). | **Low** |
| **THREAT-03** | Malicious or unverified dynamic API/WebSocket origin redirection | High | High | Conservative quarantine termination (`SEC-462`): unapproved dynamic origins retain only sanitized `ObservedOriginSummary`, discard untrusted session/token, revoke authorization, and terminate without auto-retry, budget reset, or resumable tokens; UI confirmation is never accepted as provenance proof. | **Low** |
| **THREAT-04** | Inadvertent WebSocket connection or physical robot command leakage | Critical | High | FCT-004C enforces zero WS connections (`0` initial, `0` reconnects, `0` outbound frames), zero physical robot commands (`0`), `PilotHttpOperationGuard`, and omits `selectAndObserveDevice` from the live inventory workflow. | **Zero (Structural)** |
| **THREAT-05** | Legacy MD5 credential weakness or unverified password encoding | High | Medium | Prohibit MD5 strategy/fallback (`Utf8TlsDefault` over TLS 1.2/1.3 only); server UTF-8 acceptance remains explicitly `UNKNOWN` (`UNK-AUTH-05`). | **Low** |
| **THREAT-06** | Automated CAPTCHA bypass or challenge loop | Medium | Medium | Fail closed (`ChallengeRequired`) on `"captcha": true` or challenge indicators with zero automation and zero retry. | **Zero** |
| **THREAT-07** | Stale session revival, expired window continuation, or offline APK live-network activation | High | High | Offline APK (`PILOT_INTERNET_CAPABLE = false`) rejects live workflow; `AuthMonotonicClock` enforces `<= 15m` window with generation-fenced cleanup, explicit storage failure reporting, conservative restart reauthentication, no remote token revocation claim, and mandatory physical-device monotonic expiry check (`TEST-PILOT-469`) before any live run. | **Low** |
