# Change Proposal: FCT-003 — Authentication, Device Inventory & Connectivity Readiness

- **Change ID:** `FCT-003`
- **Milestone:** Milestone 3 — Authentication, Device Inventory & Connectivity Readiness
- **Milestone Risk Ceiling:** `T4 (Critical Risk)` — Workstream-partitioned: `T4` for Authentication Contracts, Sensitive Token Storage & Android Keystore Cryptography; `T3 (High Risk)` for Network Protocol Contracts, Device Inventory/Capability Classification & Connectivity Adapters (per [policies/risk-tiers.md](../../../policies/risk-tiers.md); NEVER downgraded to `T2`)
- **Status:** `G0–G3 SPECIFICATION CANDIDATE (STOP AT GATE G3 IMPLEMENTATION READINESS)`
- **Prerequisite:** [FCT-002](../FCT-002/proposal.md) squash-merged on `main` (`d2fbf2d4b22f48923459b40d9e7afe8ba48618f9`, tree `87cc4b5aa6ae21d02c552efced92506852dc399a`) under unchanged 7-check policy (`policies/checks.json`)
- **Governance Links:** [specs/constitution.md](../../constitution.md) | [specs/mission.md](../../mission.md) | [specs/roadmap.md](../../roadmap.md) | [specs/tech-stack.md](../../tech-stack.md) | [specs/deployment.md](../../deployment.md) | [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md) | [risk-assessment.md](risk-assessment.md)

---

## 1. Problem Statement

Following `FCT-001` (clean-room `:app` foundation and build verification) and `FCT-002` (pure Kotlin robot control domain, synchronized `SimulatorRobotTransport`, and interactive Compose dashboard), the WeBack Android client remains strictly single-device and simulator-backed (`DefaultAppContainer`). Before a future hardware pilot (`FCT-004`) can ever be authorized to contact live Grit Cloud / AWS IoT endpoints, the project needs a clean-room, cryptographically hardened, specification-first baseline covering:

1. Account authentication contract, region-routed session lifecycle, and token expiry/renewal semantics.
2. Android Keystore AES-256-GCM credential/token storage architecture (completing the Security ADR mandated by [specs/tech-stack.md](../../tech-stack.md) Section 3.3) with zero plaintext password retention and zero `EncryptedSharedPreferences`.
3. Device inventory retrieval (`user_thing_list_get`) and deterministic `sub_type` capability classification filtering MVP-supported Laser SLAM vacuums from unsupported legacy XMPP/peripheral hardware.
4. Cloud WebSocket connection lifecycle state machine, automated secret redaction, fault recovery, and dual **Offline Fixture** + **loopback `MockWebServer`** adapters.

Direct bytecode inspection of the frozen legacy reverse-engineering artifacts (`com.yugong.Backome` 6.1.7) also revealed material contradictions between legacy narrative/catalog documents and actual Dalvik/JADX implementation (`REG-06` through `REG-10`), alongside critical unresolved server-side behaviors that must be reconciled cleanly and quarantined prior to writing any production implementation code (`C-001`, `C-002`, `C-014`).

---

## 2. Objectives

1. **Reconcile & Quarantine Legacy Protocol Evidence (`REG-06`..`REG-10`):** Follow the strict Evidence Precedence Rule in [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md) (Decompiled Bytecode > `protocols.json` > `final/*.md`) to resolve authentication, header, refresh, WebSocket, and SSL contradictions, and quarantine all server-side unknowns before implementation begins.
2. **Establish T4 Authentication, Session & Android Keystore Storage Contracts:** Define pure Kotlin session/auth contracts (`SessionStateMachine`, `TokenExpiryPolicy`), forbid disk storage of plaintext passwords or MD5 digests, mandate Android Keystore AES-256-GCM AEAD token storage (`KeystoreAeadTokenStore`) with fail-closed tamper wiping, and enforce automated `SecretRedactor` scrubbing across logs, exceptions, and diagnostics.
3. **Define T3 Device Inventory, Capability Detection & WebSocket Lifecycle Contracts:** Model `user_thing_list_get` JSON parsing (`kotlinx.serialization.json`), conservative `thing_status` hydration, deterministic `sub_type` prefix classification (`LaserSlam`, `VisualLaser`, `Gyro`, `XmppLegacy`, excluded peripherals, `Unknown`), and unified `ConnectionState` WebSocket lifecycle (`Disconnected`, `Connecting`, `Connected`, `Reconnecting`, `Closed`) over TLS upgrade headers.
4. **Enforce Deterministic Offline & MockWebServer Verification (`C-006`, `C-010`):** Guarantee 100% of automated tests run hermetically via in-process fixtures, Robolectric Keystore tests, and loopback `okhttp3.mockwebserver.MockWebServer` with zero live network calls, zero proprietary binaries, and zero production credentials.
5. **Preserve Existing Policy & Manifest Boundaries Until Explicitly Transitioned:** Reuse the existing 7-check policy (`policies/checks.json`) across `FCT-003` acceptance criteria, keep `policies/` and `verification/` untouched in this specification PR, keep live `android.permission.INTERNET` excluded from the FCT-003 runtime APK manifest unless separately authorized via an isolated policy/manifest PR, and document recommended standalone future security checks separately.

