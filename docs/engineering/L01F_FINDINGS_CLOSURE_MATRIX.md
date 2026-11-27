# L01F Findings Closure Matrix (`FIND-L01R-01` .. `FIND-L01R-10`)

- **Review Remediated:** `L01R` (`CHANGES_REQUIRED`: `P0: 0`, `P1: 6`, `P2: 3`, `P3: 1`, `blocks_l02a: 2`)
- **Governance Rule (`POL-GATE-01` / `POL-INT-01`):** Historical Stage `L01F` remediation ledger for `FIND-L01R-01`..`10`. Under independent `L01R2` audit, `6` findings were **`CLOSED`** (`01`, `02`, `07`, `08`, `09`, `10`), `3` were `PARTIALLY_RESOLVED` (`04`, `05`, `06`), and `1` suffered a regression (`03`). All remaining items and `FIND-L01R2-01`..`07` are remediated in [L01F2_FINDINGS_CLOSURE_MATRIX.md](L01F2_FINDINGS_CLOSURE_MATRIX.md) (`LOC-001` rev `1.2.0`), strictly pending independent `L01R3` review.

---

## Summary Index

| Finding ID | Severity | Blocked `L02A` | Primary Owning Spec Sections | Revised Status |
|---|---|---|---|---|
| `FIND-L01R-01` | `P1` | Yes | `requirements.md`, `tasks.md`, `validation.md`, `traceability.json` | `REMEDIATED_PENDING_INDEPENDENT_REVIEW` |
| `FIND-L01R-02` | `P1` | Yes | `requirements.md`, `design.md` §2, `validation.md`, `hoslam-evidence-and-unknowns.md` | `REMEDIATED_PENDING_INDEPENDENT_REVIEW` |
| `FIND-L01R-03` | `P1` | No | `proposal.md` `ADR-L002`/`L009`, `requirements.md` `AC-506.1`/`506.2`, `design.md` §1.1, `tasks.md`, `t4-transition-proposal.md` §2 | `REMEDIATED_PENDING_INDEPENDENT_REVIEW` |
| `FIND-L01R-04` | `P1` | No | `t4-transition-proposal.md` §2–§4, `tasks.md` `Task 5`, `validation.md` `TEST-LOCAL-514`/`521` | `REMEDIATED_PENDING_INDEPENDENT_REVIEW` |
| `FIND-L01R-05` | `P1` | No | `design.md` §3–§4, `requirements.md` `AC-507.2`/`AC-508.1`, `validation.md` `TEST-LOCAL-515`/`518`/`519` | `REMEDIATED_PENDING_INDEPENDENT_REVIEW` |
| `FIND-L01R-06` | `P1` | No | `traceability.json`, `requirements.md` `AC-508.2`/`509.2`/`510.1`, `tasks.md` `Task 4`–`6`, `validation.md`, `design.md` §4 | `REMEDIATED_PENDING_INDEPENDENT_REVIEW` |
| `FIND-L01R-07` | `P2` | No | `validation.md` `TEST-LOCAL-506`/`508`/`511`, `tasks.md` `Task 3`, `requirements.md` `AC-503.1`/`503.2`/`504.1` | `REMEDIATED_PENDING_INDEPENDENT_REVIEW` |
| `FIND-L01R-08` | `P2` | No | `tasks.md` `Task 4`, `design.md` §1.2, `requirements.md` `AC-505.1` | `REMEDIATED_PENDING_INDEPENDENT_REVIEW` |
| `FIND-L01R-09` | `P2` | No | `requirements.md` `AC-501.2`/`AC-501.3`, `validation.md` `TEST-LOCAL-513`/`516`, `traceability.json` | `REMEDIATED_PENDING_INDEPENDENT_REVIEW` |
| `FIND-L01R-10` | `P3` | No | `design.md` §1.3, `validation.md` `TEST-LOCAL-507`/`523`, `traceability.json` `AC-510.2`/`510.3` | `REMEDIATED_PENDING_INDEPENDENT_REVIEW` |

---

## Detailed Closure Ledger

