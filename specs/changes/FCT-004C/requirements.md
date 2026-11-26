# FCT-004C Requirements Specification — Controlled Authentication & Single-Inventory Pilot

- **Change ID:** `FCT-004C`
- **Milestone:** `FCT-004C`
- **Document ID:** `SPEC-REQ-FCT004C`
- **Risk Tier:** `T4 (Critical Risk — Live Network Admission & Credential Entry)`
- **Provenance & Governance:** [proposal.md](proposal.md) | [design.md](design.md) | [risk-assessment.md](risk-assessment.md) | [specs/constitution.md](../../constitution.md)

---

## 1. Traceable Requirements Catalog

### REQ-PILOT-461: Human-Triggered External Live-Pilot Authorization
*(Workstream: `T4` — Human Governance, Build Separation & Activation Boundary)*

Every live pilot execution requires prior external human governance authorization binding the candidate/APK identity, dedicated test account, exact target robot, permitted zero-WebSocket operation set, and monotonic time limit.

- **AC-461.1:** `PilotAuthorizationGate` requires a countersigned external `PilotExternalApproval` record (Product Owner + Lead Maintainer + external acceptance record reference — NOT a claimed cryptographic signature unless separately implemented), candidate/runtime identity (`applicationId`, `versionName`, `pilotBuildEnabled`, and externally verified candidate SHA / APK SHA-256), dedicated isolated test account + exact calling code, exact target `LaserSlam` robot `thing_name`, approved auth origin/path, permitted operations strictly within `{LOGIN, USER_THING_LIST_GET}`, zero-WebSocket budgets (`maxInitialWsConnections = 0`, `maxWsReconnectAttempts = 0`, `maxPhysicalRobotCommands = 0`, `httpCallTimeoutMs <= 15_000`), and a monotonic initial authorization window `<= 900_000` ms (15 minutes; pilot-specific operating limit, not a reverse-engineered server contract), while extending admission validation without weakening FCT-004A/B behavior.
- **AC-461.2:** The authorization context is held strictly in volatile memory and never persisted across process termination; the application always boots in `OFFLINE_DEFAULT`. The offline APK (`com.weback.robot`, `PILOT_INTERNET_CAPABLE = false`) MUST reject live pilot activation; a UI checkbox alone MUST NOT activate live networking; and `OFFLINE_MOCK_FIXTURE` mock authorizations MUST NEVER authorize live gateways.

### REQ-PILOT-462: Human-Operated In-Memory Credential Entry & Screen Protection
*(Workstream: `T4` — Credential Lifecycle & Display Security)*

Credential acquisition must occur exclusively through direct human interaction in a protected window, minimizing secret lifetimes without disk persistence, `rememberSaveable`, or logging.

- **AC-462.1:** The credential input window enforces Android OS `FLAG_SECURE` (`WindowManager.LayoutParams.FLAG_SECURE`), blocking screenshots, screen recording, and display mirroring during sensitive entry.
- **AC-462.2:** Passwords are NEVER retained via `rememberSaveable`, persistent state, logs, or diagnostic exports; controllable mutable secret buffers (`EphemeralPasswordBuffer`, `AuthCredentials.secret` `CharArray`, and local secret arrays) are wiped immediately upon submission, serialization completion, cancellation, failure, screen exit, and teardown, while minimizing unavoidable immutable `String` and HTTP request-body lifetimes without claiming complete JVM heap zeroization.

### REQ-PILOT-463: Evidence-Grounded Wire Login Envelope & Parameter Validation
*(Workstream: `T4` — Protocol Serialization & Boundary Guards)*

Outbound authentication payloads must conform strictly to the reconciled WeBack 6.1.7 reverse-engineered envelope (`REG-06`, `com.yugong.Backome.function.b`) while rejecting unexpected fields.

- **AC-463.1:** Outbound authentication request body conforms to the verified wire schema and `AuthEnvelopeConfig` (`api_version = "6.1.7"`, `app_name = "WeBack"`, `client_id = "yugong_app"`, and explicit clean-room pilot choice `language = "en"`):
  ```json
  {
    "header": {
      "api_version": "6.1.7",
      "app_name": "WeBack",
      "calling_code": "+1",
      "account": "user@test.invalid",
      "client_id": "yugong_app",
      "language": "en"
    },
    "payload": {
      "opt": "login",
      "pwd": "<UTF-8_PASSWORD_OVER_TLS>"
    }
  }
  ```
- **AC-463.2:** `PilotHttpOperationGuard` validates JSON structure strictly before dispatch, rejecting any request containing extra keys, duplicate keys, wrong field types, unknown `opt` operations, or missing/blank header fields fail-closed.

### REQ-PILOT-464: Single Login Budget, Anti-Retry & Challenge Fail-Closed Boundary
*(Workstream: `T4` — Rate Limiting & Anti-Automation)*

The pilot must prevent account lockout, brute-forcing, speculative fallbacks, and unauthorized security challenges.

