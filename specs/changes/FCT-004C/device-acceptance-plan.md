# FCT-004C Physical-Device Acceptance Plan & First-Live-Attempt Operating Procedure (`NOT_RUN`)

- **Change ID:** `FCT-004C`
- **Risk Tier:** `T4 (Critical Risk)`
- **Offline Device Acceptance Oracle:** `TEST-PILOT-469` (`NOT_RUN` — mandatory pre-live checklist before any live cloud attempt)
- **Live Cloud Auth + Single Inventory Oracle:** `TEST-PILOT-414` (`NOT_RUN` — strictly separated; requires completed offline device acceptance + external approval)
- **FCT-004B Remaining Limitation:** In FCT-004B, deterministic JVM monotonic expiry verification (`TEST-PILOT-455`) **PASSED**, whereas physical-device monotonic deadline expiry (`TEST-PILOT-460`) remained **`NOT_RUN`**. FCT-004C mandates actual offline physical-device monotonic deadline expiry (`SystemClock.elapsedRealtime()`) below and **prohibits** substituting cold process restart for actual expiry.

---

## 1. Mandatory Pre-Live Offline Physical-Device Checklist (`TEST-PILOT-469` — `NOT_RUN`)

### Preconditions
1. Verify SHA-256 checksums of `weback-fct004c-offline-debug.apk`, `weback-fct004c-pilot.apk`, and `weback-fct004c-pilot-androidTest.apk` before transfer.
2. Explicit human authorization for physical Android >= 12 (`minSdk = 31`) device execution.
3. Zero production DNS queries, zero real credentials, and zero live cloud connections during Steps 1–9.

