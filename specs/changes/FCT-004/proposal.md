# Change Proposal: FCT-004 — Controlled Connectivity Pilot (Read-Only Spec Baseline)

- **Change ID:** `FCT-004`
- **Milestone:** Milestone 4 — Controlled Connectivity Pilot
- **Milestone Risk Ceiling:** `T4 (Critical Risk)` per [policies/risk-tiers.md](../../../policies/risk-tiers.md) (`POL-RISK-01`)
- **Status:** `G0–G3 SPECIFICATION CANDIDATE (STOP AT GATE G3; ZERO IMPLEMENTATION CODE, ZERO LIVE NETWORK)`
- **Prerequisite:** [FCT-003](../FCT-003/proposal.md) squash-merged on `main` (`f6f39e7b62175a95c57606c7e3ede8656ccfe058`, tree `ccd37238d19ea157e63cd9d7e402575de6735ee3`, post-merge CI `45656859330` `PASS`; physical-device `TEST-AUTH-312` and Human T4 UAT accepted on `SM-S911B` Android 14 / API 34)
- **Governance Links:** [specs/constitution.md](../../constitution.md) | [specs/roadmap.md](../../roadmap.md) | [specs/tech-stack.md](../../tech-stack.md) | [specs/deployment.md](../../deployment.md) | [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md) | [risk-assessment.md](risk-assessment.md)

---

## 1. Problem Statement

`FCT-003` delivered clean-room authentication contracts, `EndpointTrustPolicy` / `UriValidator`, `SessionCoordinator` (`sessionEpoch`), `DeviceInventoryRepository` (`user_thing_list_get`), RE-grounded `DeviceCapabilityClassifier`, generation-fenced `OkHttpWebSocketTransport`, Android Keystore AES-256-GCM storage (`KeystoreAeadTokenStore`), and deny-by-default logging (`DiagnosticLogger` + `SecretRedactor`). Throughout `FCT-003`, runtime execution remained strictly offline (`SimulatorRobotTransport` + `OfflineFixture*`, `ProductionEndpointTrustPolicy` disabled, zero `android.permission.INTERNET`).

Before the project can progress to `FCT-005` (SLAM map ingestion) or `FCT-006` (robot controls) with confidence in real-world server compatibility, an **early, read-only, human-supervised connectivity pilot** is needed to resolve server-side protocol unknowns (`UNK-AUTH-01`..`08`, `UNK-INV-01`) against an isolated test account and physical Laser SLAM robot vacuum — **without** enabling physical robot command dispatch (`send_to_device`), **without** relaxing offline CI isolation, and **without** guessing unverified production endpoints or auth contracts.

---

## 2. Objectives

1. **Enforce Read-Only Pilot Priority (`C-006`, `C-013`):** Restrict `FCT-004` live connectivity strictly to read-only operations (`login`, `user_thing_list_get`, `sub_type` capability verification, and read-only `thing_status_update` WebSocket telemetry reception). Physical robot commands (`Clean`, `Pause`, `Resume`, `Dock` over live cloud) remain **prohibited** until a separate post-pilot command authorization gate.
2. **Require Explicit External Authorization & Exact Endpoint Trust:** Define a mandatory **Pilot Authorization Form** and an opt-in, default-disabled `AuthorizedPilotEndpointTrustPolicy` that binds auth calls to the exact RE-grounded origins (`https://user.grit-cloud.cn/prod/oauth`, `https://user.grit-cloud.com/prod/oauth`) and validates dynamic `api_url` / `wss_url` origins against operator-pinned exact origins or explicit human pre-flight confirmation (zero wildcard host inference, zero private/RFC1918/metadata IP access).
3. **Resolve Outstanding Protocol Unknowns Safely:** Define deterministic pre-pilot probe and fail-closed abort rules for `UNK-AUTH-01`..`08` and `UNK-INV-01` (including strict CAPTCHA non-bypass, UTF-8 TLS password verification without silent MD5 fallback, TLS 1.2/1.3 system CA verification, and WebSocket close/keepalive observation).
4. **Establish Secure Credential Entry, Bounded Retries, Kill Switch & Rollback:** Specify an ephemeral password entry surface (`CharArray` wiping, zero password persistence), bounded WebSocket retries, one-tap **Emergency Disconnect & Wipe**, sanitized pilot log export, and a governed `T4` `INTERNET` permission + `apk_validator.py` policy transition proposal (not activated in this spec PR).

---

## 3. Scope Boundaries

