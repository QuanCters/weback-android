# FCT-004C Physical-Device Acceptance Plan & First-Live-Attempt Operating Procedure (`NOT_RUN`)

- **Change ID:** `FCT-004C`
- **Risk Tier:** `T4 (Critical Risk)`
- **Offline Device Acceptance Oracle:** `TEST-PILOT-469` (`NOT_RUN` — mandatory pre-live checklist before any live cloud attempt)
- **Live Cloud Auth + Single Inventory Oracle:** `TEST-PILOT-414` (`NOT_RUN` — strictly separated; requires completed `TEST-PILOT-469` + external countersigned authorization)
- **Evidence Boundary:** Part A below verifies the **unmodified production builds** (`com.weback.robot` and `com.weback.robot.pilot`) without fabricating `offline_device_checklist_passed` or `physical_device_monotonic_expiry_verified` flags. Part B below uses **pilot-targeted `androidTest` instrumentation** with isolated synthetic test dependencies (`KeystoreHardwareSecurityLevelTest` and `PilotDeviceAcceptanceInstrumentationTest`), which counts as isolated instrumentation evidence rather than unmodified production live-flow execution.

---

## 1. Mandatory Pre-Live Offline Physical-Device Checklist (`TEST-PILOT-469` — `NOT_RUN`)

### Preconditions & Evidence Hygiene
1. Verify SHA-256 checksums of `weback-fct004c-offline-debug.apk`, `weback-fct004c-pilot.apk`, and `weback-fct004c-pilot-androidTest.apk` against `SHA256SUMS.txt` before transfer.
2. Explicit human authorization is required before any physical Android >= 12 (`minSdk = 31`) device execution.
3. Zero production DNS queries, zero real credentials, zero real account identifiers, and zero live cloud connections during all `TEST-PILOT-469` steps (both Part A and Part B).
4. **Screenshot & Public Evidence Protection:** Never capture or export screenshots containing actual account identifiers, target `thing_name`, or passwords into public evidence. During `FLAG_SECURE`-protected screens (`Review Live Pilot Authorization` and credential entry), OS screenshot capture must be blocked/blacked out (`FLAG_SECURE`).

### Part A — Unmodified Production APK Checks (No Test Dependencies, No Fabricated Readiness Flags)

