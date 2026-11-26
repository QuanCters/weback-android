# FCT-004B Physical-Device Acceptance Plan (`NOT_RUN` — Human-Operated Gate)

- **Change ID:** `FCT-004B`
- **Status:** `PREPARED_AWAITING_EXPLICIT_HUMAN_AUTHORIZATION` (`TEST-PILOT-414` & `TEST-PILOT-460` remain `NOT_RUN`)
- **Preconditions:** Exact candidate APKs (`app-debug.apk` and `app-pilot.apk`) verified against `apk_validator.py --connectivity-policy offline` and `--connectivity-policy pilot`, SHA-256 sidecars matched, and explicit physical-device authorization granted.
- **Hard Stop:** Zero ADB commands or physical-device executions are permitted without explicit human authorization.

---

## 1. Ten-Point Physical-Device Verification Checklist

| Step | Verification Area | Procedure (Human-Operated) | Expected Passing Evidence |
| :--- | :--- | :--- | :--- |
| **1** | **Side-by-Side Installation** | Install offline `app-debug.apk` (`com.weback.robot`) and pilot `app-pilot.apk` (`com.weback.robot.pilot`) onto the same Android >= 12 device via `adb install`. | Both installs succeed with zero package, permission (`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`), or provider authority (`androidx-startup`) conflicts. |
| **2** | **Application Identities & Labeling** | Verify launcher icons/labels and `pm list packages | grep weback`. | Both packages appear (`com.weback.robot` labeled `WeBack Robot Control`; `com.weback.robot.pilot` labeled `WeBack Robot Pilot (Read-Only)`). |
| **3** | **Manifest Permission Separation** | Inspect `dumpsys package com.weback.robot` and `dumpsys package com.weback.robot.pilot`. | `com.weback.robot` grants zero `INTERNET` permission; `com.weback.robot.pilot` grants strictly `android.permission.INTERNET` (plus internal receiver permission) and zero location/BT/Wi-Fi permissions. |
| **4** | **Zero Production Networking at Startup** | Launch `com.weback.robot.pilot` cleanly and observe `OFFLINE` badge + local packet/DNS monitor (`logcat`/network inspector). | Boots in `OfflineDefault`; zero DNS queries, zero HTTP/WS connections, zero automatic login attempts. |
| **5** | **Separate Application Storage** | Inspect app data directories (`run-as com.weback.robot` vs `run-as com.weback.robot.pilot` `no_backup/auth_session_store`). | Each package owns an isolated OS UID and storage directory; state written in `com.weback.robot.pilot` never appears in `com.weback.robot`. |
| **6** | **Android Keystore Behavior in Pilot Package** | Execute Keystore AEAD smoke/session lifecycle inside `com.weback.robot.pilot`. | Hardware-backed AES-256-GCM key is scoped to `com.weback.robot.pilot`'s UID, fails closed on tamper/missing key, and clears cleanly without touching `com.weback.robot` keys. |
| **7** | **Mock Simulator Preservation** | On `com.weback.robot.pilot`, trigger `Clean`, `Pause`, `Resume`, `Dock` on the dashboard, then run the `PilotReadinessSheet` offline mock workflow. | Simulator state transitions and `MainActivity` mock pilot workflow function identically while remaining isolated from live endpoints. |
| **8** | **Read-Only Network Boundary (Authorized Pilot Only)** | Only if separately authorized with a dedicated test account and `LaserSlam` robot: confirm outbound `send_to_device` / physical command triggers immediate `CommandResult.Rejected` + `UnexpectedOutboundCommandAttempt` abort. | Zero outbound robot command frames transmitted; `PilotSanitizedExporter` report shows zero leaked secrets/PII. |
| **9** | **Emergency Disconnect & Wipe** | Tap **Emergency Disconnect & Wipe** mid-session and also verify cold restart / deep-sleep monotonic expiry. | Immediate network cancellation (`<= 1s`), token store cleared, `PilotAuthorizationGate` disabled (`OfflineDefault`), and process restart never restores authorization. |
| **10** | **Clean Rollback Without Affecting Offline App** | Uninstall `com.weback.robot.pilot` (`adb uninstall com.weback.robot.pilot`) and launch `com.weback.robot`. | `com.weback.robot` remains installed, intact, `INTERNET`-free, and fully functional in offline simulator mode. |
