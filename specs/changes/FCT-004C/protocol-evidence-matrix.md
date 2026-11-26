# FCT-004C Protocol Uncertainty & Evidence Matrix

- **Change ID:** `FCT-004C`
- **Milestone:** `FCT-004C`
- **Document ID:** `SPEC-EVIDENCE-FCT004C`
- **Risk Tier:** `T4 (Critical Risk — Live Network Admission & Credential Entry)`
- **Provenance:** [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md) | [FCT-003 REG-06](../FCT-003/proposal.md#4-reconciled-re-evidence-matrix-reg-06-through-reg-10) | `com.yugong.Backome.function.b`

---

## 1. Evidence Provenance Classification

Per [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md) §2.2, primary Dalvik Smali and JADX decompiled sources take strict precedence over narrative reports. Sources below are classified into three provenance tiers:

1. **Archive-Confirmed Evidence:** Packed directly inside frozen archive `WEBack_6.1.7_FINAL_REVERSE_ENGINEERING_PACKAGE.zip` (`SHA-256: fd34a682a834011637d322cc4529c3ca72d49c9d8a9661c7f6c0089fa7246fe4`):
   - `decompiled/jadx/sources/com/yugong/Backome/function/b.java`
   - `decompiled/apktool/smali_classes3/com/yugong/Backome/function/b.smali`
   - `decompiled/jadx/sources/com/yugong/Backome/model/IdentityInfo.java`
   - `final/02_PROTOCOL_REGISTRY.md`, `final/protocols.json` (where superseded by `REG-06`..`REG-10`, bytecode governs)
2. **Local-Only Decompiled Evidence:** Present in the local unpacked RE workspace (`decompiled/jadx/sources/` and `decompiled/apktool/`), NOT packed inside the subset zip above:
   - `decompiled/jadx/sources/com/yugong/Backome/utils/v0.java` (`l()`, `u()`)
   - `decompiled/jadx/sources/com/yugong/Backome/utils/e0.java` (`s()`)
   - `decompiled/jadx/sources/com/yugong/Backome/utils/f.java` (`D()`)
   - `decompiled/jadx/sources/com/yugong/Backome/model/ThingBean.java`
   - `decompiled/jadx/sources/com/yugong/Backome/model/BaseResponse.java`
   - `decompiled/jadx/sources/com/yugong/Backome/utils/net/d.java`
   - `decompiled/jadx/sources/com/yugong/Backome/enums/k.java`
   - `decompiled/jadx/sources/com/google/firebase/analytics/FirebaseAnalytics.java`
3. **Unresolved Server-Side / Runtime Assumptions (`UNKNOWN`):** Behavior owned by Grit Cloud servers outside the static 6.1.7 APK boundary (`UNK-AUTH-01`..`08`, `UNK-INV-01`). **Static client evidence MUST NOT be interpreted as proof of current live server compatibility.**

---

## 2. Reconciled Protocol Evidence Matrix (`SEC-461`)

| Field / Contract | Provenance Tier | Exact Legacy Source Reference | Observed Legacy Fact | Discrepancies Corrected from Prior Draft | Clean-Room FCT-004C Choice & Uncertainty |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Region Routing & Path** | Archive-confirmed (`b.java`, `b.smali`) + Local-only (`e0.java`) | `function/b.java`: `f42918a` (`:28`), `f42919b` (`:31`), `r()` (`:237-239`); `b.smali:1083-1088`; `utils/e0.java:144-149` (`s()`) | `e0.s(str)` matches `"+86"` or `"0086"` (exact or prefix). `r(str)` routes CN to `https://user.grit-cloud.cn/prod/oauth` and non-CN to `https://user.grit-cloud.com/prod/oauth`. | Preserved exact `/prod/oauth` path and region routing contract. | Preserved in `RegionEndpointRouter`. Exact live availability and TLS leaf/CA behavior remain unverified until authorized probe. |
| **`header.api_version`** | Archive-confirmed (`b.java:328`, `b.smali:931-951`) + Local-only (`v0.java:327-334`) | `b.java:328` calls `v0.l(TApplication.b())`; `b.smali:931-951`; `v0.java:327-334` reads `PackageInfo.versionName` | Legacy APK `versionName` is `"6.1.7"` (`RE_BASELINE.md` §1). `AuthEnvelopeConfig.DEFAULT_PROTOCOL_API_VERSION` is `"6.1.7"` (`AuthGateway.kt:23`). | **Discrepancy corrected:** Prior draft claimed `"1.0"`, `"1.0.1"`, `"2.0"` and defaulted to `"1.0"`. Zero evidence for `"1.0"` in `b.I()`. | Transmit `"6.1.7"` (`AuthEnvelopeConfig`); never substitute `"0.1.0-alpha"`. Live server version allowlist remains **`UNKNOWN`** (`UNK-AUTH-07`). |
| **`header.app_name`** | Archive-confirmed (`b.java`, `b.smali`) | `b.java:329` (and `:314,346,363,454,556`); `b.smali:956-964` | Hardcoded string literal `"WeBack"` in every `function/b` auth envelope. | **Discrepancy corrected:** Prior draft claimed `"WeBack"` or `"Backome"`. `"Backome"` appears only in Java package `com.yugong.Backome`, never as `"app_name"` in `function/b`. | Transmit `"WeBack"` (`AuthEnvelopeConfig.appName`). Server allowlist remains **`UNKNOWN`**. |
| **`header.client_id`** | Archive-confirmed (`b.java`, `b.smali`) | `b.java:332`; `b.smali:987-995` | Hardcoded string literal `"yugong_app"`. | **Discrepancy corrected:** Prior draft claimed Android device ID / UUID and proposed generating a volatile UUID. | Transmit `"yugong_app"` (`AuthEnvelopeConfig.clientId`); zero IMEI/hardware probing. Server acceptance remains **`UNKNOWN`**. |
| **`header.language`** | Archive-confirmed (`b.java:333`, `b.smali:1000-1012`) + Local-only (`v0.java:409-428`) | `b.java:333` calls `v0.u()`; `b.smali:1000-1012`; `v0.java:409-428` | Dynamically derived from `LocaleList.getDefault().get(0)` / `Locale.getDefault()` (`locale.getLanguage()`, or `"zh-TW"` when `zh` + `TW`/`HK`; `""` on error). | Clarified dynamic derivation in legacy client vs clean-room config choice. | Retain `"en"` (`AuthEnvelopeConfig.languageTag`) as an explicit clean-room pilot configuration choice unless stronger evidence requires another value. Server fallback behavior remains **`UNKNOWN`**. |
| **`payload.opt`** | Archive-confirmed (`b.java`, `b.smali`) + Local-only (`FirebaseAnalytics.java`) | `b.java:335` (`FirebaseAnalytics.c.f34961m`); `FirebaseAnalytics.java:127`; `b.smali:1026-1034` (`const-string v2, "login"`) | Hardcoded `"login"`. | None. | Fixed `"login"` validated by `PilotHttpOperationGuard`. |
| **`payload.pwd`** | Archive-confirmed (`b.java:336`, `b.smali:1039-1051`) + Local-only (`f.java:136-155`) | `b.java:336` calls `f.D(str3)`; `b.smali:1043`; `utils/f.java:136-155` | Legacy computes unsalted lowercase MD5 hex of `str.getBytes()` (`MessageDigest.getInstance("MD5")`). | Clarified clean-room policy boundary vs legacy observation. | **Prohibited:** Zero legacy MD5 strategy or fallback (`specs/tech-stack.md` §4). Transmit UTF-8 over TLS (`Utf8TlsDefault`). **Server UTF-8 acceptance remains `UNKNOWN`** (`UNK-AUTH-05`). |
| **Login Response & Dynamic Origins** | Archive-confirmed (`IdentityInfo.java`) | `model/IdentityInfo.java:21-25` | Response `data` maps to `IdentityInfo`: `api_url`, `expired_time` (`int`), `jwt_token`, `region_name`, `wss_url`. | **Discrepancy corrected:** Prior draft named dynamic origin fields `api_host` and `wss_host`. Actual fields are `api_url` and `wss_url`. | Parse `api_url`, `wss_url`, `region_name`, `jwt_token`, `expired_time`. Unapproved dynamic origins trigger conservative quarantine termination (`SEC-462`). Dynamic host provenance remains **`UNKNOWN`** (`UNK-AUTH-07`). |
| **Challenge / CAPTCHA Handling** | Archive-confirmed (`b.java:97,106,368-371,561`) + Local-only (`enums/k.java`) | `b.java:97,106` (`get_verify_code_captcha`, `get_image_captcha`); `enums/k.java` (`CaptchaExpired`, `CaptchaInvalid`) | Legacy has separate `opt` verbs for verification/image CAPTCHAs on registration/password-reset and enum codes for CAPTCHA errors. Static client `IdentityInfo`/`BaseResponse` do **not** declare a `"captcha"` boolean field. | **Discrepancy corrected:** Clarified that `"captcha": true` in `PilotCapabilityRestrictedAuthGateway` is a **defensive clean-room guard**, not an observed `IdentityInfo` field. | Fail closed (`ChallengeRequired`) on `"captcha": true` or CAPTCHA challenge indicators in response `msg`; zero CAPTCHA automation; zero retry. Live server login challenge trigger/schema remains **`UNKNOWN`** (`UNK-AUTH-04`). |
| **Inventory Wire (`user_thing_list_get`)** | Archive-confirmed (`b.java`) + Local-only (`ThingBean.java`, `d.java`, `BaseResponse.java`) | `b.java` `k()` (`:229-235`), `s()` (`:241-249`), `D()` (`:277-282`); `d.java:115-132,180-199`; `BaseResponse.java:64-66`; `ThingBean.java:22-28` | POST `{"opt":"user_thing_list_get"}` to `IdentityInfo.api_url` with HTTP headers `token` (`jwt_token`) and `region` (`region_name`). Checks HTTP `200..210` and `BaseResponse.code == 1`; maps `ThingBean` (`thing_name`, `sub_type`, `thing_nickname`, `is_admin`, `brand_icon`, `thing_status`). | Clarified exact header and response contract (`token`, `region`, `code == 1`, `ThingBean` fields). | Bounded to at most ONE request in FCT-004C when `api_url` (and required origins) are pre-approved; exact `thing_name` + `LaserSlam` capability validation; zero WS connections. Live paging/error taxonomy remains **`UNKNOWN`** (`UNK-AUTH-02`, `UNK-INV-01`). |
| **Passwordless Refresh** | Archive-confirmed (`IdentityInfo.java`, `p0.java`) | `IdentityInfo.java:21-25` (`0` hits for `refresh_token` in `com/yugong/`); `FCT-003 REG-07` | Legacy has no `refresh_token`; silently re-runs `b.I()` with plaintext password saved in `SharedPreferences` (`p0.f43937e`). | Explicitly recorded prohibition of invented refresh grants. | **Prohibited:** `DisabledPasswordlessRefreshStrategy` remains active; zero password persistence; zero invented passwordless refresh grant (`UNK-AUTH-01`). |
