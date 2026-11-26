# FCT-004C Implementation & Verification Tasks

- **Change ID:** `FCT-004C`
- **Milestone:** `FCT-004C`
- **Document ID:** `SPEC-TASK-FCT004C`
- **Risk Tier:** `T4 (Critical Risk — Live Network Admission & Credential Entry)`
- **Provenance & Governance:** [proposal.md](proposal.md) | [requirements.md](requirements.md) | [design.md](design.md) | [validation.md](validation.md) | [specs/constitution.md](../../constitution.md)

---

### Task 1: Human-Triggered External Live-Pilot Authorization Model & Memory Gating
- Define `PilotExternalApproval` validation in `PilotAuthorizationGate`.
- Ensure authorization cannot persist across process restarts.
- Map to: `REQ-PILOT-461` (`AC-461.1`, `AC-461.2`).

### Task 2: FLAG_SECURE UI & Ephemeral Credential Buffer
- Verify `FLAG_SECURE` window enforcement on password entry Composable.
- Implement zeroizable `EphemeralPasswordBuffer` with `CharArray`.
- Map to: `REQ-PILOT-462` (`AC-462.1`, `AC-462.2`).

### Task 3: Evidence-Grounded Wire Login Serialization & Operation Guard
- Formalize `LoginRequestEnvelope` with verified header and payload fields.
- Harden `PilotHttpOperationGuard` to reject unknown keys fail-closed.
- Map to: `REQ-PILOT-463` (`AC-463.1`, `AC-463.2`).

### Task 4: Single Login Budget, Anti-Retry & CAPTCHA Fail-Closed Guards
- Enforce strict single-use login ticket quota (`loginAttemptsUsed <= 1`).
- Abort immediately on `"captcha": true` without automated solving.
- Map to: `REQ-PILOT-464` (`AC-464.1`, `AC-464.2`).

### Task 5: Trusted Origin Routing & Dynamic Origin Quarantine Engine
- Route login to approved region endpoint over TLS 1.3/1.2.
- Capture unpinned API/WSS server responses into `QuarantinedDynamicOrigins`.
- Map to: `REQ-PILOT-465` (`AC-465.1`, `AC-465.2`).

### Task 6: Single Read-Only Inventory Query & Device Identity Match
- Implement single inventory query boundary (`inventoryRequestsUsed <= 1`).
- Filter inventory for exact authorized `thing_name` and Laser SLAM capability.
- Map to: `REQ-PILOT-466` (`AC-466.1`, `AC-466.2`).

### Task 7: Monotonic Expiry & Complete Fail-Closed Teardown
- Integrate `AuthMonotonicClock` deadline expiration handler.
- Verify complete session token purge and emergency disconnect sequence.
- Map to: `REQ-PILOT-467` (`AC-467.1`, `AC-467.2`).
