# L01F Updated Traceability Report (`LOC-001`)

- **Change ID:** `LOC-001` (`Revision 1.2.0` — Synced With `L01F2` Remediation & Freeze; see [L01F2_REMEDIATION_REPORT.md](L01F2_REMEDIATION_REPORT.md))
- **Machine-Readable Source:** [specs/changes/LOC-001/traceability.json](../../specs/changes/LOC-001/traceability.json)
- **Cardinality:** `10` Requirements (`REQ-LOCAL-501`..`510`) | `22` Acceptance Criteria (`AC-501.1`..`AC-510.3`) | `23` Independent Oracles (`TEST-LOCAL-501`..`523`)
- **Strict Stage Ownership Invariant (`FIND-L01R-01`):** Every `AC-*` maps 1-to-1 to **exactly one** `stage_owner` (`L01`, `L02A`, `L02B`, `L03A`, `L04A`, `L04B`, `L05`, or `L06`) and **one** `task_id` (`Task 1`..`Task 8`). Zero ACs straddle stages.

---

## 1. Stage-Partitioned Traceability Table (`22` ACs)

| AC ID | Requirement | Owning Stage | Task ID | Evidence Tier | Oracle ID(s) | L01F `verification_check_id` (`traceability.json`) | Planned Post-`L04A` Check & Promotion Rule |
|---|---|---|---|---|---|---|---|
| `AC-501.1` | `REQ-LOCAL-501` | `L01` | `Task 1` | `SPECIFICATION` | `TEST-LOCAL-501` | `spec-hygiene` | Active (`spec-hygiene` + git/SHA/`evidence.py`) |
| `AC-501.2` | `REQ-LOCAL-501` | `L01` | `Task 1` | `AUTOMATED_IMPL` | `TEST-LOCAL-522` | `pilot-apk-validation` | Active (`11/11` baseline verified on `184175a`) |
| `AC-501.3` | `REQ-LOCAL-501` | `L03A` | `Task 4` | `AUTOMATED_IMPL` | `TEST-LOCAL-513` | `android-unit-tests` | `android-unit-tests` (spy non-fallback counters) |
| `AC-502.1` | `REQ-LOCAL-502` | `L02A` | `Task 2` | `AUTOMATED_IMPL` | `TEST-LOCAL-502`, `503` | `android-unit-tests` | `android-unit-tests` (uses `app/src/test/resources/hoslam/`) |
| `AC-502.2` | `REQ-LOCAL-502` | `L02A` | `Task 2` | `AUTOMATED_IMPL` | `TEST-LOCAL-504`, `505`, `510` | `android-unit-tests` | `android-unit-tests` (`InvalidMagic`/`InvalidLength`/`FrameTooLarge`/`TruncatedFrame`/`InvalidChecksum` + boundary `EndOfStream`) |
| `AC-503.1` | `REQ-LOCAL-503` | `L02B` | `Task 3` | `AUTOMATED_IMPL` | `TEST-LOCAL-506`, `510` | `android-unit-tests` | `android-unit-tests` (`hoslmap\0` `<40`, dims, alignment, pad `0..3`) |
| `AC-503.2` | `REQ-LOCAL-503` | `L02B` | `Task 3` | `AUTOMATED_IMPL_PLUS_PRIVATE_ORACLE_NOT_RUN` | `TEST-LOCAL-507`, `509`, `511` | `android-unit-tests` | `android-unit-tests` + isolated temp-workspace mutants (`511`); `509` private BIN stays `NOT_RUN` in CI |
| `AC-504.1` | `REQ-LOCAL-504` | `L02B` | `Task 3` | `AUTOMATED_IMPL` | `TEST-LOCAL-508` | `android-unit-tests` | `android-unit-tests` (`hoslcac\0 == 20`, `hosltraj >= 16` `% 8 == 0`, `N=0`, `NaN`/`Inf`) |
| `AC-504.2` | `REQ-LOCAL-504` | `L02B` | `Task 3` | `AUTOMATED_IMPL` | `TEST-LOCAL-508` | `android-unit-tests` | `android-unit-tests` (counts only; no pose/heading models) |
| `AC-505.1` | `REQ-LOCAL-505` | `L03A` | `Task 4` | `AUTOMATED_IMPL` | `TEST-LOCAL-512` | `android-unit-tests` | `android-unit-tests` + secondary `apk-validation` (`assets/synthetic_fixtures/`, 0-map replay rejection, offline APK) |
| `AC-505.2` | `REQ-LOCAL-505` | `L03A` | `Task 4` | `AUTOMATED_IMPL` | `TEST-LOCAL-513` | `android-unit-tests` | `android-unit-tests` (truthful state, recreate preservation, a11y labels) |
| `AC-506.1` | `REQ-LOCAL-506` | `L04A` | `Task 5` | `AUTOMATED_IMPL` | `TEST-LOCAL-514` | `apk-validation` *(provisional)* | **Promote to `local-apk-validation` + secondary `local-android-unit-tests` atomically in `L04A`** |
| `AC-506.2` | `REQ-LOCAL-506` | `L04B` | `Task 6` | `AUTOMATED_IMPL` | `TEST-LOCAL-514`, `515`, `522` | `pilot-apk-validation` *(provisional)* | **Promote to `local-android-unit-tests` + secondary `local/pilot-apk-validation` & JUnit XML identities in `L04A`** |
| `AC-507.1` | `REQ-LOCAL-507` | `L04B` | `Task 6` | `AUTOMATED_IMPL` | `TEST-LOCAL-516` | `android-unit-tests` *(provisional)* | **Promote to `local-android-unit-tests` atomically in `L04A`** |
| `AC-507.2` | `REQ-LOCAL-507` | `L04B` | `Task 6` | `AUTOMATED_IMPL` | `TEST-LOCAL-515` | `android-unit-tests` *(provisional)* | **Promote to `local-android-unit-tests` atomically in `L04A`** |
| `AC-508.1` | `REQ-LOCAL-508` | `L04B` | `Task 6` | `AUTOMATED_IMPL` | `TEST-LOCAL-518`, `519`, `520` | `android-unit-tests` *(provisional)* | **Promote to `local-android-unit-tests` atomically in `L04A`** |
| `AC-508.2` | `REQ-LOCAL-508` | `L04B` | `Task 6` | `AUTOMATED_IMPL` | `TEST-LOCAL-517`, `520` | `android-unit-tests` *(provisional)* | **Promote to `local-android-unit-tests` atomically in `L04A`** |
| `AC-509.1` | `REQ-LOCAL-509` | `L04A` | `Task 5` | `AUTOMATED_IMPL` | `TEST-LOCAL-521` | `harness-selftests` | `harness-selftests` (`test_transition.py`, `test_apk_validator.py`) + `local-android-unit-tests` (`DashboardScreenControlTest`) |
| `AC-509.2` | `REQ-LOCAL-509` | `L01` | `Task 1` | `SPECIFICATION` | `TEST-LOCAL-501` | `spec-hygiene` | Active (`spec-hygiene` + git/SHA/`evidence.py` tier checks) |
| `AC-510.1` | `REQ-LOCAL-510` | `L03A` | `Task 4` | `AUTOMATED_IMPL` | `TEST-LOCAL-513` | `android-unit-tests` | `android-unit-tests` (Compose UI & runtime `UNSUPPORTED` checks) |
| `AC-510.2` | `REQ-LOCAL-510` | `L05` | `Task 7` | `HIL_HUMAN_GATED_NOT_RUN` | `TEST-LOCAL-523` | `android-smoke-test` *(schema placeholder)* | `manual-hil-acceptance` (NOT `android_smoke_test.py`; `status: NOT_RUN`) |
| `AC-510.3` | `REQ-LOCAL-510` | `L06` | `Task 8` | `HIL_HUMAN_GATED_NOT_RUN` | `TEST-LOCAL-523` | `android-smoke-test` *(schema placeholder)* | `manual-hil-wan-isolation` (`HIL-06` + `U01`..`U12`; `status: NOT_RUN`) |