---

## 3. Scope Boundaries

### 3.1 In Scope (Specification Scope -> Target Implementation Later Upon G3 Approval)
- Pure Kotlin authentication, session lifecycle, token expiry/renewal, connectivity error, device inventory, and capability-detection domain contracts under `com.weback.robot.domain`.
- Security layer (`com.weback.robot.security`) threat model, Trust Boundaries, Keystore AES-256-GCM ADR & envelope specification, fail-closed corruption handling, URI scheme/safety validator, and automated secret-redaction pipeline.
- Network protocol contracts & clean-room adapters (`com.weback.robot.protocol`) for auth gateway, device inventory (`user_thing_list_get`), and cloud WebSocket lifecycle (`Connected`/`Disconnected`/reconnect/error handling), implemented with both deterministic `OfflineFixture*` adapters and `OkHttp` + `kotlinx.serialization` adapters tested hermetically against `MockWebServer`.
- Negative authentication test matrix, session invalidation, Keystore AEAD corruption/tamper tests, and automated log-redaction tests.
- Complete governance artifacts: `proposal.md`, `risk-assessment.md`, `requirements.md`, `design.md`, `validation.md`, `tasks.md`, and `traceability.json`.

### 3.2 Out of Scope (Explicit Non-Goals & Hard Prohibitions)
- **No FCT-003 Production Code in This Change:** This PR stops strictly at Gate G3 (`Implementation Readiness`); no Kotlin files or Gradle build files are altered here.
- **No Live Network Calls or Production Credentials (`C-010`, `C-013`):** Automated builds and tests must never contact `grit-cloud.cn`, `grit-cloud.com`, AWS IoT endpoints, or physical robots, and no real account credentials, JWTs, or private keys may enter Git.
- **No Runtime `INTERNET` Manifest Permission in Offline Policy:** `verification/checks/apk_validator.py` prohibits `android.permission.INTERNET` under the active offline policy. FCT-003 keeps `AndroidManifest.xml` offline (`INTERNET`-free; JVM `MockWebServer` tests require no Android manifest permission) while defining live network enablement (`FCT-004`) as a separate externally authorized gate + isolated policy transition PR.
- **Excluded Hardware & Feature Domains (`specs/mission.md` §3.2–3.3):** ThroughTek camera/video (`CAT-03`, `libIOTCAPIs.so`, `libAVAPIs.so`), legacy XMPP Smack protocol runtime, smart sockets/thermostats/lamps/air-purifiers (`CAT-04`), BLE/SoftAP Wi-Fi provisioning (`FCT-008`), SLAM map rendering (`FCT-005`), and account registration/CAPTCHA UI automation (`get_image_captcha`, `register`).
- **No Modifications to Protected Governance Assets (`POL-INT-01`):** Zero edits to `policies/checks.json`, `verification/run.py`, `verification/checks/`, `verification/selftests/`, or `.github/workflows/`.

---

## 4. Reconciled RE Inconsistencies & Unresolved Details Summary

Following [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md) Section 2.2, primary decompiled Java sources in the frozen RE workspace (`com.yugong.Backome`) refute several claims in `final/*.md` and `final/protocols.json`:

