# Change Proposal: FCT-004 — Controlled Connectivity Pilot (Bounded Security Calibration)

- **Change ID:** `FCT-004`
- **Milestone:** Milestone 4 — Controlled Connectivity Pilot
- **Milestone Risk Ceiling:** `T4 (Critical Risk)` per [policies/risk-tiers.md](../../../policies/risk-tiers.md) (`POL-RISK-01`)
- **Status:** `FCT-004A OFFLINE IMPLEMENTATION CANDIDATE (BOUNDED REMEDIATION FIX-401..FIX-405 COMPLETE; ZERO INTERNET PERMISSION, ZERO LIVE NETWORK)`
- **Prerequisite:** [FCT-003](../FCT-003/proposal.md) squash-merged on `main` (`f6f39e7b62175a95c57606c7e3ede8656ccfe058`, tree `ccd37238d19ea157e63cd9d7e402575de6735ee3`, post-merge CI `45656859330` `PASS`; physical-device `TEST-AUTH-312` and Human T4 UAT accepted on `SM-S911B` Android 14 / API 34)
- **Governance Links:** [specs/constitution.md](../../constitution.md) | [specs/roadmap.md](../../roadmap.md) | [specs/tech-stack.md](../../tech-stack.md) | [specs/deployment.md](../../deployment.md) | [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md) | [risk-assessment.md](risk-assessment.md) | [regression-evidence.md](regression-evidence.md)

---

## 1. Problem Statement

`FCT-003` delivered clean-room authentication contracts, `EndpointTrustPolicy` / `UriValidator`, `SessionCoordinator` (`sessionEpoch`), `DeviceInventoryRepository` (`user_thing_list_get`), RE-grounded `DeviceCapabilityClassifier`, generation-fenced `OkHttpWebSocketTransport`, Android Keystore AES-256-GCM storage (`KeystoreAeadTokenStore`), and deny-by-default logging (`DiagnosticLogger` + `SecretRedactor`). Throughout `FCT-003`, runtime execution remained strictly offline (`SimulatorRobotTransport` + `OfflineFixture*`, `ProductionEndpointTrustPolicy` disabled, zero `android.permission.INTERNET`).

Before the project can progress to `FCT-005` (SLAM map ingestion) or `FCT-006` (robot controls) with confidence in real-world server compatibility, an **early, read-only, human-supervised connectivity pilot** is needed to resolve server-side protocol unknowns (`UNK-AUTH-01`..`08`, `UNK-INV-01`) against an isolated test account and physical Laser SLAM robot vacuum — **without** enabling physical robot command dispatch (`send_to_device`), **without** relaxing offline CI isolation, and **without** guessing unverified production endpoints or auth contracts.

---

## 2. Objectives & Bounded Security Conditions (`SEC-401` .. `SEC-406`)