---

## 2. Schema Limitation & Provisional Binding Governance (`FIND-L01R-06`, `FIND-L01R-10`)

- `verification/checks/spec_validator.py` line 186 enforces `valid_planned_checks = approved_checks | (proposed_checks or set()) | {"android-smoke-test"}` and permits one `verification_check_id` string per AC (`FIND-L01R2-07`).
- Because `L01F` / `L01F2` cannot modify `policies/` or create `policies/loc001_checks_proposal.json`, `traceability.json`:
  1. Preserves single valid `verification_check_id` values while capturing secondary checks cleanly in `"secondary_verification_checks"` (`apk-validation`, `local-apk-validation`, etc.) without fabricating active policy checks.
  2. Sets `"provisional_l01_check_binding": true` on `AC-506.1`..`508.2` alongside exact `"planned_t4_policy_check_id"` (`local-apk-validation` / `local-android-unit-tests`) and `"t4_promotion_rule"` prohibiting any `VERIFIED` transition before `L04A` promotes those check IDs atomically.
  3. Sets `"manual_hil_gate_distinct_from_android_smoke_test": true` and `"planned_hil_gate_id"` on `AC-510.2` and `AC-510.3` with `"status": "NOT_RUN"`, documenting explicitly that `android_smoke_test.py` targets offline `com.weback.robot` (`lines 17-19`) and does **not** represent physical HIL.
- Recommended `L04A` improvement (captured in `Task 5` and `t4-transition-proposal.md` §4.3 item 2): extend `spec_validator.py` so `provisional_l01_check_binding: true` fails validation if paired with `status == "VERIFIED"`, and add an explicit non-CI `"manual-hil-gate"` target keyword for `HIL` ACs.

---

## 3. Legacy `AC-502.2` Split Preservation Cross-Reference

| Former `L01` Concern Inside `AC-502.2` | Remediated `L01F` Owner | Remediated AC |
|---|---|---|
| Envelope magic, u32 `Long` length `< 16` / `> 2_097_152`, checksum, mid-frame `TruncatedFrame`, boundary `EndOfStream`, `0`-based `frameStartOffset`, reset | `Task 2` (`L02A`) | `AC-502.1`, `AC-502.2` |
| `hoslmap\0` `< 40` -> `UnsupportedLayout`, `W`/`H` -> `InvalidDimensions`, `L != aligned + 4` -> `UnsupportedLayout`, pad `!= 0x00` -> `InvalidPadding` | `Task 3` (`L02B`) | `AC-503.1` |
| `hoslcac\0` (`== 20`) & `hosltraj` (`>= 16`, `% 8 == 0`, `N=0`, `NaN`/`Inf`) -> `UnsupportedLayout` | `Task 3` (`L02B`) | `AC-504.1` |
| Offline replay exact EOF & zero-map replay acceptance failure | `Task 4` (`L03A`) | `AC-505.1` |
| Socket connect/first-map/absolute-`15s` in-frame deadlines, `180s` `SessionLimitReached`, cancellation & `<= 1_000 ms` close | `Task 6` (`L04B`) | `AC-508.1` |