| Reconciled ID | Frozen `final/*.md` / `protocols.json` Claim | Bytecode Ground Truth (`decompiled/jadx/`) | FCT-003 Clean-Room Resolution |
| :--- | :--- | :--- | :--- |
| **`REG-06`** | `/api/v1/auth/login`, Cognito `identity_id`, combined login + Thing list response (`01_MASTER_ARCHITECTURE.md:87`, `03_DATA_MODEL_CATALOG.md:50`, `04_FEATURE_CATALOG.md:47-48`). | Zero occurrences of `cognito` or `/api/v1`. `function/b.java:28-31,326-341` posts JSON `{header, payload:{opt:"login", ...}}` to `https://user.grit-cloud.cn/prod/oauth` (`+86`/`0086`) or `.com/prod/oauth`, returning `IdentityInfo` (`jwt_token`, `api_url`, `wss_url`, `region_name`, `expired_time`). Inventory is a separate `POST` (`opt:"user_thing_list_get"`). | Model `SessionCredentials` strictly on observed `IdentityInfo` fields and fetch inventory separately via `user_thing_list_get`. Do not assume Cognito `identity_id`. |
| **`REG-07`** | `WF-01` claims Form URL Encoded `refresh_token` posted to Cognito refresh endpoint and `p0.p().B(refreshedInfo)` (`04_FEATURE_CATALOG.md:36-39`). | `IdentityInfo.java` has **no `refresh_token`**. `BaseActivity.n1()` (`BaseActivity.java:143-146`) checks `p0.p().t()` (`> 0.8 * expired_time * 1000`, `p0.java:354-369`) and silently re-invokes `opt="login"` using **plaintext password persisted in `SharedPreferences`** (`p0.f43937e`, `LoginActivity.java:140`) + unsalted MD5 (`f.D`, `f.java:136-155`). | Strictly forbid disk persistence of plaintext passwords or MD5 digests (`C-010`, `C-014`). Support single-flight token renewal when an explicit refresh grant is supplied; otherwise transition safely to `SessionState.ReauthenticationRequired`. |
| **`REG-08`** | `GET` requests with `Authorization: Bearer {JWT_TOKEN}` (`02_PROTOCOL_REGISTRY.md:102`, `protocols.json:122`, `04_FEATURE_CATALOG.md:129`). | `function/b.java:216-249,277-282` and `utils/net/d.java:180-199` use **HTTP POST** `application/json` to `api_url` with custom HTTP headers **`"token"`** and **`"region"`**, checking `BaseResponse.code == 1` (`BaseResponse.java:65`). | Default clean-room REST contract sends `POST` JSON `opt` envelopes with `"token"` and `"region"` HTTP headers (no inferred `Bearer` header), validated cleanly against mock HTTP servers. |
| **`REG-09`** | Unverified assumptions about WebSocket auth query params or `Bearer` auth. | `websocket/e.java:159-169` (`q()`) builds OkHttp upgrade requests targeting `wss_url` with HTTP headers **`"token"`** and **`"region"`** (zero query parameters). `utils/net/d.java:50,181` logs headers and request payloads in cleartext. | Transmit WebSocket credentials solely in TLS HTTP upgrade headers (never query params), and strip every secret header/token via `SecretRedactor` (`REQ-AUTH-305`). |
| **`REG-10`** | App-wide SSL socket factory override (`utils/net/d.java:79-101`). | Legacy `d.c()` globally mutates `HttpsURLConnection.setDefaultSSLSocketFactory` using bundled `AmazonRootCA1.cer`. | Prohibit global JVM/process SSL factory mutation; rely on platform TrustManager + manifest `usesCleartextTraffic="false"` per scoped `OkHttpClient`. |

Detailed unresolved server-side items (`UNK-AUTH-01`..`06`, `UNK-INV-01`) and explicit pilot authorization boundaries are cataloged in [requirements.md](requirements.md) Section 2 and [risk-assessment.md](risk-assessment.md).

---

## 5. Governance & Policy Boundary Note (Proposal Only — No Active Policy Edit)

Per `POL-INT-01` and Milestone `FCT-003` authorization boundaries:
- **Reused Active Policy (`policies/checks.json`):** `FCT-003` plans verification entirely under the existing 7 required checks (`spec-hygiene`, `harness-selftests`, `gradle-wrapper-validation`, `android-lint`, `android-unit-tests`, `android-build`, `apk-validation`).
- **Separate Proposed Controls (Not Activated Here):** Before future `FCT-004` hardware/cloud pilot work requires `android.permission.INTERNET` in `AndroidManifest.xml`, an isolated `T4` governance PR will be required to amend `policies/` (`apk_validator.py` permission allowance for `INTERNET` under controlled builds plus recommended static `secret-redaction-scan` check). No policy file is created or edited in `FCT-003`.
