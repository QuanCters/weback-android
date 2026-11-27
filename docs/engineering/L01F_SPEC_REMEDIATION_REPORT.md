# L01F Specification Remediation Report (`LOC-001`)

- **Stage:** `L01F` — Specification Remediation Following Independent Adversarial Review (`L01R`)
- **Change ID:** `LOC-001` (`Revision 1.1.0`)
- **Date:** `2026-11-27`
- **Authorized Worktree / Branch:** `.worktrees/rapido-local-spec` (`spec/rapido-local-first`)
- **Canonical Base Commit (`origin/main`):** `184175a14e5eb6659396c95624e2d3b08d1078a1` (`tree` `4a460199553cb237114895d105f4e20971f409d6`)
- **Protected Policy Digest (`policies/checks.json`):** `fd870ee6b2b324aa1d2bfbe84d9282df13708821cb7d291d2560d0a80a77bbc4` (unmodified)
- **Prior Review Verdict:** `L01R` -> `CHANGES_REQUIRED` (`P0: 0`, `P1: 6`, `P2: 3`, `P3: 1`, `blocks_l02a: 2`)
- **Subsequent `L01R2` Audit & `L01F2` Resolution:** Under `L01R2`, six `L01R` findings (`01`, `02`, `07`, `08`, `09`, `10`) were confirmed `CLOSED`, while `03` (`FIND-L01R2-01`, `03`), `04` (`FIND-L01R2-02`, `03`, `07`), `05` (`FIND-L01R2-04`), and `06` (`FIND-L01R2-05`) were updated alongside `FIND-L01R2-06` in Stage **`L01F2`** (`LOC-001` rev `1.2.0`). See [L01F2_REMEDIATION_REPORT.md](L01F2_REMEDIATION_REPORT.md), [L01F2_FINDINGS_CLOSURE_MATRIX.md](L01F2_FINDINGS_CLOSURE_MATRIX.md), and [L01F2_VERIFICATION_REPORT.md](L01F2_VERIFICATION_REPORT.md).

---

## 1. Executive Remediation Summary

Independent adversarial review (`L01R`) identified ten defects (`FIND-L01R-01`..`10`), two of which directly blocked `L02A` implementation (`FIND-L01R-01` and `FIND-L01R-02`). Stage `L01F` remediated every finding inside the authorized specification worktree without writing any production Kotlin code, test code, Gradle DSL, Android manifests, `policies/`, `verification/`, or root checkout files.