- **AC-464.1:** At most ONE `LOGIN` request is permitted across an entire authorization run (`loginAttemptsUsed <= 1`), with maximum HTTP call timeout `<= 15` seconds (`15_000` ms). Incorrect account or calling code produces zero network requests; any network error, timeout, or authentication failure terminates the run with zero automatic authentication retries and zero automatic passwordless refresh (`DisabledPasswordlessRefreshStrategy`).
- **AC-464.2:** Legacy MD5 password hashing/fallback, speculative alternate login envelope retries, invented passwordless refresh grants, and CAPTCHA automation are strictly prohibited. Password transport uses clean-room UTF-8 over TLS (`Utf8TlsDefault`; server UTF-8 acceptance remains `UNKNOWN`), and any CAPTCHA/challenge indicator (`"captcha": true` or challenge message) aborts immediately fail-closed (`ChallengeRequired`).

### REQ-PILOT-465: Trusted Authentication Origin & Conservative Dynamic-Origin Quarantine
*(Workstream: `T4` — Origin Trust & Conservative Quarantine Policy)*

Network traffic is restricted to verified authentication domains, and unverified server-directed dynamic origins never receive credentials or tokens.

- **AC-465.1:** Authentication requests are routed strictly to the approved region endpoint (`+86`/`0086` -> `https://user.grit-cloud.cn/prod/oauth`, else `https://user.grit-cloud.com/prod/oauth`) over TLS (`followRedirects = false`, `Proxy.NO_PROXY`); cleartext HTTP is denied fail-closed.
- **AC-465.2:** Dynamic identity fields (`api_url`, `wss_url`, `region_name`, `jwt_token`, `expired_time`) are evaluated conservatively: if all required origins were independently verified and pre-approved (`pinnedApiOrigin` / `pinnedWssOrigin` or approved trust policy), execution may continue to the single authorized inventory request; otherwise, the client MUST retain ONLY the sanitized endpoint observation (`ObservedOriginSummary`), discard the untrusted session/token (never writing to `TokenStore` or calling inventory), revoke authorization and perform required cleanup, terminate the authorized run without automatically repeating login or resetting request budgets, and NEVER accept UI confirmation as proof of endpoint provenance. Any later login requires a separate newly approved authorization, and no resumable quarantined-token framework is introduced.

### REQ-PILOT-466: Single Read-Only Inventory Query & Exact Target Robot Validation
*(Workstream: `T4` — Zero-WebSocket Inventory & Device Isolation)*

Device discovery is limited to a single read-only inventory query verifying the exact authorized target robot's identity and capability without any WebSocket connection.

- **AC-466.1:** At most ONE `USER_THING_LIST_GET` request (`{"opt": "user_thing_list_get"}`) is permitted across an entire authorization run when required origins are pre-approved. Zero WebSocket initial connections (`0`), zero WebSocket reconnects (`0`), zero outbound application-level WS messages (`0`), and zero physical robot commands (`0`) are permitted; FCT-004C live inventory workflow MUST NOT call `selectAndObserveDevice` or any equivalent WebSocket connection method.
- **AC-466.2:** Exact target inventory validation is separated from telemetry observation: the workflow validates that the authorized target robot (`thing_name`) is present and passes `LaserSlam` capability validation (`ControlCompatibility.MvpSupportedShadowWs.isMvpControlSupported`), exposing ONLY the authorized target robot's minimum necessary read-only inventory data. Unknown or unsupported target, mismatched `thing_name`, failed capability validation, malformed response, or missing target results in safe termination and cleanup.

### REQ-PILOT-467: Minimal Live Pilot UI Integration, Monotonic Expiry & Pre-Live Device Readiness
*(Workstream: `T4` — Product Integration, Teardown & Physical-Device Acceptance Readiness)*

The isolated `pilot` build exposes the smallest working live pilot workflow while preserving offline simulator/mock workflows, generation-fenced cleanup, and mandatory pre-live physical-device expiry verification.

- **AC-467.1:** In the isolated `pilot` build (`com.weback.robot.pilot`), `MainActivity` and `PilotReadinessSheet` preserve the complete offline simulator and offline mock pilot workflows (in both `debug` and `pilot` APKs) while supporting the 11-step live pilot workflow (default `OFFLINE_DEFAULT` startup -> explicit live authorization review -> external approval context validation -> `FLAG_SECURE` credential entry -> single `LOGIN` -> sanitized auth result -> quarantine termination or pre-approved continuation -> single `USER_THING_LIST_GET` -> exact target robot and capability result -> Emergency Disconnect & Wipe -> return to `OFFLINE_DEFAULT`), verified by Activity-level Compose integration tests with injected synthetic adapters / `MockWebServer`.
- **AC-467.2:** `AuthMonotonicClock` deadline expiry and Emergency Disconnect execute immediate network revocation, generation-fenced cleanup (preventing late results from restoring revoked authorization), explicit storage failure reporting, conservative restart reauthentication, and reset to `OFFLINE_DEFAULT` without claiming remote bearer token revocation. Records the remaining FCT-004B limitation (deterministic JVM expiry verification passed; physical-device monotonic deadline expiry was `NOT_RUN`) and adds actual offline physical-device monotonic deadline expiry (`SystemClock.elapsedRealtime()`, never substituted by cold restart) to the mandatory FCT-004C pre-live acceptance checklist.