### `FIND-L01R-01`
- **Original Finding & Severity:** `FIND-L01R-01` | **`P1`** | `blocks_l02a: true`
- **Original Evidence (`L01R`):** `tasks.md` `Task 2` (`lines 17-22`) omitted `app/src/test/resources/` from allowed paths while `validation.md` `TEST-LOCAL-503..505` (`lines 24-26`) required reading `valid_mixed.bin` and negative `.bin` fixtures (absent from worktree); `traceability.json` (`lines 43-64`) mapped multi-stage `AC-502.2` solely to `L02A` despite containing `L02B` layout/padding/dimensions, `L03A` zero-map replay rejection, and `L04B` `ReadDeadline`/`Cancelled`; `Task 2` omitted the explicit `POL-GATE-01` §3.1 prerequisite that `LOC-001` be committed/merged before `L02A`.
- **Root Cause:** Early `LOC-001` grouped frame envelope, payload layout, offline replay EOF, and socket deadlines inside one criterion (`AC-502.2`) and deferred synthetic `.bin` test resources to `Task 3`.
- **Modified Document & Exact Section(s):**
  - `specs/changes/LOC-001/requirements.md` (`REQ-LOCAL-502` `AC-502.1`–`502.2`, `REQ-LOCAL-503` `AC-503.1`, `REQ-LOCAL-505` `AC-505.1`, `REQ-LOCAL-508` `AC-508.1`)
  - `specs/changes/LOC-001/tasks.md` (`Task 2` `L02A` lines 18–35, `Task 3` `L02B` lines 37–46, `Task 4` `L03A` lines 48–61, `Task 6` `L04B` lines 79–90)
  - `specs/changes/LOC-001/validation.md` (`TEST-LOCAL-503`..`506`, `512`, `518`)
  - `specs/changes/LOC-001/traceability.json` (`AC-502.1`..`AC-510.3`)
  - `specs/changes/LOC-001/proposal.md` (`ADR-L010`)
- **Applied Correction:**
  1. Narrowed `AC-502.2` strictly to `L02A` framing/envelope errors (`InvalidMagic`, `InvalidLength`, `FrameTooLarge`, `TruncatedFrame`, `InvalidChecksum`, clean boundary `EndOfStream`, `0`-based `frameStartOffset`, chunk/reset guarantees).
  2. Moved `hoslmap\0` layout/dimensions/padding checks to `AC-503.1` (`L02B`), `hoslcac\0`/`hosltraj` structure to `AC-504.1` (`L02B`), zero-map replay acceptance to `AC-505.1` (`L03A`), and `ReadDeadline`/socket cancellation to `AC-508.1` (`L04B`).
  3. Added `app/src/test/resources/hoslam/` (synthetic fixtures ONLY) and a step-by-step SHA-256 + `verify_capture.py` import procedure to `Task 2` (`L02A`), plus explicit `POL-GATE-01` §3.1 prerequisite requiring `L01R2` PASS, `G0`–`G3` approvals, and clean merged `LOC-001` spec baseline before starting `L02A`.
