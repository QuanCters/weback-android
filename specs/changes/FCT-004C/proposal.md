# FCT-004C Proposal — Controlled Live Authentication & Single-Inventory Pilot

- **Change ID:** `FCT-004C`
- **Milestone:** `FCT-004C`
- **Title:** `Controlled Live Authentication & Single-Inventory Read-Only Pilot`
- **Risk Tier:** `T4 (Critical Risk — Live Network Admission & Credential Entry)`
- **Governing Architecture & Law:** [specs/constitution.md](../../constitution.md) | [specs/roadmap.md](../../roadmap.md) | [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md)
- **Status:** `CALIBRATED_SPECIFICATION (Gate G1-G3; Offline-Testable Implementation Next; Live Cloud Execution NOT Authorized)`

---

## 1. Context & Problem Statement

Milestones FCT-004A and FCT-004B established an isolated, default-disabled `pilot` build type (`com.weback.robot.pilot`), hardware Keystore security verification, single-use admission ticket gates, and an authoritative 11-check permanent verification policy (`fd870ee6b2b324aa1d2bfbe84d9282df13708821cb7d291d2560d0a80a77bbc4`).

However, live cloud authentication against Grit Cloud (`TEST-PILOT-414`) remains strictly `NOT_RUN`, and one physical-device verification gap remains from FCT-004B:
- **FCT-004B Remaining Limitation:** Deterministic JVM monotonic deadline expiry verification (`TEST-PILOT-455`) passed, whereas physical-device monotonic deadline expiry (`TEST-PILOT-460`) remained `NOT_RUN`. FCT-004C adds actual offline physical-device monotonic deadline expiry (`SystemClock.elapsedRealtime()`) to the mandatory pre-live acceptance checklist (without substituting cold restart for expiry).

Several critical protocol uncertainties remain unresolved from static reverse engineering (`com.yugong.Backome` 6.1.7, `REG-06`, `UNK-AUTH-02`, `UNK-AUTH-04`, `UNK-AUTH-05`, `UNK-AUTH-07`):
1. Live server acceptance of the static-observed login envelope fields (`api_version = "6.1.7"`, `app_name = "WeBack"`, `client_id = "yugong_app"`, and clean-room pilot choice `language = "en"`).
2. Whether Grit Cloud accepts clean-room UTF-8 passwords over TLS (`UNKNOWN`; legacy client hashes via unsalted MD5, which is prohibited).
3. Live server challenge/CAPTCHA triggers on `login` responses.
4. Dynamic `api_url` and `wss_url` origin assignments returned per account/region (`IdentityInfo`).

FCT-004C defines the smallest evidence-grounded, read-only authentication and single-inventory live pilot workflow in the isolated `pilot` build (`com.weback.robot.pilot`), preserving the complete offline simulator and offline mock pilot workflows in both APKs while keeping WebSocket connections, outbound WS frames, and physical robot commands strictly at zero.

---

## 2. Goals & Non-Goals

### Goals
- **G-461:** Define exact external pilot authorization with countersigned human governance approval (`product_owner_approved` / `lead_maintainer_approved` defaulting to `false` in templates) prior to any live network interaction, strictly separated from offline mock authorizations and UI checkboxes.
- **G-462:** Enforce human-operated credential entry into mutable zeroizable buffers (`EphemeralPasswordBuffer`) protected by `FLAG_SECURE`, without `rememberSaveable`, persistent password storage, or impossible JVM heap-zeroization claims.
- **G-463:** Align the outbound login wire envelope (`api_version = "6.1.7"`, `app_name = "WeBack"`, `client_id = "yugong_app"`, clean-room `language = "en"`, `payload.opt = "login"`) with frozen 6.1.7 RE evidence and `FCT-003` `REG-06`, with strict JSON validation.
- **G-464:** Bound execution to at most ONE `LOGIN` attempt (`<= 15s` HTTP timeout, `<= 15` min pilot operating window) with zero automatic retries, zero MD5 fallback, zero passwordless refresh, and zero CAPTCHA automation.
- **G-465:** Enforce conservative dynamic-origin quarantine: if required origins (`api_url`, `wss_url`) are not independently verified and pre-approved, retain only a sanitized endpoint observation, discard the untrusted session/token, revoke authorization, and terminate the run cleanly without auto-retry or resumable quarantined tokens.
- **G-466:** Permit at most ONE read-only inventory request (`user_thing_list_get`) when origins are pre-approved, separate exact target robot validation from telemetry observation, expose only the authorized target robot's minimum necessary read-only inventory data, and enforce ZERO WebSocket connections (`0` initial, `0` reconnects, `0` outbound WS messages) and ZERO physical robot commands.
- **G-467:** Implement the smallest working live pilot UI workflow in the isolated `pilot` build (verified by Activity-level Compose integration tests with injected synthetic adapters / `MockWebServer`), preserve the complete mock workflow in both APKs, guarantee generation-fenced Emergency Disconnect & Wipe returning to `OFFLINE_DEFAULT`, and mandate physical-device monotonic deadline expiry testing before any live run.