Summary of structural fixes by Workstream (`A`–`G`):
1. **Workstream A (`FIND-L01R-01`, `08`, `09`):** Split mixed-stage acceptance criteria into `22` strictly single-stage ACs (`1` AC -> `1` owning stage: `AC-501.3` -> `L03A`, `AC-502.1`/`502.2` -> `L02A` framing only, `AC-503.1`/`503.2`/`504.1`/`504.2` -> `L02B` map/aux/traj layout & raster decode, `AC-505.1`/`505.2`/`510.1` -> `L03A` viewer/0-map replay/UI unsupported labels, `AC-506.1`/`509.1` -> `L04A` T4, `AC-506.2`/`507.1`..`508.2` -> `L04B` socket/lifecycle/privacy, `AC-510.2` -> `L05`, `AC-510.3` -> `L06`/`L07+`). Added synthetic-only `app/src/test/resources/hoslam/` + SHA-verified import procedure to `Task 2` (`L02A`), `app/src/main/assets/synthetic_fixtures/` to `Task 4` (`L03A`), clean landed-spec prerequisite to `Task 2`, and `TEST-LOCAL-513`/`516` fallback oracles.
2. **Workstream B (`FIND-L01R-02`, `07`, `10`):** Defined exact `HoslamStreamFramer` (`L02A`) and `HoslamFrameDecoder` (`L02B`) contracts, fixed `readU32Le` to `(bytes[offset + i].toLong() and 0xFFL)` prior to allocation, separated `EndOfStream` (non-error clean boundary `finish()`) from `TruncatedFrame`, enforced `FrameTooLarge` for `> 2_097_152L` (including `0x80000010L` and `0xFFFFFFFFL`), established exact `0`-based `frameStartOffset` and deterministic multi-fault precedence, preserved raw u8 raster at offset 36 without ZLIB/`Inflater`, specified defensive `copyOf()`/`equals`/`hashCode`/redacted `toString()` on `RawHoslamFrame` & `RasterSnapshot`, defined isolated temp-workspace 11-mutant proof (`TEST-LOCAL-511`), and added `H=0`, `<40`, `> aligned+4`, inner pad `1`&`2`, `N=0` traj, and `NaN`/`Infinity` test cases.
3. **Workstream C (`FIND-L01R-03`):** Resolved `MainActivity` (`lines 63, 71, 75`) and `DefaultAppContainer` (`lines 140-351`) coupling by specifying `VariantAppContainerFactory`, `PilotCapabilityBridge?`, `src/cloudPilot/` (excluded from `local` & `release`), `src/local/` (excluded from `offline` & `pilot`), and `testLocal` `setSrcDirs` starting on `L04A` Day 1 — keeping all `157` existing `src/test/` tests intact while excluding concrete cloud gateways/Keystore from `app-local.apk` DEX.
4. **Workstream D (`FIND-L01R-04`, `06`):** Specified `localImplementation` Compose BOM + `ui-test-manifest`, pinned `testBuildType = "pilot"`, symmetric 3-way `apk_validator.py` `forbidden_variant_tokens` & line 370 tuple (`("app-debug.apk", "app-pilot.apk", "app-local.apk")`), DEX class exclusion checks, mandatory `policies/checks.baseline.json` update (`e688b6...` -> `fd870ee6...`), zero fabricated digests (`TO_BE_COMPUTED_IN_L04A_PR`), and atomic `L04A` promotion of `traceability.json` provisional bindings + optional `spec_validator.py` hardening.
5. **Workstream E (`FIND-L01R-05`, `06`, `10`):** Completed the lifecycle FSM (`Stale -> Streaming`, `Stale -> Error`, `Stale -> Disconnected`, `WaitingForMap -> Error`, `EOF_BEFORE_FIRST_MAP`, `180s` `SessionLimitReached`), specified monotonic `elapsedRealtimeMs()` and absolute `15s` in-frame deadline, defined rotation manual-reconnect decision, `!isChangingConfigurations` / `ProcessLifecycleOwner` background teardown in `<= 1_000 ms`, exact 9-row Map Retention & Clearing table, `LocalLanTrustPolicy` `.1..254` + `Socket(Proxy.NO_PROXY)` + post-connect peer check, construction-time IP/byte-free `LocalDiagnosticEvent` + `<= 200` ring cap, `SecretRedactor.kt` IPv4 scrubbing in `Task 6`, and explicit `TEST-LOCAL-523` manual HIL distinction from `android_smoke_test.py`.
6. **Workstreams F & G (`FIND-L01R-01`..`10`):** Produced all six `L01F_*` deliverables, synced `L01_*`, and audited 13 cross-document consistency dimensions.

---

## 2. Deliverable Inventory (`L01F`)

- Updated Core Change Spec (`specs/changes/LOC-001/`):
  [proposal.md](../../specs/changes/LOC-001/proposal.md) | [requirements.md](../../specs/changes/LOC-001/requirements.md) | [design.md](../../specs/changes/LOC-001/design.md) | [risk-assessment.md](../../specs/changes/LOC-001/risk-assessment.md) | [tasks.md](../../specs/changes/LOC-001/tasks.md) | [validation.md](../../specs/changes/LOC-001/validation.md) | [traceability.json](../../specs/changes/LOC-001/traceability.json) | [t4-transition-proposal.md](../../specs/changes/LOC-001/t4-transition-proposal.md) | [hoslam-evidence-and-unknowns.md](../../specs/changes/LOC-001/hoslam-evidence-and-unknowns.md)