- **Revised Acceptance Criteria:** `AC-502.1`, `AC-502.2` (`L02A`); `AC-503.1` (`L02B`); `AC-505.1` (`L03A`); `AC-508.1` (`L04B`) — 1-to-1 single-stage ownership across all `22` ACs.
- **Revised Test Oracle:** `TEST-LOCAL-501`, `TEST-LOCAL-502`..`505`, `506`, `512`, `518`.
- **Future Implementation Stage Owner:** `Task 2` (`L02A`), `Task 3` (`L02B`), `Task 4` (`L03A`), `Task 6` (`L04B`).
- **Verification Evidence:** Cross-document script verifying 1-to-1 AC->single-stage->single-task mapping + `spec_validator.py` PASS *(recorded in `L01F` post-edit run)*.
- **Remaining Limitations:** Physical execution of `TEST-LOCAL-502`..`505` begins only after `L01R2` PASS, human `G0`–`G3` approvals, and clean `LOC-001` spec merge.
- **Status:** **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`**

---

### `FIND-L01R-02`
- **Original Finding & Severity:** `FIND-L01R-02` | **`P1`** | `blocks_l02a: true`
- **Original Evidence (`L01R`):** (1) `AC-502.2` listed `EndOfStream` inside the typed failure enum despite `design.md` line 64 treating boundary EOF as clean disconnect; (2) `validation.md` `TEST-LOCAL-505` permitted `(FrameTooLarge / InvalidLength)` for `0x80000010` and `0xFFFFFFFF`; (3) `design.md` line 54 omitted `(b.toLong() and 0xFFL)` causing signed `Int` overflow when MSB >= `0x80`; (4) `<40`-byte `hoslmap\0` and `totalLength != aligned + 4` lacked exact error tags; (5) `frameStartOffset` vs failing-byte offset and `RawHoslamFrame` return type were unspecified.
- **Root Cause:** Ambiguous pseudocode and unseparated envelope vs map-layout error taxonomy in initial `design.md` §2 and `TEST-LOCAL-505`.
- **Modified Document & Exact Section(s):**
  - `specs/changes/LOC-001/design.md` (§2.1 lines 64–73, §2.2 lines 74–88, §2.3 lines 89–111)
  - `specs/changes/LOC-001/requirements.md` (`AC-502.1`, `AC-502.2`, `AC-503.1`)
  - `specs/changes/LOC-001/validation.md` (`TEST-LOCAL-503`, `504`, `505`, `506`, `511`)
  - `specs/changes/LOC-001/hoslam-evidence-and-unknowns.md` (§2 lines 24–57)
- **Applied Correction:**
  1. Defined explicit `readU32Le` via per-byte `(bytes[offset + i].toLong() and 0xFFL)` prior to `Long` bounds (`16L..2_097_152L`) and before `.toInt()` or buffer allocation.
  2. Separated `HoslamFinishResult.EndOfStream(framesDelivered, bytesConsumed)` (clean boundary non-error) from `LocalCodecError.TruncatedFrame(frameStartOffset, bytesReadInFrame)`.
  3. Restricted `InvalidLength` to `< 16L` and required strictly `FrameTooLarge` for `> 2_097_152L`, including `0x80000010L` (`2_147_483_664L`) and `0xFFFFFFFFL` (`4_294_967_295L`), banning `InvalidLength` in `TEST-LOCAL-505`.
  4. Assigned `0`-based session-relative `Long` `frameStartOffset`, chunk defensive copying, `RawHoslamFrame`, post-error terminal isolation, `reset()`, and full deterministic precedence (`L02A` header/magic/len/truncation/checksum; `L02B` `<40` `UnsupportedLayout("MAP_LENGTH_BELOW_40")` -> `InvalidDimensions` -> `UnsupportedLayout("MAP_LENGTH_LAYOUT_MISMATCH")` -> `InvalidPadding`).
- **Revised Acceptance Criteria:** `AC-502.1`, `AC-502.2`, `AC-503.1`.
- **Revised Test Oracle:** `TEST-LOCAL-502`, `503`, `504`, `505`, `506`, `511` (including signed `Int` frame-length mutant).
- **Future Implementation Stage Owner:** `Task 2` (`L02A`) and `Task 3` (`L02B`).
- **Verification Evidence:** Synthetic fixture checks (`verify_capture.py`) + spec consistency validation.
- **Remaining Limitations:** Kotlin unit compilation/execution deferred until `L02A`/`L02B`.
- **Status:** **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`**

---

### `FIND-L01R-03`
- **Original Finding & Severity:** `FIND-L01R-03` | **`P1`** | `blocks_l02a: false`
- **Original Evidence (`L01R`):** Canonical `MainActivity.kt` (`app/src/main/`, lines 63, 71, 75) accesses `app.container.pilotSessionController`, and `DefaultAppContainer.kt` (`app/src/main/`, lines 140-351) constructs `PilotClientFactory`, `PilotCapabilityRestrictedAuthGateway`, `OkHttpDeviceInventoryGateway`, `OkHttpWebSocketTransport`, and `KeystoreAeadTokenStore`. Because `isMinifyEnabled = false` and `local` inherits `src/main/`, cloud classes would compile into `app-local.apk` DEX and instantiate at startup, contradicting `AC-506.2` and `design.md` lines 5/41.
- **Root Cause:** `LOC-001` initially asserted build-time/runtime cloud exclusion without specifying source-set composition-root refactoring in `src/main/` or Day-1 enforcement in `L04A`.
- **Modified Document & Exact Section(s):**
  - `specs/changes/LOC-001/proposal.md` (`ADR-L002`, `ADR-L009`)
  - `specs/changes/LOC-001/requirements.md` (`AC-506.1`, `AC-506.2`)
  - `specs/changes/LOC-001/design.md` (§1.1 lines 29–39, §1.2 lines 40–53)
  - `specs/changes/LOC-001/tasks.md` (`Task 5` `L04A` lines 63–77, `Task 6` `L04B` lines 79–90)
  - `specs/changes/LOC-001/t4-transition-proposal.md` (§2.1–§2.2, §3 item 2)
  - `specs/changes/LOC-001/validation.md` (`TEST-LOCAL-514`)