1. **Separated Authorization & Immutable Pilot Context (`SEC-401`):** Separate (a) G3 specification/offline implementation authorization (`FCT-004A`), (b) T4 pilot-build and policy transition authorization (`FCT-004B`), and (c) external live-pilot execution authorization. Require an immutable `PilotAuthorizationContext` (defaulting to disabled, never restored across process restart, and never satisfied by a UI checkbox alone).
2. **Structural Read-Only Network Boundary (`SEC-402`):** Enforce a capability-restricted pilot transport boundary permitting ONLY `login` and `user_thing_list_get` HTTP operations and inbound WebSocket telemetry, rejecting arbitrary HTTP payloads and prohibiting all outbound WebSocket application-level text/binary frames (while preserving RFC 6455 protocol control frames). Isolate offline `SimulatorRobotTransport` commands and treat unexpected live physical robot command attempts as immediate pilot-abort events.
3. **Strict Endpoint Trust & Address Isolation (`SEC-403`):** Resolve FCT-003 immediate response-origin rejection vs FCT-004 `AwaitingOriginConfirmation` by keeping unconfirmed sessions strictly quarantined (never published as `Authenticated`, never persisted, zero token forwarding). Enforce exact auth origin + `/prod/oauth` path, exact approved inventory/WS origins (`https`/`wss`, port `443`, no userinfo/credential query, no redirects, no proxy, platform TLS + hostname verification), and explicit IPv4/IPv6 special-use CIDR blocking including ULA, CGNAT, and IPv4-mapped IPv6.
4. **Bounded Execution Budgets & Non-Blocking Emergency Disconnect (`SEC-404`):** Enforce strict session budgets (`1` login, `0` password retries, `0` CAPTCHA automation, `1` inventory request, `1` initial WS connection, `<= 3` cumulative WS reconnects without reset on open, `15s` HTTP call timeout, `<= 30m` monotonic observation window, `0` physical commands) and a 9-step Emergency Disconnect that revokes network admission immediately without waiting for disk I/O.
5. **Separated Offline vs Pilot Build Architecture (`SEC-405` — Design & Proposal Only):** Preserve the existing `INTERNET`-free offline APK and propose a separate pilot artifact + unified `apk_validator.py --connectivity-policy {offline,pilot}` contract for a future authorized `FCT-004B` transition PR. Zero Gradle/manifest/policy/validator/CI modifications occur in `FCT-004` or `FCT-004A`.
6. **Grounded Protocol Evidence & Ephemeral Credential Hygiene (`SEC-406`):** Preserve the unresolved protocol matrix, classify RE-observed vs clean-room vs fixture-only `AuthEnvelopeConfig` values with exact bytecode references, enforce `CharArray` buffer wiping on submit/cancel/error/screen exit alongside `FLAG_SECURE`, and use allowlisted diagnostics without claiming impossible JVM immutable `String` zeroization.

---

## 3. Scope Boundaries

### 3.1 Phased Delivery Governance (`SEC-401`, `SEC-405`)
| Phase | Authorization Gate | Scope | Network / `INTERNET` |
| :--- | :--- | :--- | :--- |
| **`FCT-004` (This Spec PR)** | G3 Specification Readiness | Bounded specification calibration in `specs/changes/FCT-004/`. | Disabled (`INTERNET`-free) |
| **`FCT-004A` (Offline Pilot Impl)** | G3 + Bounded Offline Implementation Auth | Tasks 1–4 offline components (`PilotModels`, `PilotAuthorizationGate`, `ReadOnlyPilotGuard`, `AuthorizedPilotEndpointTrustPolicy`, `PilotSessionController`, `PilotReadinessSheet`, `PilotSanitizedExporter`), verified via MockWebServer, injected DNS fixtures, and Compose tests. | Disabled (`INTERNET`-free; production endpoints disabled) |
| **`FCT-004B` (Future Build/Policy Transition)** | Separate T4 Policy & Transition Auth (`TRANSITION_AUTHORIZATION`) | Separate `pilot` build variant/artifact, `INTERNET`-only pilot manifest, unified `apk_validator.py --connectivity-policy` enforcement. | Pilot artifact only (`offline` stays `INTERNET`-free) |
| **External Live Pilot Execution** | Signed Pilot Authorization Form + G7/G8 External Auth | Human-supervised read-only probe (`TEST-PILOT-414`) against dedicated test account and isolated `LaserSlam` robot. | Explicit time-boxed window (`<= 30m`) |

### 3.2 Out of Scope & Hard Stop Conditions
- **No Production Code, Dependency, Manifest, or Policy Edits in This Spec PR:** Zero changes to `app/`, `policies/`, `verification/`, or `.github/workflows/`.
- **No `INTERNET`, Live Cloud Requests, Real Credentials, or Physical Robot Commands in `FCT-004A`:** Automated tests run strictly against loopback `MockWebServer` and injected DNS/clock fixtures.
- **No Automated CAPTCHA/Challenge Bypass (`UNK-AUTH-04`) & No Legacy MD5 Fallback (`UNK-AUTH-05`):** Fail closed to `ChallengeRequired` or `ProtocolMismatchAbort`.
- **No Passwordless Refresh Without Verified Server Support (`UNK-AUTH-01`):** Keep `DisabledPasswordlessRefreshStrategy` active.
- **Excluded Hardware Domains:** Cameras/ThroughTek (`CAT-03`), peripherals (`CAT-04`), legacy XMPP (`H0`/`U0`/`e1`), BLE/SoftAP provisioning (`FCT-008`), and SLAM map rendering (`FCT-005`).

---

