# FCT-004C Implementation & Verification Tasks

- **Change ID:** `FCT-004C`
- **Milestone:** `FCT-004C`
- **Document ID:** `SPEC-TASK-FCT004C`
- **Risk Tier:** `T4 (Critical Risk — Live Network Admission & Credential Entry)`
- **Provenance & Governance:** [proposal.md](proposal.md) | [requirements.md](requirements.md) | [design.md](design.md) | [validation.md](validation.md) | [specs/constitution.md](../../constitution.md)

---

### Task 1: External Authorization Model, Zero-WS Admission Budgets & Offline Isolation
- Extend `PilotAuthorizationGate` validation minimally to support zero-WebSocket FCT-004C budgets (`maxInitialWsConnections = 0`, `maxWsReconnectAttempts = 0`, `maxPhysicalRobotCommands = 0`, `observationWindowMs <= 900_000L`) without weakening FCT-004A/B checks.
- Enforce `PILOT_INTERNET_CAPABLE` build separation, non-persisted `OFFLINE_DEFAULT` startup, rejection of placeholder/incomplete/UI-checkbox-only/mock authorizations on live gateways.
- Map to: `REQ-PILOT-461` (`AC-461.1`, `AC-461.2`).

### Task 2: Credential Lifecycle, `FLAG_SECURE` & Ephemeral Buffers
- Preserve `FLAG_SECURE` during sensitive entry; ensure zero `rememberSaveable`, zero persistent credential storage, and zero secret retention in logs or diagnostic exports.
- Wipe mutable `CharArray` buffers (`EphemeralPasswordBuffer`, `AuthCredentials`) on submission, serialization completion, cancellation, failure, screen exit, and teardown while minimizing immutable `String`/body lifetimes without overclaiming complete JVM heap zeroization.
- Map to: `REQ-PILOT-462` (`AC-462.1`, `AC-462.2`).

### Task 3: Evidence-Grounded Login Wire Envelope & Strict Guard
- Verify and test `AuthEnvelopeConfig` (`api_version = "6.1.7"`, `app_name = "WeBack"`, `client_id = "yugong_app"`, `language = "en"`) and `PilotHttpOperationGuard` strict JSON key/type/operation rejection.
- Map to: `REQ-PILOT-463` (`AC-463.1`, `AC-463.2`).

### Task 4: Single Login Budget, Anti-Retry & Challenge Fail-Closed Enforcement
- Enforce `LOGIN` max 1 attempt per authorization run, zero automatic retries, zero passwordless refresh, `<= 15s` timeout, zero requests on mismatched account/region, zero MD5 fallback, and immediate fail-closed abort on challenge indicators.
- Map to: `REQ-PILOT-464` (`AC-464.1`, `AC-464.2`).

### Task 5: Trusted Region Routing & Conservative Dynamic-Origin Quarantine Termination
- Preserve CN/Global `/prod/oauth` routing and TLS enforcement.
- Implement conservative quarantine termination for FCT-004C live mode: unapproved dynamic origins retain ONLY sanitized `ObservedOriginSummary`, discard untrusted session/tokens, revoke authorization and run cleanup, never auto-retry login or reset budgets, and require a separate newly approved authorization for any later attempt.
- Map to: `REQ-PILOT-465` (`AC-465.1`, `AC-465.2`).

### Task 6: Single Read-Only Inventory Query & Separated Exact Target Validation (Zero WS)
- Enforce `USER_THING_LIST_GET` max 1 request when required origins are pre-approved, with ZERO WebSocket initial connections, ZERO reconnects, ZERO outbound WS frames, and ZERO calls to `selectAndObserveDevice`.
- Separate exact target robot validation (`thing_name` + `LaserSlam` capability) from telemetry observation, exposing only the target robot's minimum necessary read-only inventory data and safely terminating on missing, mismatched, unsupported, or malformed inventory.
- Map to: `REQ-PILOT-466` (`AC-466.1`, `AC-466.2`).

### Task 7: Minimal Live Pilot UI Integration, Monotonic Expiry & Pre-Live Device Checklist
- Wire the minimal live pilot workflow in `MainActivity` / `PilotReadinessSheet` (isolated `pilot` build only) alongside preserved mock workflows in both APKs, verified by Activity-level Compose integration tests with injected synthetic adapters / `MockWebServer`.
- Verify generation-fenced deadline expiry, Emergency Disconnect & Wipe, and return to `OFFLINE_DEFAULT`; document the remaining FCT-004B limitation (`NOT_RUN` physical-device monotonic expiry) and establish the mandatory FCT-004C offline physical-device monotonic deadline expiry pre-live checklist.
- Map to: `REQ-PILOT-467` (`AC-467.1`, `AC-467.2`).