### Non-Goals
- WebSocket telemetry connections (`selectAndObserveDevice` / `ws_telemetry_observe`), reconnects, or outbound WS frames (prohibited in FCT-004C; `0` budget).
- Real-time robot control (`send_to_device` is strictly prohibited).
- Live cloud map stream ingestion (deferred to FCT-005).
- Resumable quarantined-token framework or UI-confirmation-based endpoint provenance bypass.
- Legacy MD5 password hashing, speculative alternate login envelope retries, automated CAPTCHA solving, or invented passwordless refresh grants.
- Modifying the offline production APK (`com.weback.robot`) capability boundary, token store architecture, admission architecture, verification harness, or build separation.

---

## 3. Architecture & Boundary Overview

```mermaid
flowchart TD
    Human["PO & Lead Maintainer (Human)"] -->|1. Countersigns External Approval| AuthForm["External Pilot Authorization"]
    AuthForm -->|2. Validates Context in Pilot Build| Gate["PilotAuthorizationGate (Zero-WS Budget)"]
    Human -->|3. Enters Credentials| SecureUI["FLAG_SECURE Credential UI"]
    SecureUI -->|4. Holds in CharArray| Buffer["EphemeralPasswordBuffer"]
    Buffer -->|5. Single-Use Ticket| Dispatch["PilotClientFactory + Restricted Auth Gateway"]
    Dispatch -->|6. HTTPS POST /prod/oauth (Max 1)| Cloud["Grit Cloud Auth API (or MockWebServer)"]
    Cloud -->|7a. Unapproved Dynamic Origins| Quarantine["Retain Sanitized Observation & Discard Session"]
    Quarantine -->|Revoke & Clean Up| Wipe["Emergency Disconnect & Wipe -> OFFLINE_DEFAULT"]
    Cloud -->|7b. Pre-Approved Origins| Inventory["Single Read-Only Inventory (Max 1, Zero WS)"]
    Inventory -->|8a. Exact Target Match & LaserSlam| Result["Minimum Read-Only Target Inventory View"]
    Inventory -->|8b. Missing / Mismatch / Unsupported| Wipe
    Result -->|9. Done or Abort| Wipe
```

---

## 4. Document Index

- [requirements.md](requirements.md) — Traceable functional and security requirements (`REQ-PILOT-461`..`467`).
- [design.md](design.md) — Technical design, calibrated state machine, and transport guards.
- [validation.md](validation.md) — Test oracle strategy and acceptance matrix (`TEST-PILOT-461`..`469`).
- [tasks.md](tasks.md) — Granular implementation and verification tasks (`Task 1`..`7`).
- [traceability.json](traceability.json) — Requirement-to-task-to-test mapping.
- [risk-assessment.md](risk-assessment.md) — Threat modeling and mitigation ledger.
- [external-pilot-authorization.md](external-pilot-authorization.md) — Human authorization form template (defaults to `false`).
- [protocol-evidence-matrix.md](protocol-evidence-matrix.md) — Reconciled WeBack 6.1.7 RE evidence (`REG-06`) and uncertainty log.