- **Applied Correction:**
  1. Specified Day-1 `L04A` composition-root decoupling: `WeBackApplication` delegates to build-type-specific `VariantAppContainerFactory`, `AppContainer` exposes nullable `pilotCapabilityBridge: PilotCapabilityBridge?` (`null` in `local` & `release`), and `MainActivity` renders `PilotReadinessSheet` only when `pilotCapabilityBridge != null`.
  2. Relocated concrete cloud factories (`PilotCapabilityRestrictedAuthGateway`, `OkHttpAuthGateway`, `OkHttpDeviceInventoryGateway`, `OkHttpWebSocketTransport`, `PilotClientFactory`, `ExternalPilotAuthorizationLoader`, `KeystoreAeadTokenStore`, `AuthorizedPilotEndpointTrustPolicy`, `PilotSessionController`, `PilotReadinessSheet`) into `src/cloudPilot/` (included ONLY in `debug` & `pilot`, excluded from `local` & `release`).
  3. Kept `DomainPurityTest` and `Fct004BPreInternetSecurityTest` untouched under `src/main/domain/` and kept all `157` existing `src/test/` unit tests intact via `testLocal` `setSrcDirs(listOf("src/testLocalShared/java", "src/testLocal/java"))`.
  4. Added concrete cloud class DEX exclusions in `apk_validator.py --connectivity-policy local` (`TEST-LOCAL-514`), plus explained `release` structural exclusion vs `checks.json` build scope (`t4-transition-proposal.md` §2.1).
- **Revised Acceptance Criteria:** `AC-506.1` (`L04A`), `AC-506.2` (`L04B`).
- **Revised Test Oracle:** `TEST-LOCAL-514`, `TEST-LOCAL-521`, `TEST-LOCAL-522`.
- **Future Implementation Stage Owner:** `Task 5` (`L04A` composition-root split & DEX policy), `Task 6` (`L04B` `ReceiveOnlyTcpSource` wiring).
- **Verification Evidence:** Inspected canonical `AppContainer.kt`, `MainActivity.kt`, and `app/build.gradle.kts` source layout + `spec_validator.py`.
- **Remaining Limitations:** Actual Gradle compilation and `dexdump`/`apk_validator.py` execution require approved `L04A` T4 transition.
- **Status:** **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`**

---

### `FIND-L01R-04`
- **Original Finding & Severity:** `FIND-L01R-04` | **`P1`** | `blocks_l02a: false`
- **Original Evidence (`L01R`):** (1) `app/build.gradle.kts` lines 114-117 declare `compose.bom` and `ui.test.manifest` per buildType (`debugImplementation`, `pilotImplementation`), omitted for `local` in `t4-transition-proposal.md` §2; (2) `apk_validator.py` hardcoded `("pilot", "app-pilot.apk")` (line 31), `("debug", "app-debug.apk")` (line 38), and `("app-debug.apk", "app-pilot.apk")` (line 370), left asymmetric in §3; (3) line 27 left `testBuildType` tentative and line 81 made updating `checks.baseline.json` (`e688b6fe...`) conditional.
- **Root Cause:** Draft `t4-transition-proposal.md` inspected high-level variant properties without reconciling exact `app/build.gradle.kts` Compose BOM scopes, line 370 path tuple, and `transition.py` baseline fallback rules.
- **Modified Document & Exact Section(s):**
  - `specs/changes/LOC-001/t4-transition-proposal.md` (§2.1–§2.2 lines 20–60, §3 lines 63–75, §4.2–§4.3 lines 86–101)
  - `specs/changes/LOC-001/tasks.md` (`Task 5` `L04A` lines 66–77)
  - `specs/changes/LOC-001/requirements.md` (`AC-506.1`, `AC-509.1`)
  - `specs/changes/LOC-001/validation.md` (`TEST-LOCAL-514`, `521`)
- **Applied Correction:**
  1. Explicitly required `add("localImplementation", platform(libs.androidx.compose.bom))` and `add("localImplementation", libs.androidx.compose.ui.test.manifest)`.
  2. Explicitly pinned `testBuildType = "pilot"` in `L04A` (keeping explicit opt-in `-PwebackTestBuildType=local` for any `L05` HIL instrumentation).
  3. Made `apk_validator.py` symmetric across all three modes (`offline`, `pilot`, `local`), updating `forbidden_variant_tokens` for `offline` & `pilot` to reject `"local"` / `"app-local.apk"` and updating line 370 to `("app-debug.apk", "app-pilot.apk", "app-local.apk")`.
  4. Mandated atomic `policies/checks.baseline.json` update to `fd870ee6...` alongside `policies/checks.json` (`11 -> 15` checks), with zero fabricated digests (`TO_BE_COMPUTED_IN_L04A_PR`).
- **Revised Acceptance Criteria:** `AC-506.1`, `AC-509.1`.
- **Revised Test Oracle:** `TEST-LOCAL-514`, `TEST-LOCAL-521` (incl. `DashboardScreenControlTest` in `testLocalUnitTest`).
- **Future Implementation Stage Owner:** `Task 5` (`L04A`).
- **Verification Evidence:** Verified exact lines in `app/build.gradle.kts` (43, 114-117), `apk_validator.py` (25-40, 370), `spec_validator.py` (185-196), and SHA-256 of `policies/checks.baseline.json`.
- **Remaining Limitations:** `L04A` remains `PROPOSAL_ONLY_NOT_AUTHORIZED`; protected files remain unmodified in `L01F`.
- **Status:** **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`**