## 4. Pilot Authorization Contract & Trust Properties (`SEC-401`)

### 4.1 Immutable `PilotAuthorizationContext` Binding
No pilot network admission may be granted unless an immutable `PilotAuthorizationContext` binds all of the following fields and passes `PilotAuthorizationGate` validation:
1. `authorizationId`: Non-blank unique authorization identifier (`"FCT004-PILOT-YYYYMMDD-NN"`), valid for **one bounded pilot run only** (`PilotAuthorizationGate` tracks `consumedAuthorizationIds` and rejects replay/reactivation of previously consumed IDs or activation while cleanup is in-flight/incomplete; production restart continues to require a newly issued external authorization without claiming persistent cross-restart anti-replay storage).
2. **Candidate & APK Identity:** `targetCandidateSha` (40-char hex git SHA), `expectedApplicationId` (`"com.weback.robot"`), `expectedVersionName` (`"0.1.0-alpha"`), and `expectedApkSha256` (64-char hex SHA-256). Offline mock fixture workflows use `PilotEnvironment.OFFLINE_MOCK_FIXTURE` with a distinct non-live gate/controller (`offlineMockPilotController`) incapable of activating live cloud endpoints (`PilotEnvironment.LIVE_PILOT`), and never populate live gateways with fake zero-filled candidate digests.
3. **Authorized Test Account Scope:** `accountScopeType == DEDICATED_ISOLATED_TEST_ACCOUNT`, exact normalized `regionCallingCode` (`trim()`), separate exact `authorizedAccountIdentifier` (`trim()` equality checked before credential serialization/network I/O, redacted in `toString()`), and redacted display `authorizedAccountAlias` (personal/production accounts prohibited; redacted display alias never substitutes for exact account identity).
4. **Exact Target Robot Identity:** `expectedSubTypeFamily == "LaserSlam"` and `expectedTargetThingName` (or pinned redacted tag/alias verified before telemetry hydration).
5. **Permitted Operation Set:** Explicitly restricted to `{"login", "user_thing_list_get", "ws_telemetry_observe"}`.
6. **Approved Endpoint Configuration:** Exact `approvedAuthOrigin` (`https://user.grit-cloud.cn:443` or `https://user.grit-cloud.com:443` + path `/prod/oauth`), optional pinned `pinnedApiOrigin` / `pinnedWssOrigin` (`https`/`wss`, `:443`), and `requireProvenanceVerification = true`.
7. **Read-Only Constraint:** `readOnlyMode = true` AND `physicalRobotCommandsPermitted = false`.
8. **Request Budgets:** `maxLoginAttempts = 1`, `maxPasswordRetries = 0`, `maxCaptchaAutomation = 0`, `maxInventoryRequests = 1`, `maxInitialWsConnections = 1`, `maxWsReconnectAttempts = 3` (cumulative), `httpCallTimeoutMs = 15_000L`, `observationWindowMs <= 1_800_000L`, `maxPhysicalRobotCommands = 0`.
9. **Monotonic Observation Deadline:** `authorizedAtElapsedMs` and `observationDeadlineElapsedMs` anchored to injected `AuthMonotonicClock`.

### 4.2 Lifecycle, Default-Disabled & Trust Limitations
- **Default Disabled & Zero Restart Persistence:** `PilotAuthorizationGate` boots in `Disabled` (`OfflineDefault`) on every launch and test run. Pilot authorization MUST NEVER be persisted to disk or restored after process restart.
- **No UI-Checkbox Substitution:** An in-app UI toggle or consent checkbox alone MUST NEVER satisfy `PilotAuthorizationContext`; external human approval metadata (`approvedByProductOwner`, `approvedByLeadMaintainer`, signed authorization record) is required.
- **Trust Properties & Limitations (No Unnecessary PKI):** `PilotAuthorizationGate` does not introduce client-side PKI/certificate chains. On-device validation enforces fail-closed structural invariants, candidate/APK identity binding, monotonic deadlines, and protection against accidental activation or stale/mismatched sessions. Because a local debug build without remote attestation relies on operator-supplied or build-injected context, external dual human governance at G6/G7 and exact APK SHA-256 verification remain the authoritative trust root preventing unauthorized builds from reaching live networks.

