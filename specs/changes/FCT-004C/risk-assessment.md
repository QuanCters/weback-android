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
| **THREAT-01** | Account lockout via automated brute force or retry loops | Critical | High | Bound execution to exactly ONE login attempt (`loginAttemptsUsed <= 1`). Zero automatic retries on failure. | **Low (Eliminated)** |
| **THREAT-02** | Credential leakage via logcat, heap inspection, or screen sniffer | Critical | High | Password entered in `FLAG_SECURE` window; held in `EphemeralPasswordBuffer` (`CharArray`); zeroized immediately. | **Low** |
| **THREAT-03** | Malicious / untrusted dynamic API or WebSocket redirection | High | High | All unpinned dynamic origins returned by server are quarantined in `QuarantinedDynamicOrigins` without socket connection. | **Low** |
| **THREAT-04** | Inadvertent robot movement or physical command leakage | Critical | High | `send_to_device` operation blocked fail-closed by `PilotHttpOperationGuard` and omitted from transport interface. | **Zero (Structural)** |
| **THREAT-05** | Legacy MD5 password digest interception | High | Medium | Prohibit MD5 hashing; enforce clean-room UTF-8 password serialization over authenticated TLS 1.3/1.2. | **Low** |
| **THREAT-06** | Automated CAPTCHA solving challenge | Medium | Medium | If `"captcha": true` is received, client aborts immediately with `CAPTCHA_CHALLENGE_NOT_SUPPORTED`. | **Zero** |
| **THREAT-07** | Session token persistence or unmanaged revival | High | High | Token store cleared fail-closed upon monotonic deadline expiry or Emergency Disconnect. | **Low** |