---

### `FIND-L01R-05`
- **Original Finding & Severity:** `FIND-L01R-05` | **`P1`** | `blocks_l02a: false`
- **Original Evidence (`L01R`):** (1) `design.md` §3 FSM omitted `Stale -> Error` and incomplete `WaitingForMap -> Error`; (2) `AC-508.1` lacked millisecond close bound and distinction between `ON_STOP` backgrounding (`!isChangingConfigurations` / `ProcessLifecycleOwner`) vs configuration-change rotation; (3) `design.md` line 107 left `.0`/`.255` RFC1918 handling as an unresolved question; (4) `design.md` line 111 omitted `Socket(Proxy.NO_PROXY)`.
- **Root Cause:** Initial lifecycle diagram and endpoint policy left edge transitions, partial-frame trickle deadline semantics, and Android configuration-change vs background signals underspecified.
- **Modified Document & Exact Section(s):**
  - `specs/changes/LOC-001/design.md` (§3 lines 114–159, §3.2 lines 160–172, §4 lines 175–186)
  - `specs/changes/LOC-001/requirements.md` (`AC-507.2`, `AC-508.1`)
  - `specs/changes/LOC-001/validation.md` (`TEST-LOCAL-515`, `518`, `519`)
  - `specs/changes/LOC-001/risk-assessment.md` (`THREAT-502`, `THREAT-507`)
- **Applied Correction:**
  1. Completed Mermaid FSM and rules: added `Stale -> Streaming`, `Stale -> Error`, `Stale -> Disconnected`, `WaitingForMap -> Error` (`FirstMap 30s` / `InFrame 15s` / `CodecErr` / `IOException`), clean EOF from every connected state (`EOF_BEFORE_FIRST_MAP` fails map acceptance), `180s` `SessionLimitReached`, absolute `15s` in-frame deadline from first frame byte, race serialization via `activeGenerationId`, and full 9-row Map Retention & Clearing Table.
  2. Selected MVP rotation rule: cleanly close live socket within `<= 1_000 ms` on configuration/rotation change and require explicit manual reconnect (zero hidden auto-reconnect), while separating backgrounding (`ON_STOP` when `!isChangingConfigurations` / `ProcessLifecycleOwner`).
  3. Resolved RFC1918 last-octet rule conservatively for MVP: allow `1..254` only (reject `.0` and `.255`).
  4. Mandated `Socket(Proxy.NO_PROXY)` + post-connect peer IPv4/port `9999` verification + explicit application-level constraint notice.
