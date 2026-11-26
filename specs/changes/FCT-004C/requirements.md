# FCT-004C Requirements Specification — Controlled Authentication & Single-Inventory Pilot

- **Change ID:** `FCT-004C`
- **Milestone:** `FCT-004C`
- **Document ID:** `SPEC-REQ-FCT004C`
- **Risk Tier:** `T4 (Critical Risk — Live Network Admission & Credential Entry)`
- **Provenance & Governance:** [proposal.md](proposal.md) | [design.md](design.md) | [risk-assessment.md](risk-assessment.md) | [specs/constitution.md](../../constitution.md)

---

## 1. Traceable Requirements Catalog

### REQ-PILOT-461: Human-Triggered External Live-Pilot Authorization
*(Workstream: `T4` — Human Governance & Activation Boundary)*

Every live pilot execution requires prior external human authorization binding the operator identity, test account, target robot, and monotonic time limit.

- **AC-461.1:** `PilotAuthorizationGate` requires a signed `PilotExternalApproval` record containing Product Owner sign-off, authorized account identifier, calling code, expected target robot `thing_name`, approved auth origin, and a monotonic observation deadline ($\le 1800000$ ms / 30 minutes).
- **AC-461.2:** The authorization context is held strictly in volatile memory; it cannot be pre-authorized, auto-activated on boot, or persisted across process termination. The application always boots in `OFFLINE_DEFAULT`.

### REQ-PILOT-462: Human-Operated In-Memory Credential Entry & Screen Protection
*(Workstream: `T4` — Credential Lifecycle & Display Security)*

Credential acquisition must occur exclusively through direct human interaction in a protected window, without disk persistence or unencrypted logging.

- **AC-462.1:** The credential input window enforces Android OS `FLAG_SECURE` (`WindowManager.LayoutParams.FLAG_SECURE`), blocking screenshots, screen recording, and display mirroring during entry.
- **AC-462.2:** Password input is stored exclusively in `EphemeralPasswordBuffer` (backed by a zeroizable `CharArray`); the buffer is wiped immediately upon HTTP request body serialization, cancellation, or error. No credential text is written to disk, Keystore, or logcat.

### REQ-PILOT-463: Evidence-Grounded Wire Login Envelope & Parameter Validation
*(Workstream: `T4` — Protocol Serialization & Boundary Guards)*

Outbound authentication payloads must conform strictly to the ground-truth reverse-engineered envelope while rejecting unexpected fields.

- **AC-463.1:** Outbound authentication request body conforms to the verified wire schema:
  ```json
  {
    "header": {
      "api_version": "1.0",
      "app_name": "WeBack",
      "calling_code": "+XX",
      "account": "user@test.invalid",
      "client_id": "STABLE_CLIENT_ID",
      "language": "en"
    },
    "payload": {
      "opt": "login",
      "pwd": "ENCODED_PASSWORD"
    }
  }
  ```
- **AC-463.2:** `PilotHttpOperationGuard` validates JSON structure before dispatch, rejecting any request containing extra top-level keys, unknown `opt` operations, or missing header fields fail-closed.

### REQ-PILOT-464: Single Login Budget, Anti-Retry & CAPTCHA Fail-Closed Boundary
*(Workstream: `T4` — Rate Limiting & Anti-Automation)*

The pilot must prevent account lockout, brute-forcing, and unauthorized security challenges.

- **AC-464.1:** Exactly ONE login request is permitted per admission ticket (`loginAttemptsUsed <= 1`). Any network error, timeout, or authentication failure exhausts the ticket; automatic password retries are strictly prohibited.
- **AC-464.2:** If the cloud returns `"captcha": true` or any challenge indicator, the client aborts immediately with `CAPTCHA_CHALLENGE_NOT_SUPPORTED` without attempting automated CAPTCHA resolution. Zero MD5 fallback is permitted; password transport uses clean-room UTF-8 over TLS.

### REQ-PILOT-465: Trusted Authentication Origin & Dynamic Endpoint Quarantine
*(Workstream: `T4` — Origin Trust & Quarantine Policy)*

Network traffic is restricted to verified authentication domains, and unvetted server-directed endpoints are quarantined.

- **AC-465.1:** Authentication requests are routed strictly to the approved region endpoint (`https://user.grit-cloud.com/prod/oauth` or `https://user.grit-cloud.cn/prod/oauth`) over TLS 1.3/1.2; cleartext HTTP is denied fail-closed.
- **AC-465.2:** Any dynamic REST API origins or WebSocket URLs returned in the authentication payload are captured in `QuarantinedDynamicOrigins` for human review and policy verification prior to socket establishment.

### REQ-PILOT-466: Single Read-Only Inventory Query & Exact Target Robot Matching
*(Workstream: `T4` — Inventory & Device Isolation)*

Device discovery is limited to a single read-only inventory query verifying the target robot's identity.

- **AC-466.1:** Exactly ONE inventory request (`{"opt": "user_thing_list_get"}`) is permitted (`inventoryRequestsUsed <= 1`). Mutation requests (device rename, sharing, deletion) are rejected fail-closed.
- **AC-466.2:** The returned device list is filtered to verify that the authorized target robot (`thing_name`) is present and matches supported capability metadata (Laser SLAM); unsupported devices are ignored.

### REQ-PILOT-467: Monotonic Window Expiry & Fail-Closed Emergency Disconnect
*(Workstream: `T4` — Teardown & Session Invalidation)*

The session must self-terminate upon monotonic deadline expiry or explicit emergency disconnect.

- **AC-467.1:** `AuthMonotonicClock` evaluates the deadline monotonically; upon expiry, active calls are cancelled, session tokens in `AndroidKeystoreAeadTokenStore` are wiped, and mode resets to `OFFLINE_DEFAULT`.
- **AC-467.2:** Outbound robot commands (`send_to_device`) remain strictly prohibited across gateway, transport, and interceptor layers during the entire pilot lifecycle.
