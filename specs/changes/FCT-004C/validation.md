# FCT-004C Validation Strategy & Verification Oracles

- **Change ID:** `FCT-004C`
- **Milestone:** `FCT-004C`
- **Document ID:** `SPEC-VAL-FCT004C`
- **Risk Tier:** `T4 (Critical Risk — Live Network Admission & Credential Entry)`
- **Provenance & Governance:** [proposal.md](proposal.md) | [requirements.md](requirements.md) | [design.md](design.md) | [specs/constitution.md](../../constitution.md)

---

## 1. Independent Test Oracles Catalog (`TEST-PILOT-461` .. `TEST-PILOT-469`)

| Oracle ID | Target ACs | Execution Mechanism | Independent Oracle & Expected Outcome | Policy Check ID |
| :--- | :--- | :--- | :--- | :--- |
| `TEST-PILOT-461` | `AC-461.1`, `AC-461.2` | `Fct004CPilotAdmissionTest` & `PilotBuildSeparationTest` | Validates countersigned `PilotExternalApproval`, exact candidate/APK/runtime identity, zero-WS budgets (`0` initial, `0` reconnect, `<= 15m` / `900_000L` ms), rejection of missing/placeholder/incomplete/checkbox-only/mock authorizations, zero restart persistence, and rejection of live activation in the offline build. | `pilot-android-unit-tests` |
| `TEST-PILOT-462` | `AC-462.1`, `AC-462.2` | `PilotSignInSheetTest` & `Fct004CLiveWorkflowActivityTest` | Asserts `FLAG_SECURE` window enforcement during credential entry, zero `rememberSaveable`/disk/log retention of secrets, and `EphemeralPasswordBuffer` / `CharArray` zeroization on submission, serialization completion, cancellation, failure, screen exit, and teardown. | `pilot-android-unit-tests` |
| `TEST-PILOT-463` | `AC-463.1`, `AC-463.2` | `Fct004CProtocolEnvelopeTest` (`MockWebServer`) | Captures outbound HTTP POST body independently on `MockWebServer`: asserts `api_version = "6.1.7"`, `app_name = "WeBack"`, `client_id = "yugong_app"`, `language = "en"`, `payload.opt = "login"`; asserts `PilotHttpOperationGuard` rejects extra/duplicate/wrong-type fields fail-closed. | `pilot-android-unit-tests` |
| `TEST-PILOT-464` | `AC-464.1`, `AC-464.2` | `Fct004CProtocolEnvelopeTest` (`MockWebServer`) | Asserts incorrect account/calling code makes `0` requests; at most `1` login request per run; `0` automatic retries after HTTP/timeout/challenge failure; `0` passwordless refresh calls; `0` MD5 fallback; immediate fail-closed abort on challenge response. | `pilot-android-unit-tests` |
| `TEST-PILOT-465` | `AC-465.1`, `AC-465.2` | `Fct004CQuarantineTerminationTest` (`MockWebServer`) | Asserts `/prod/oauth` routing & TLS hygiene; on unknown dynamic origins, proves `TokenStore` has `0` writes, inventory receives `0` requests, sanitized `ObservedOriginSummary` is retained, authorization is revoked, and subsequent login/inventory attempts fail without a new authorization. | `pilot-android-unit-tests` |
| `TEST-PILOT-466` | `AC-466.1`, `AC-466.2` | `Fct004CInventoryValidationTest` (`MockWebServer`) | With pre-approved origins, asserts at most `1` `user_thing_list_get` request, `0` WebSocket upgrade attempts, `0` robot-command frames, `selectAndObserveDevice` never invoked, exact `thing_name` + `LaserSlam` validation exposing only minimum target data, and safe termination on missing/mismatched/unsupported/malformed target. | `pilot-android-unit-tests` |
| `TEST-PILOT-467` | `AC-467.1` | `Fct004CLiveWorkflowActivityTest` (Robolectric `MainActivity` + `MockWebServer`) | Exercises full 11-step live workflow in `MainActivity` (pilot build) with injected synthetic gateways/`MockWebServer` AND verifies preserved offline mock workflow (`MainActivityMockPilotFlowTest`) in both `debug` and `pilot`. | `pilot-android-unit-tests` |
| `TEST-PILOT-468` | `AC-467.2` | `Fct004CExpiryAndTeardownTest` (Virtual Clock + `MockWebServer`) | Advances `AuthMonotonicClock` past 15-min deadline and verifies generation-fenced teardown, active call cancellation, token store clear, explicit storage failure reporting, late-callback rejection, and return to `OFFLINE_DEFAULT` without claiming remote revocation. | `pilot-android-unit-tests` |
| `TEST-PILOT-469` | `AC-467.2` | Physical-Device Pre-Live Acceptance Checklist (`NOT_RUN` in CI) | Mandatory offline physical-device `TEST-PILOT-460` + real `SystemClock.elapsedRealtime()` monotonic deadline expiry test on Android >= 12 prior to any live run (cold restart MUST NOT substitute for actual expiry). | `android-smoke-test` |