| Step | Verification Area | Human-Operated Commands / Procedure | Independent Acceptance Oracle (Unmodified Production Evidence) |
| :--- | :--- | :--- | :--- |
| **A1** | **Side-by-Side Installation & Package Separation** | `adb install weback-fct004c-offline-debug.apk`<br>`adb install weback-fct004c-pilot.apk`<br>`adb shell dumpsys package com.weback.robot \| grep INTERNET \|\| echo NO_INTERNET`<br>`adb shell dumpsys package com.weback.robot.pilot \| grep permission` | Both APKs install side-by-side; `com.weback.robot` has **zero** `INTERNET` permission; `com.weback.robot.pilot` declares strictly `android.permission.INTERNET` + `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. |
| **A2** | **Offline Build Live-Activation Rejection** | Launch `com.weback.robot`, open **Pilot Readiness (Offline / Mock)**. | **Review Live Pilot Authorization** action is absent; only offline simulator and synthetic mock pilot are available (`OFFLINE_DEFAULT`). |
| **A3** | **Default-Disabled Pilot Startup** | Capture `PILOT_UID`, check `dumpsys netstats detail` before and 30s after launching `com.weback.robot.pilot` without starting a pilot run. | Boots in `OFFLINE_DEFAULT` (`Authorization ID: DISABLED (OFFLINE)`); `dumpsys netstats` shows `rb=0 rp=0 tb=0 tp=0` and zero active sockets for `$PILOT_UID`. |
| **A4** | **Unmodified Production Rejection Without Provisioned Approval** | In `com.weback.robot.pilot`, tap **Review Live Pilot Authorization** with app-private `no_backup/pilot_external_auth/` empty (do **not** fabricate `offline_device_checklist_passed` or `physical_device_monotonic_expiry_verified`). | Loader reports missing authorization record and blocks activation; **Verify & Activate** fails closed (`OFFLINE_DEFAULT`, zero network traffic). |
| **A5** | **Preserved Simulator & Mock Pilot (30-Min Default)** | Exercise `Clean`, `Pause`, `Resume`, `Dock` and **Start Offline Mock Pilot (Synthetic Fixture)** in both APKs (`mock-pilot@offline.invalid`). Confirm mock UI displays `Synthetic Credential Entry (Offline Mock Fixture, No Network)` and `Synthetic Password`. | Simulator and 30-min synthetic mock pilot operate identically with zero external network traffic (`dumpsys netstats` remains `0`). |
| **A6** | **Short Offline Mock Deadline Expiry Across Screen-Off & Resume** | Tap **Start 1-Min Offline Mock Expiry Test (No Network)** (timer `01:00`, `OFFLINE_MOCK_FIXTURE`, zero network). Turn screen off for > 65 seconds on `SystemClock.elapsedRealtime()`, then wake and unlock device without force-stopping app. | **Cold restart MUST NOT substitute for this step.** On resume after `elapsedRealtime()` crosses 60s, mock authorization expires automatically, clears storage, returns to `OFFLINE_DEFAULT`, and displays `Last Abort: OBSERVATION_WINDOW_EXPIRED` (do not claim verified kernel deep sleep unless separately established via power/doze telemetry). |
| **A7** | **Process Restart Non-Persistence & Rollback Readiness** | Execute `adb shell am force-stop com.weback.robot.pilot`, relaunch, confirm `OFFLINE_DEFAULT`; then (with rollback authorization) uninstall `com.weback.robot.pilot` + `.test` and verify `com.weback.robot`. | Relaunch always boots in `OFFLINE_DEFAULT`; uninstall leaves offline `com.weback.robot` intact and `INTERNET`-free. |

### Part B — Hermetic Pilot-Targeted Instrumentation Checks (Isolated Test Dependencies)

> [!NOTE]
> Part B installs `weback-fct004c-pilot-androidTest.apk` against `com.weback.robot.pilot` and uses isolated Keystore aliases / injected test hooks (`InMemoryProvisionedPilotAuthorizationStore`, loopback-only `AuthorizedPilotEndpointTrustPolicy`) with zero production DNS or live endpoints.

| Step | Instrumentation Class & Method | Execution Command | Independent Acceptance Oracle (Instrumentation Evidence) |
| :--- | :--- | :--- | :--- |
| **B1** | `KeystoreHardwareSecurityLevelTest` | `adb install -t weback-fct004c-pilot-androidTest.apk`<br>`adb shell am instrument -w -e class com.weback.robot.security.KeystoreHardwareSecurityLevelTest com.weback.robot.pilot.test/androidx.test.runner.AndroidJUnitRunner` | `OK (1 test)`: TEE/StrongBox AES-256-GCM key, tamper rejection, missing-key rejection, isolated cleanup. |
| **B2** | `PilotDeviceAcceptanceInstrumentationTest#unmodifiedProductionContainerVerifiesInstalledApkAndFailsClosedWithoutFakeReadinessFlags` | `adb shell am instrument -w -e class com.weback.robot.pilot.PilotDeviceAcceptanceInstrumentationTest com.weback.robot.pilot.test/androidx.test.runner.AndroidJUnitRunner` | Unmodified `DefaultAppContainer` computes real installed `sourceDir` APK SHA-256 (`supported=true`) and rejects missing, malformed, and unapproved records (`offline_device_checklist_passed=false`, `physical_device_monotonic_expiry_verified=false`) with zero network and gate disabled. |
| **B3** | `PilotDeviceAcceptanceInstrumentationTest#hermeticLiveUiVerifiesLabelsFlagSecureBackRecreationAndCloseRevocation` | Same `am instrument` invocation as **B2**. | With isolated container test hooks only, verifies Mock vs Live credential labels, `FLAG_SECURE` active during review & `AuthorizedReady`, Back navigation blocked during active live session, `ActivityScenario.recreate()` live UI ownership preservation, and Close revocation + cleanup. |