### 4.3 Mandatory Pre-Execution Form Template (Untracked External Record)

```yaml
pilot_authorization_id: "FCT004-PILOT-YYYYMMDD-01"
authorized_by_product_owner: "<Human PO Name & Date>"
authorized_by_lead_maintainer: "<Human Lead Maintainer Name & Date>"
target_candidate_sha: "<40-char Git Commit SHA>"
expected_application_id: "com.weback.robot"
expected_version_name: "0.1.0-alpha"
expected_apk_sha256: "<64-char SHA-256>"
pilot_mode: "READ_ONLY_CONNECTIVITY_ONLY"
read_only_mode: true
physical_robot_commands_permitted: false   # MUST remain false in FCT-004
permitted_operations: ["login", "user_thing_list_get", "ws_telemetry_observe"]
account_scope:
  type: "DEDICATED_ISOLATED_TEST_ACCOUNT"  # Personal/production accounts prohibited
  region_calling_code: "<+86 | +1 | ...>"
  account_alias_tag: "<ALIAS_ONLY>"
target_device:
  expected_sub_type_family: "LaserSlam"
  expected_thing_name_or_tag: "<EXACT_OR_REDACTED_TAG>"
network_and_endpoint_bounds:
  auth_origin: "https://user.grit-cloud.com:443" # or https://user.grit-cloud.cn:443
  auth_path: "/prod/oauth"
  pinned_api_origin: "<Exact https://host:443 or UNPINNED_PROVENANCE_REQUIRED>"
  pinned_wss_origin: "<Exact wss://host:443 or UNPINNED_PROVENANCE_REQUIRED>"
  private_and_special_use_ips_blocked: true
request_budgets:
  max_login_attempts: 1
  max_password_retries: 0
  max_captcha_automation: 0
  max_inventory_requests: 1
  max_initial_ws_connections: 1
  max_ws_reconnect_attempts: 3
  http_call_timeout_ms: 15000
  observation_window_minutes: 30
  max_physical_robot_commands: 0
rollback_target_apk:
  milestone: "FCT-003"
  debug_apk_sha256: "3c933db2160c4407f44b3393dffdd3cf372074e5b06f8b29089ee0408e9fa90e"
```

---

## 5. Separate Offline & Pilot Build Transition Proposal (`SEC-405` — DESIGN & PROPOSAL ONLY)

> **Important:** This section is **DESIGN AND PROPOSAL ONLY**. Neither `FCT-004` nor `FCT-004A` modifies Gradle variants, manifests, `policies/checks.json`, `verification/`, or `.github/workflows/`, nor activates `TRANSITION_AUTHORIZATION`.

### 5.1 Build Separation & Compatibility Evaluation with Seven-Check Policy
The current `policies/checks.json` (`e688b6fef22290281facf91d26f42ae53ff527ca7f6b599a750d24d66873985e`) executes fixed Gradle tasks: `./gradlew lintDebug --no-daemon`, `testDebugUnitTest`, `./gradlew assembleDebug --no-daemon`, and `python3 verification/checks/apk_validator.py` (default `--apk app/build/outputs/apk/debug/app-debug.apk`).