### 3.1 In Scope (Specification-Only Candidate)
- Specification documents in `specs/changes/FCT-004/` (`proposal.md`, `requirements.md`, `design.md`, `validation.md`, `tasks.md`, `traceability.json`, `risk-assessment.md`).
- Target post-G3 implementation architecture extending `com.weback.robot.core`, `domain`, `security`, `protocol`, `app/session`, and `ui` while preserving offline `SimulatorRobotTransport` as the default boot state.
- Governed proposal for a separate future `T4` policy/manifest transition enabling `android.permission.INTERNET` strictly for authorized pilot builds.

### 3.2 Out of Scope & Hard Stop Conditions
- **No Production Code, Dependency, Manifest, or Policy Edits in This PR:** Zero changes to `app/`, `policies/`, `verification/`, or `.github/workflows/`.
- **No Live Cloud Requests, Real Credentials, or Physical Robot Commands:** Zero real logins, zero production credentials, zero `send_to_device` cloud commands, zero public release.
- **No Automated CAPTCHA/Challenge Bypass (`UNK-AUTH-04`):** Any login challenge must fail closed to `AuthFailureReason.ChallengeRequired` and halt.
- **No Legacy MD5 Silent Fallback (`UNK-AUTH-05`):** If Grit Cloud rejects UTF-8 TLS passwords, the pilot aborts and records the finding for a separate Lead Maintainer security decision.
- **Excluded Hardware Domains:** Cameras/ThroughTek (`CAT-03`), peripherals (`CAT-04`), legacy XMPP (`H0`/`U0`/`e1`), BLE/SoftAP provisioning (`FCT-008`), and SLAM map rendering (`FCT-005`).

---

## 4. Pilot Authorization Form (Mandatory Pre-Execution Template)

No live network connectivity may be enabled or executed until all fields below are signed by **both** the Product Owner and Lead Maintainer in an untracked local/external authorization record (never committed with real credentials or device serials):

```yaml
pilot_authorization_id: "FCT004-PILOT-YYYYMMDD-01"
authorized_by_product_owner: "<Human PO Name & Date>"
authorized_by_lead_maintainer: "<Human Lead Maintainer Name & Date>"
target_candidate_sha: "<Exact Git Commit SHA>"
pilot_mode: "READ_ONLY_CONNECTIVITY_ONLY"
physical_robot_commands_permitted: false   # MUST remain false in FCT-004
account_scope:
  type: "DEDICATED_ISOLATED_TEST_ACCOUNT"  # Primary personal/production accounts prohibited
  region_calling_code: "<+86 | +1 | ...>"
target_device:
  expected_sub_type_family: "LaserSlam (P0)"
  thing_name_redacted_tag: "<ALIAS_ONLY>"
network_and_endpoint_bounds:
  auth_origin: "https://user.grit-cloud.com" # or https://user.grit-cloud.cn
  pinned_api_origin: "<Exact https://host:443 or HUMAN_PREFLIGHT_CONFIRM>"
  pinned_wss_origin: "<Exact wss://host:443 or HUMAN_PREFLIGHT_CONFIRM>"
  private_and_loopback_ips_blocked: true
  observation_window_minutes: 30
rollback_target_apk:
  milestone: "FCT-003"
  debug_apk_sha256: "3c933db2160c4407f44b3393dffdd3cf372074e5b06f8b29089ee0408e9fa90e"
```

---

## 5. `INTERNET` Permission & APK Policy Transition Proposal (Deferred Beyond Spec PR)

1. **Current State (Preserved Here):** `policies/checks.json` (`e688b6fef22290281facf91d26f42ae53ff527ca7f6b599a750d24d66873985e`) runs `apk_validator.py`, which fails closed if `android.permission.INTERNET` appears in `AndroidManifest.xml`.
2. **Proposed Governed Transition (Isolated `T4` Implementation/Policy PR Only):**
   - Parameterize `apk_validator.py` with an explicit `--connectivity-policy` mode (default `offline` prohibiting `INTERNET`; `pilot` permitting ONLY `android.permission.INTERNET` alongside `com.weback.robot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` while keeping all location, Bluetooth, Wi-Fi, and legacy vendor permissions strictly prohibited).
   - Keep `usesCleartextTraffic="false"`, `allowBackup="false"`, `networkSecurityConfig`, `PROHIBITED_BINARY_PATTERNS`, and `PROHIBITED_DEX_PACKAGES` strictly enforced and non-waivable.
   - Activate via `TRANSITION_AUTHORIZATION` (`verification/checks/transition.py`) signed off under `T4` dual human review (Product Owner + Lead Maintainer).