| Step | Verification Area | Human-Operated Commands / Procedure | Independent Acceptance Oracle |
| :--- | :--- | :--- | :--- |
| **1** | **Side-by-Side Install & Package Separation** | `adb install weback-fct004c-offline-debug.apk`<br>`adb install weback-fct004c-pilot.apk`<br>`adb shell dumpsys package com.weback.robot \| grep INTERNET \|\| echo NO_INTERNET`<br>`adb shell dumpsys package com.weback.robot.pilot \| grep permission` | Both APKs install side-by-side; `com.weback.robot` has **zero** `INTERNET` permission; `com.weback.robot.pilot` has strictly `android.permission.INTERNET` + `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. |
| **2** | **Offline Build Live Rejection** | Launch `com.weback.robot`, open **Pilot Readiness (Offline / Mock)**. | Live Authorization Review button is absent; only offline simulator and synthetic mock pilot are available (`OFFLINE_DEFAULT`). |
| **3** | **Default Offline Startup & Zero Background Network** | Capture `PILOT_UID`, check `dumpsys netstats detail` before and 30s after launching `com.weback.robot.pilot` without starting a pilot run. | Boots in `OFFLINE_DEFAULT`; `dumpsys netstats` shows `rb=0 rp=0 tb=0 tp=0`, zero active sockets in `/proc/net/*` for `$PILOT_UID`. |
| **4** | **Hardware Keystore (`KeystoreHardwareSecurityLevelTest`)** | `adb install -t weback-fct004c-pilot-androidTest.apk`<br>`adb shell am instrument -w -e class com.weback.robot.security.KeystoreHardwareSecurityLevelTest com.weback.robot.pilot.test/androidx.test.runner.AndroidJUnitRunner` | `OK (1 test)`, exit code `0`: TEE/StrongBox AES-256-GCM key, tamper rejection, missing-key rejection, isolated cleanup. |
| **5** | **Preserved Simulator & Mock Workflow** | Exercise `Clean`, `Pause`, `Resume`, `Dock` and **Start Offline Mock Pilot** in both APKs. | Simulator and mock pilot operate identically with zero network traffic (`dumpsys netstats` remains `0`). |
| **6** | **FLAG_SECURE & Placeholder Rejection (Offline)** | In `com.weback.robot.pilot`, open **Review Live Pilot Authorization**, submit blank/placeholder approval -> observe rejection; enter test-only loopback/synthetic context (no real network) to enter `AuthorizedReady` and attempt screenshot (`adb shell screencap` or system screenshot). | Placeholder/incomplete authorization rejected in `OFFLINE_DEFAULT`; OS screenshot capture blocked (`FLAG_SECURE`) during credential entry; Emergency Disconnect wipes buffers and returns to `OFFLINE_DEFAULT`. |
| **7** | **Mandatory Actual Physical-Device Monotonic Deadline Expiry (`SystemClock.elapsedRealtime()`)** | Activate a short (e.g., 1-minute) offline-bounded window (mock pilot or isolated loopback test context with zero cloud calls), lock device screen / allow device to enter doze/sleep past the deadline (1+ min on `SystemClock.elapsedRealtime()`), wake device without force-stopping app, and observe `PilotReadinessSheet`. | **Cold restart MUST NOT substitute for this step.** Upon monotonic `SystemClock.elapsedRealtime()` reaching deadline (including across screen-off/doze), active pilot authorization revokes automatically, storage clears, and state returns to `OFFLINE_DEFAULT` with `OBSERVATION_WINDOW_EXPIRED`. |
| **8** | **Cold Restart Non-Persistence** | Force-stop and relaunch `com.weback.robot.pilot` (`adb shell am force-stop ...`). | Always boots in `OFFLINE_DEFAULT` (`isAuthorized == false`). |
| **9** | **Rollback Verification** | Only with separate rollback authorization: uninstall `com.weback.robot.pilot` + `.test` and verify `com.weback.robot`. | Offline app remains intact, `INTERNET`-free, and fully functional. |

---

## 2. Minimal First-Live-Attempt Operating Procedure (`TEST-PILOT-414` — `NOT_RUN`)

**Do NOT execute until `TEST-PILOT-469` above passes AND an external countersigned [external-pilot-authorization.md](external-pilot-authorization.md) is approved (`product_owner_approved: true`, `lead_maintainer_approved: true`).**

1. **Pre-Flight:** Verify `com.weback.robot.pilot` APK SHA-256 against the countersigned authorization form; attach isolated dedicated test account credentials (never production credentials) and target `LaserSlam` robot `thing_name`.
2. **Launch & Review:** Launch `com.weback.robot.pilot` (confirm `OFFLINE_DEFAULT`), open **Pilot Readiness (Offline / Live Review)** -> **Review Live Pilot Authorization**, enter exact countersigned authorization parameters (`<= 15` minutes window, `https://user.grit-cloud.com:443` or `.cn:443`, `pinnedApiOrigin` / `pinnedWssOrigin` null unless independently pre-approved), tap **Validate & Activate**.
3. **Human Credential Entry:** Verify `FLAG_SECURE` active; human operator enters calling code, dedicated test account identifier, and password directly; tap **Submit Probe** (dispatches **at most 1** `POST /prod/oauth` with `opt="login"`, UTF-8 over TLS, `<= 15s` timeout, `0` retries).
4. **Outcome Branching:**
   - **If Unapproved Dynamic Origins Returned:** App retains ONLY sanitized `ObservedOriginSummary` (`apiOrigin`, `wssOrigin`), discards untrusted session/token, revokes authorization, cleans up, and returns to `OFFLINE_DEFAULT` (`UNTRUSTED_ORIGIN_REJECTED`). Record the sanitized endpoint observation for offline provenance review; **stop** (do not retry).
   - **If Challenge/Error Returned:** App aborts fail-closed (`CHALLENGE_REQUIRED` / `PROTOCOL_MISMATCH_ABORT` / `AUTH_REJECTED`), revokes authorization, and returns to `OFFLINE_DEFAULT`; **stop** (do not retry).
   - **If Pre-Approved Origins Returned:** App displays sanitized authentication result (`AUTHENTICATED_INVENTORY_READY`). Tap **Fetch Target Inventory (Single Read-Only Request)** (dispatches **at most 1** `POST` `user_thing_list_get`, **0** WebSocket connections). Inspect sanitized target robot capability summary (`INVENTORY_VERIFIED_READ_ONLY`).
5. **Mandatory Teardown:** Tap **Emergency Disconnect & Wipe** immediately after recording sanitized observation; confirm return to `OFFLINE_DEFAULT`, `storageCleared=true`, and zero leaked credentials.