1. **Why a dedicated `pilot` buildType (or explicit `offline`/`pilot` flavor update) matters:** Adding a Gradle `productFlavor` dimension splits `debug` into `offlineDebug` and `pilotDebug`, meaning `./gradlew assembleDebug` would compile *both* variants and move the APK path away from `app/build/outputs/apk/debug/app-debug.apk`. In contrast, adding a dedicated `pilot` `buildType` (configured via `initWith(getByName("debug"))`, `matchingFallbacks += listOf("debug")`, and sourceSet `app/src/pilot/AndroidManifest.xml`) keeps `assembleDebug`, `lintDebug`, `testDebugUnitTest`, and `app/build/outputs/apk/debug/app-debug.apk` 100% intact for the `INTERNET`-free offline APK while isolating the pilot artifact into `assemblePilot` -> `app/build/outputs/apk/pilot/app-pilot.apk` (or `pilotDebug` if `offline` & `pilot` flavors are adopted alongside a governed `policies/checks.json` task update).
2. **Preferred FCT-004B Artifact Separation:**
   - **Affected Manifests:**
     - `app/src/main/AndroidManifest.xml`: Remains unmodified and strictly `INTERNET`-free (`allowBackup="false"`, `usesCleartextTraffic="false"`, `networkSecurityConfig="@xml/network_security_config"`).
     - `app/src/pilot/AndroidManifest.xml` (new in FCT-004B only): Adds strictly `<uses-permission android:name="android.permission.INTERNET" />` via manifest merger on top of `src/main/AndroidManifest.xml`.
   - **Exact Build Tasks & Artifact Paths:**
     - Offline artifact (default): `./gradlew lintDebug testDebugUnitTest assembleDebug --no-daemon` -> `app/build/outputs/apk/debug/app-debug.apk` (with `.sha256` sidecar).
     - Pilot artifact (FCT-004B authorized): `./gradlew lintPilot testPilotUnitTest assemblePilot --no-daemon` -> `app/build/outputs/apk/pilot/app-pilot.apk` (with `.sha256` sidecar).
   - **Unified `apk_validator.py` CLI Contract:**
     - Add one explicit mode option: `--connectivity-policy` with choices `offline` (default, deny-by-default) and `pilot`.
     - Offline validator invocation (default in `policies/checks.json`): `python3 verification/checks/apk_validator.py --apk app/build/outputs/apk/debug/app-debug.apk --connectivity-policy offline`.
     - Pilot validator invocation (in FCT-004B pilot check): `python3 verification/checks/apk_validator.py --apk app/build/outputs/apk/pilot/app-pilot.apk --connectivity-policy pilot`.
   - **Exact APK Permission Allowlists per Artifact:**
     - `--connectivity-policy offline`: Allows ONLY `{com.weback.robot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION}`. `android.permission.INTERNET` and all other permissions MUST fail validation.
     - `--connectivity-policy pilot`: Allows ONLY `{com.weback.robot.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, android.permission.INTERNET}`. All location (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`), Bluetooth (`BLUETOOTH*`), Wi-Fi (`*_WIFI_STATE`), `CHANGE_NETWORK_STATE`, and any other external permissions remain strictly prohibited.
   - **Required Validator & Negative Tests (in FCT-004B `verification/selftests/test_apk_validator.py`):**
     - Negative test proving `--connectivity-policy offline` (and omitted default flag) rejects any APK containing `android.permission.INTERNET`.
     - Negative test proving `--connectivity-policy pilot` rejects any APK containing `ACCESS_FINE_LOCATION`, `BLUETOOTH_SCAN`, `ACCESS_WIFI_STATE`, `CHANGE_NETWORK_STATE`, or unknown custom permissions.
     - Preservation checks proving `usesCleartextTraffic="false"`, `allowBackup="false"`, `networkSecurityConfig`, `apksigner` signature verification, `PROHIBITED_DEX_PACKAGES`, and `PROHIBITED_BINARY_PATTERNS` remain strictly non-waivable in both `offline` and `pilot` modes.
   - **Candidate-Specific Transition Authorization & Retirement Procedure:**
     - FCT-004B policy edits (`policies/checks.json`, `verification/checks/apk_validator.py`) MUST be authorized via a candidate-specific `TRANSITION_AUTHORIZATION` JSON payload bound to exact `candidate_sha`, `tree_sha`, `base_sha`, `expected_policy_sha256`, and dual human review (`PO` + `Lead Maintainer`):
       ```json
       {
         "change_id": "FCT-004B",
         "candidate_sha": "<40-char-commit-sha>",
         "tree_sha": "<40-char-tree-sha>",
         "base_sha": "<40-char-main-sha>",
         "expected_policy_sha256": "<64-char-new-checks-json-sha256>",
         "approved_by_product_owner": "<Human PO Name & Date>",
         "approved_by_lead_maintainer": "<Human Lead Maintainer Name & Date>"
       }
       ```
     - Post-merge procedure: Immediately after FCT-004B merges to `main`, compute the new canonical `policies/checks.json` SHA-256, update GitHub repository variable `APPROVED_POLICY_SHA256`, clear/retire the one-shot `TRANSITION_AUTHORIZATION` variable so stale transition grants cannot be replayed, and verify post-merge `push` CI passes against the new canonical policy hash.
