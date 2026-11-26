# FCT-004C Validation Strategy & Verification Oracles

- **Change ID:** `FCT-004C`
- **Milestone:** `FCT-004C`
- **Document ID:** `SPEC-VAL-FCT004C`
- **Risk Tier:** `T4 (Critical Risk — Live Network Admission & Credential Entry)`
- **Provenance & Governance:** [proposal.md](proposal.md) | [requirements.md](requirements.md) | [design.md](design.md) | [specs/constitution.md](../../constitution.md)

---

## 1. Independent Test Oracles Catalog (`TEST-PILOT-461` .. `TEST-PILOT-467`)

| Oracle ID | Target ACs | Execution Mechanism | Independent Oracle & Expected Outcome | Policy Check ID |
| :--- | :--- | :--- | :--- | :--- |
| `TEST-PILOT-461` | `AC-461.1`, `AC-461.2` | `PilotAuthorizationGateTest` | Validates that `PilotAuthorizationGate` requires signed `PilotExternalApproval` binding account, calling code, and monotonic window $\le 30$ mins; confirms zero persistence across restarts. | `pilot-android-unit-tests` |
| `TEST-PILOT-462` | `AC-462.1`, `AC-462.2` | `PilotSignInSheetTest` | Asserts `FLAG_SECURE` window enforcement on password entry Composable and proves `EphemeralPasswordBuffer` zeroizes `CharArray` immediately upon request serialization or cancellation. | `pilot-android-unit-tests` |
| `TEST-PILOT-463` | `AC-463.1`, `AC-463.2` | `Fct004BPreInternetSecurityTest` | Validates `LoginRequestEnvelope` serialization against verified schema; asserts `PilotHttpOperationGuard` rejects extra JSON fields and unauthorized operations fail-closed. | `pilot-android-unit-tests` |
| `TEST-PILOT-464` | `AC-464.1`, `AC-464.2` | `Fct004BPreInternetSecurityTest` (`MockWebServer`) | Asserts exactly ONE login request dispatched per ticket; verifies immediate fail-closed abort on `"captcha": true` response without automated CAPTCHA bypass or MD5 fallback. | `pilot-android-unit-tests` |
| `TEST-PILOT-465` | `AC-465.1`, `AC-465.2` | `AuthorizedPilotEndpointTrustPolicyTest` | Verifies strict HTTPS routing to approved region origins (`user.grit-cloud.com`) and validates quarantine isolation for dynamic API/WSS server responses. | `pilot-android-unit-tests` |
| `TEST-PILOT-466` | `AC-466.1`, `AC-466.2` | `PilotInventoryCapabilityTest` (`MockWebServer`) | Asserts exactly ONE inventory query (`user_thing_list_get`) dispatched; verifies robot identity filtering and capability matching for target `thing_name`. | `pilot-android-unit-tests` |
| `TEST-PILOT-467` | `AC-467.1`, `AC-467.2` | `Fct004BPreInternetSecurityTest` (Virtual Clock) | Advances `AuthMonotonicClock` past deadline: asserts immediate fail-closed teardown, token store deletion, mode reset to `OFFLINE_DEFAULT`, and blocked `send_to_device` commands. | `pilot-android-unit-tests` |