- **Revised Acceptance Criteria:** `AC-507.2`, `AC-508.1`.
- **Revised Test Oracle:** `TEST-LOCAL-515`, `TEST-LOCAL-518`, `TEST-LOCAL-519`.
- **Future Implementation Stage Owner:** `Task 6` (`L04B`).
- **Verification Evidence:** Complete FSM/retention/deadline audit in `L01F_PROTOCOL_CONTRACT_DECISIONS.md` and `L01F_SPEC_DIFF_REVIEW.md`.
- **Remaining Limitations:** Live socket/lifecycle execution requires approved `L04B`.
- **Status:** **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`**

---

### `FIND-L01R-06`
- **Original Finding & Severity:** `FIND-L01R-06` | **`P1`** | `blocks_l02a: false`
- **Original Evidence (`L01R`):** (1) `traceability.json` bound `AC-506.1`..`508.2` to `apk-validation`/`pilot-apk-validation`/`android-unit-tests` with an ignored `target_t4_check_id`; (2) `AC-510.1` and `AC-509.2` mapped solely to `TEST-LOCAL-501` (`spec-hygiene`); (3) `SecretRedactor.kt` (`app/src/main/java/.../security/SecretRedactor.kt`) did not scrub IPv4 literals and was omitted from `Task 6` allowed paths, and diagnostic ring/counters lacked numeric bounds.
- **Root Cause:** `spec_validator.py` line 186 rejects non-active check IDs unless defined in `policies/loc001_checks_proposal.json` (which cannot be edited in `L01`/`L01F`), while IPv4 log redaction relied on unscheduled `SecretRedactor.kt` edits.
- **Modified Document & Exact Section(s):**
  - `specs/changes/LOC-001/traceability.json` (`schema_limitation_note`, `AC-506.1`..`508.2`, `AC-509.2`, `AC-510.1`)
  - `specs/changes/LOC-001/requirements.md` (`AC-508.2`, `AC-509.1`, `AC-509.2`, `AC-510.1`)
  - `specs/changes/LOC-001/design.md` (§4 item 4 lines 187–190)
  - `specs/changes/LOC-001/tasks.md` (`Task 4` `L03A`, `Task 5` `L04A`, `Task 6` `L04B`)
  - `specs/changes/LOC-001/validation.md` (`TEST-LOCAL-501`, `513`, `517`, `521`)
  - `specs/changes/LOC-001/t4-transition-proposal.md` (§4.3 item 2 line 97)
- **Applied Correction:**
  1. Explicitly marked `AC-506.1`..`508.2` with `"provisional_l01_check_binding": true`, `"planned_t4_policy_check_id"`, and `"t4_promotion_rule"` requiring atomic `L04A` switch to `local-*` checks before `status == "VERIFIED"`, paired with `L04A` `spec_validator.py` hardening note.
  2. Re-owned `AC-510.1` to `Task 4` (`L03A`) -> `TEST-LOCAL-513` (`android-unit-tests`, Compose + runtime unsupported checks) alongside `L02B` (`AC-504.2` / `TEST-LOCAL-508`) and `L04B` (`AC-507.1` / `TEST-LOCAL-516`), and clarified `TEST-LOCAL-501` (`AC-509.2`) requires git/diff/SHA/`evidence.py` checks rather than `spec-hygiene` alone.
  3. Added `SecretRedactor.kt` & `SecretRedactorTest.kt` to `Task 6` (`L04B`) allowed paths to scrub IPv4 literals (`[REDACTED_IP]`), specified construction-time IP/byte-free `LocalDiagnosticEvent`, and capped diagnostic ring buffer at `<= 200` entries / `<= 512` chars with fixed `Long` counters.
- **Revised Acceptance Criteria:** `AC-506.1`..`AC-508.2`, `AC-509.1`, `AC-509.2`, `AC-510.1`.
- **Revised Test Oracle:** `TEST-LOCAL-501`, `508`, `513`, `516`, `517`, `521`.
- **Future Implementation Stage Owner:** `Task 4` (`L03A`), `Task 5` (`L04A`), `Task 6` (`L04B`).
- **Verification Evidence:** Verified `spec_validator.py` line 186 behavior and `traceability.json` consistency via `spec_validator.py`.
- **Remaining Limitations:** `L04A` must promote provisional `verification_check_id` entries before any `T4` AC marks `VERIFIED`.
- **Status:** **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`**

---

