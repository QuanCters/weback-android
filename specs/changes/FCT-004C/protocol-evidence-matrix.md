# FCT-004C Protocol Uncertainty & Evidence Matrix

- **Change ID:** `FCT-004C`
- **Milestone:** `FCT-004C`
- **Document ID:** `SPEC-EVIDENCE-FCT004C`
- **Risk Tier:** `T4 (Critical Risk — Live Network Admission & Credential Entry)`
- **Provenance:** [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md) | `com.yugong.Backome.function.b`

---

## 1. Protocol Uncertainty Matrix

| Field / Feature | Reverse-Engineered Observation | Clean-Room Uncertainty | FCT-004C Resolution & Safety Guard |
| :--- | :--- | :--- | :--- |
| `api_version` | Decompiled code exhibits `"1.0"`, `"1.0.1"`, `"2.0"` | Whether cloud rejects unversioned or newer version strings | Default to `"1.0"`; record server response code if rejected. |
| `app_name` | Decompiled code sets `"WeBack"` or `"Backome"` | Server-side allowlist for `app_name` | Transmit `"WeBack"`; reject if cloud demands alternative. |
| `client_id` | Uses Android device ID / UUID string | Whether server enforces persistent client binding | Generate single-session volatile UUID; zero hardware IMEI probing. |
| `language` | Standard ISO 639-1 (`"en"`, `"zh"`, `"vi"`) | Localization fallback | Default to `"en"` for test execution. |
| `pwd` (Password) | Legacy code shows MD5 hex hashing in some branches | Whether Grit Cloud requires MD5 or accepts UTF-8 over TLS | **Law V Mandate:** Zero MD5 fallback. Transmit clean-room UTF-8 over TLS. If rejected, stop and report blocker. |
| `captcha` | Boolean `"captcha"` in response JSON | Triggers for CAPTCHA challenge on test accounts | Fails closed on `"captcha": true` (`CAPTCHA_CHALLENGE_NOT_SUPPORTED`). |
| Dynamic Origins | Server response contains `api_host` and `wss_host` | Dynamic cloud region routing | Quarantine all dynamic origins in `QuarantinedDynamicOrigins`. |
| Inventory Wire | `{"opt": "user_thing_list_get"}` | Extra parameters or paging tokens | Bounded to single request; parse device array for `thing_name`. |