---

## 2. Minimal First-Live-Attempt Operating Procedure (`TEST-PILOT-414` — `NOT_RUN`)

**Do NOT execute until `TEST-PILOT-469` (all Part A + Part B checks) PASS AND a separate external countersigned [external-pilot-authorization.md](external-pilot-authorization.md) is approved (`product_owner_approved: true`, `lead_maintainer_approved: true`, `offline_device_checklist_passed: true`, `physical_device_monotonic_expiry_verified: true`).**

1. **Out-of-Band Provisioning:** Verify `com.weback.robot.pilot` APK SHA-256 against `SHA256SUMS.txt` and the external approval record (`verified_pilot_apk_sha256`). For the initial authentication-only observation, set `permitted_operations: ["login"]`, `max_inventory_requests: 0`, `pinned_api_origin: null`, `pinned_wss_origin: null`, and `endpoint_provenance_ref: null`. Provision `authorized_pilot_record.json` and `authorized_pilot_record.sha256` (containing NO password) into app-private `no_backup/pilot_external_auth/` via `adb shell run-as com.weback.robot.pilot`.
2. **Launch & Review:** Launch `com.weback.robot.pilot` (confirm `OFFLINE_DEFAULT`), open **Pilot Readiness (Offline / Live Review)** -> **Review Live Pilot Authorization**, confirm `FLAG_SECURE` active, inspect the read-only provisioned scope (showing exact approved operations and budgets), enter the out-of-band 64-hex `record_sha256` from the external approval ticket, and tap **Verify & Activate** (durably consumes authorization ID and digest before enabling live gate).
3. **Human Credential Entry:** Verify **Real-Server Authentication (Ephemeral, Never Persisted)** UI and `FLAG_SECURE` active; human operator enters authorized calling code, dedicated isolated test account identifier, and password directly; tap **Authenticate Live Account (1 Attempt)** (dispatches **at most 1** `POST /prod/oauth` with `opt="login"`, `api_version="6.1.7"`, `app_name="WeBack"`, `client_id="yugong_app"`, `language="en"`, UTF-8 over TLS, `<= 15s` timeout, `0` retries).
4. **Outcome Branching:**
   - **If Unapproved Dynamic Origins Returned:** App retains ONLY sanitized `ObservedOriginSummary` (`apiOrigin`, `wssOrigin`), discards untrusted session/token, revokes authorization, initiates cleanup, and returns to `OFFLINE_DEFAULT` (`UNTRUSTED_ORIGIN_REJECTED`) with **zero** subsequent network requests. Record the sanitized endpoint observation for offline provenance review; **stop** (do not retry).
   - **If Challenge/Error Returned:** App aborts fail-closed (`CHALLENGE_REQUIRED` / `PROTOCOL_MISMATCH_ABORT` / `AUTH_REJECTED`), revokes authorization, and returns to `OFFLINE_DEFAULT`; **stop** (do not retry).
   - **If Independently Pre-Approved Pinned Origins Returned Under Authentication-Only Scope:** Inventory remains disabled (`USER_THING_LIST_GET` absent, `max_inventory_requests = 0`); app shows `Authentication-only authorization complete (inventory not permitted)` and operator taps **Emergency Disconnect & Wipe**.
   - **If Separately Approved Authentication-Plus-Inventory Run (`login` + `user_thing_list_get`, `max_inventory_requests = 1`):** Tap **Fetch Target Inventory (Single Read-Only Request)** (dispatches **at most 1** `POST` `user_thing_list_get`, **0** WebSocket connections) and inspect sanitized target summary (`INVENTORY_VERIFIED_READ_ONLY`).
5. **Mandatory Teardown:** Tap **Emergency Disconnect & Wipe** immediately after recording sanitized observation; confirm return to `OFFLINE_DEFAULT`, `storageCleared=true`, and zero leaked credentials.