### `FIND-L01R-07`
- **Original Finding & Severity:** `FIND-L01R-07` | **`P2`** | `blocks_l02a: false`
- **Original Evidence (`L01R`):** `TEST-LOCAL-511` omitted concrete execution mechanism avoiding mutations in `main` and omitted 5 critical mutants (`0x80000010` signed-`Int` length, `W*H` `Int32` overflow, signed-`Byte` pixel extension `>=0x80`, `totalLength != aligned + 4` bypass, `aux`/`traj` freshness reset); `TEST-LOCAL-506` omitted `H=0`, `hoslmap\0 < 40`, `> aligned + 4` with valid checksum, and inner nonzero padding byte `1`/`2`; `TEST-LOCAL-508` omitted `N=0` (`16` B) `hosltraj` and `NaN`/`Infinity` float32 payloads.
- **Root Cause:** Compressed test table in initial `validation.md` omitted negative edge permutations and isolated temp-workspace execution steps (`POL-INT-01` §4 Step 8).
- **Modified Document & Exact Section(s):**
  - `specs/changes/LOC-001/validation.md` (`TEST-LOCAL-506`, `508`, `511` lines 32, 34, 37)
  - `specs/changes/LOC-001/requirements.md` (`AC-503.1`, `AC-503.2`, `AC-504.1`)
  - `specs/changes/LOC-001/tasks.md` (`Task 3` `L02B` line 45)
  - `specs/changes/LOC-001/hoslam-evidence-and-unknowns.md` (§2 line 46)
- **Applied Correction:** Defined `TEST-LOCAL-511` strictly as an isolated temporary-workspace (`tempfile.TemporaryDirectory`) 11-mutant kill protocol (zero mutations left in `main`), and explicitly added all missing negative layout vectors (`H=0`, `<40`, `> aligned + 4`, pad index `1`/`2`), `N=0` `hosltraj`, and non-finite `NaN`/`Infinity` float32 test cases.
- **Revised Acceptance Criteria:** `AC-503.1`, `AC-503.2`, `AC-504.1`.
- **Revised Test Oracle:** `TEST-LOCAL-506`, `TEST-LOCAL-508`, `TEST-LOCAL-511`.
- **Future Implementation Stage Owner:** `Task 3` (`L02B`).
- **Verification Evidence:** Verified inclusion of all 11 mutants and edge vectors in `validation.md`.
- **Remaining Limitations:** Mutation harness runs during `L02B`.
- **Status:** **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`**

---

### `FIND-L01R-08`
- **Original Finding & Severity:** `FIND-L01R-08` | **`P2`** | `blocks_l02a: false`
- **Original Evidence (`L01R`):** `Task 4` (`L03A`, `tasks.md` line 34) omitted `app/src/main/assets/` or `res/raw/`, even though `REQ-LOCAL-505` and `TEST-LOCAL-512` require `SyntheticReplayTelemetrySource` to replay `valid_mixed.bin` inside the offline app.
- **Root Cause:** Asset path omitted from `Task 4` allowlist in `tasks.md` and package table in `design.md`.
- **Modified Document & Exact Section(s):**
  - `specs/changes/LOC-001/tasks.md` (`Task 4` `L03A` line 55)
  - `specs/changes/LOC-001/design.md` (§1.2 line 49)
  - `specs/changes/LOC-001/requirements.md` (`AC-505.1`)
- **Applied Correction:** Added `app/src/main/assets/synthetic_fixtures/` (strictly authored `valid_mixed.bin` + `manifest.json`, zero household pixels, hash-verified against pack `manifest.json`) to `Task 4` allowed paths and `design.md` §1.2.
- **Revised Acceptance Criteria:** `AC-505.1`.
- **Revised Test Oracle:** `TEST-LOCAL-512`.
- **Future Implementation Stage Owner:** `Task 4` (`L03A`).
- **Verification Evidence:** Path consistency verified across `tasks.md`, `design.md`, and `validation.md`.
- **Remaining Limitations:** Asset bundling occurs in `L03A`.
- **Status:** **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`**

---

### `FIND-L01R-09`
- **Original Finding & Severity:** `FIND-L01R-09` | **`P2`** | `blocks_l02a: false`
- **Original Evidence (`L01R`):** Initial `AC-501.2` claimed zero auto-fallback across cloud, simulator, and local map paths while `traceability.json` mapped `AC-501.2` solely to `TEST-LOCAL-522` (the existing `157` debug + `157` pilot tests), which have zero awareness of local map paths.
- **Root Cause:** Combined existing-baseline non-regression with new local non-fallback behavior inside a single AC owned by `Task 1`.
- **Modified Document & Exact Section(s):**
  - `specs/changes/LOC-001/requirements.md` (`AC-501.2`, new `AC-501.3`)
  - `specs/changes/LOC-001/validation.md` (`TEST-LOCAL-513`, `TEST-LOCAL-516`, `TEST-LOCAL-522`)
  - `specs/changes/LOC-001/tasks.md` (`Task 4` `L03A`, `Task 6` `L04B`)
  - `specs/changes/LOC-001/traceability.json` (`AC-501.2`, `AC-501.3`, `AC-507.1`)