- Updated `L01_*` Reconciliation & Review Packet:
  [L01_BASELINE_RECONCILIATION_REPORT.md](L01_BASELINE_RECONCILIATION_REPORT.md) | [L01_SPEC_REVIEW_PACKET.md](L01_SPEC_REVIEW_PACKET.md)
- New `L01F_*` Remediation Artifacts:
  - `docs/engineering/L01F_SPEC_REMEDIATION_REPORT.md` (this report)
  - [L01F_FINDINGS_CLOSURE_MATRIX.md](L01F_FINDINGS_CLOSURE_MATRIX.md)
  - [L01F_UPDATED_TRACEABILITY_REPORT.md](L01F_UPDATED_TRACEABILITY_REPORT.md)
  - [L01F_PROTOCOL_CONTRACT_DECISIONS.md](L01F_PROTOCOL_CONTRACT_DECISIONS.md)
  - [L01F_T4_ARCHITECTURE_DECISIONS.md](L01F_T4_ARCHITECTURE_DECISIONS.md)
  - [L01F_SPEC_DIFF_REVIEW.md](L01F_SPEC_DIFF_REVIEW.md)

---

## 3. Immutable Baseline & Four-Tier Evidence Separation

- **Baseline Candidate Verification (`l01-baseline` on clean `184175a`):** Previously executed and audited `PASS` (`11/11` checks, `195/195` selftests, `157/157` `debug`, `157/157` `pilot`, `Evidence Audit PASSED`).
- **Private Capture (`E-002` `hoslmap_20261127_075438.bin`):** **`NOT_RUN`** in `L01F` (absent from workspace; never copied to Git).
- **Physical HIL (`HIL-01`..`06`, `TEST-LOCAL-523`):** **`NOT_RUN`** in `L01F` (explicitly prohibited; requires prior PO + Lead Maintainer approval after `L04B`).

---

## 4. Post-Edit Verification Scope (`L01F`)

The post-remediation `L01F` verification command executes after the final document edit across all offline gates required by `STAGE L01F`:
1. **Git anchor & workspace isolation:** Verifies `origin/main` & `.worktrees/rapido-local-spec` `HEAD == 184175a14e5eb6659396c95624e2d3b08d1078a1` (`tree == 4a460199553cb237114895d105f4e20971f409d6`), root checkout (`51bdd70`) untouched, and deferred perf branches untouched.
2. **Allowed-path diff/status audit:** Confirms all `17` modified/added paths in `.worktrees/rapido-local-spec` fall strictly within `specs/changes/LOC-001/*`, `docs/engineering/L01_*`, and `docs/engineering/L01F_*`, with zero tracked changes outside those directories.
3. **Protected-file SHA-256 verification:** Confirms `policies/*`, `verification/run.py`, and `verification/checks/*.py` match `184175a` verbatim (`policies/checks.json == fd870ee6b2b324aa1d2bfbe84d9282df13708821cb7d291d2560d0a80a77bbc4`, `policies/checks.baseline.json == e688b6fef22290281facf91d26f42ae53ff527ca7f6b599a750d24d66873985e`).
4. **Specification hygiene (`spec-hygiene`):** Runs `python3 verification/checks/spec_validator.py` across all markdown specification files and `traceability.json`.
5. **Harness integrity (`harness-selftests`):** Runs the repository's canonical harness self-test suite cleanly with zero policy/harness edits.
6. **Cross-document & traceability consistency audit:** Verifies `10` REQs, `22` single-stage ACs matching `requirements.md` and `traceability.json` 1-for-1, `23` oracles (`TEST-LOCAL-501`..`523`) with zero orphans, all `FIND-L01R-01`..`10` marked `REMEDIATED_PENDING_INDEPENDENT_REVIEW`, and zero absolute home-directory paths or local file-URI schemes in repository markdown.
7. **Synthetic HOSLAM fixture verification:** Verifies all `7` `.bin` SHA-256 digests against `manifest.json` and runs `verify_capture.py` against `valid_mixed.bin` (`PASS`, `6` frames: checksums `1424`, `943`, `1342`, `1297`, `1168`, `1167`) and all `6` negative vectors (expected fail exits).