- **Applied Correction:** Kept `AC-501.2` strictly for baseline non-regression (`TEST-LOCAL-522`), introduced **`AC-501.3`** (`Task 4` / `L03A`) verified via provider/factory spy counters (`TEST-LOCAL-513`: zero automatic fallback to `SimulatorRobotTransport` or cloud gateways on empty streams/EOF/errors), and reinforced `AC-507.1` (`TEST-LOCAL-516` in `L04B`: spy cloud/simulator factories record `0` calls in live local path).
- **Revised Acceptance Criteria:** `AC-501.2`, `AC-501.3`, `AC-507.1`.
- **Revised Test Oracle:** `TEST-LOCAL-513`, `TEST-LOCAL-516`, `TEST-LOCAL-522`.
- **Future Implementation Stage Owner:** `Task 1` (`L01`), `Task 4` (`L03A`), `Task 6` (`L04B`).
- **Verification Evidence:** Traceability script confirming `AC-501.3 -> TEST-LOCAL-513` and `AC-507.1 -> TEST-LOCAL-516`.
- **Remaining Limitations:** Spy-counter tests implement in `L03A` and `L04B`.
- **Status:** **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`**

---

### `FIND-L01R-10`
- **Original Finding & Severity:** `FIND-L01R-10` | **`P3`** | `blocks_l02a: false`
- **Original Evidence (`L01R`):** (1) `TEST-LOCAL-523` targeted `android-smoke-test`, which in `verification/checks/android_smoke_test.py` lines 17-19 hardcodes `EXPECTED_PACKAGE = "com.weback.robot"` (offline, not `.local`); (2) `RasterSnapshot` defaulted to reference equality and unredacted `ByteArray.toString()` without explicit defensive copy overrides in `design.md`.
- **Root Cause:** Reused existing schema placeholder string without documenting script incompatibility, and omitted explicit JVM `ByteArray` `equals`/`hashCode`/`toString`/defensive-copy requirements.
- **Modified Document & Exact Section(s):**
  - `specs/changes/LOC-001/validation.md` (`TEST-LOCAL-507`, `TEST-LOCAL-523`)
  - `specs/changes/LOC-001/design.md` (§1.3 lines 54–57)
  - `specs/changes/LOC-001/requirements.md` (`AC-503.2`, `AC-510.2`, `AC-510.3`)
  - `specs/changes/LOC-001/traceability.json` (`schema_limitation_note`, `AC-510.2`, `AC-510.3`)
- **Applied Correction:**
  1. Explicitly separated `TEST-LOCAL-523` (`Manual HIL Gate`, `NOT_RUN` in CI) from `android_smoke_test.py`, documenting in `traceability.json` (`manual_hil_gate_distinct_from_android_smoke_test: true`, `planned_hil_gate_id`) why `"android-smoke-test"` appears solely as a schema placeholder until `L04A`.
  2. Added §1.3 in `design.md` and explicit requirements in `AC-503.2` / `TEST-LOCAL-507` requiring defensive `copyOf()` on construction and `copyPixels()`/`copyBytes()`, custom content `equals`/`hashCode`, and redacted `toString()` on both `RawHoslamFrame` and `RasterSnapshot`.
- **Revised Acceptance Criteria:** `AC-503.2`, `AC-510.2`, `AC-510.3`.
- **Revised Test Oracle:** `TEST-LOCAL-507`, `TEST-LOCAL-517`, `TEST-LOCAL-523`.
- **Future Implementation Stage Owner:** `Task 3` (`L02B`), `Task 5` (`L04A` schema note), `Task 7` (`L05`), `Task 8` (`L06`/`L07+`).
- **Verification Evidence:** Verified `android_smoke_test.py` lines 17-19 and `spec_validator.py` line 186 behavior.
- **Remaining Limitations:** Physical HIL gates remain `NOT_RUN`.
- **Status:** **`REMEDIATED_PENDING_INDEPENDENT_REVIEW`**
