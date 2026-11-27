# L01 Spec Review Packet — Rapido R8S Local-First Initiative (`LOC-001`)

- **Change ID:** `LOC-001` (`Revision 1.2.0 — Remediated & Frozen Under Stage L01F2`)
- **Stage Status:** `READY_FOR_L01R3` *(pending independent re-review of `FIND-L01R2-01`..`07`; zero self-approved gates)*
- **Canonical Base Anchor:** `origin/main` = `184175a14e5eb6659396c95624e2d3b08d1078a1` (`tree` `4a460199553cb237114895d105f4e20971f409d6`)
- **Worktree / Branch:** `.worktrees/rapido-local-spec` (`spec/rapido-local-first`)
- **Current Policy Digest (`policies/checks.json`):** `fd870ee6b2b324aa1d2bfbe84d9282df13708821cb7d291d2560d0a80a77bbc4` (unmodified)
- **Overall Risk Ceiling:** `T4 (Critical Risk)`
- **Core Spec Set:** [L01_BASELINE_RECONCILIATION_REPORT.md](L01_BASELINE_RECONCILIATION_REPORT.md) | [Proposal](../../specs/changes/LOC-001/proposal.md) | [Requirements](../../specs/changes/LOC-001/requirements.md) | [Design](../../specs/changes/LOC-001/design.md) | [Risk Assessment](../../specs/changes/LOC-001/risk-assessment.md) | [Tasks](../../specs/changes/LOC-001/tasks.md) | [Validation](../../specs/changes/LOC-001/validation.md) | [Traceability JSON](../../specs/changes/LOC-001/traceability.json) | [T4 Transition Proposal](../../specs/changes/LOC-001/t4-transition-proposal.md) | [HOSLAM Evidence & Unknowns](../../specs/changes/LOC-001/hoslam-evidence-and-unknowns.md)
- **L01F & L01F2 Dossiers:** [L01F Report](L01F_SPEC_REMEDIATION_REPORT.md) | [L01F Matrix](L01F_FINDINGS_CLOSURE_MATRIX.md) | [Updated Traceability](L01F_UPDATED_TRACEABILITY_REPORT.md) | [Protocol Decisions](L01F_PROTOCOL_CONTRACT_DECISIONS.md) | [T4 Decisions](L01F_T4_ARCHITECTURE_DECISIONS.md) | [L01F2 Remediation Report](L01F2_REMEDIATION_REPORT.md) | [L01F2 Closure Matrix](L01F2_FINDINGS_CLOSURE_MATRIX.md) | [L01F2 Verification Report](L01F2_VERIFICATION_REPORT.md)

---

## 1. Executive Review Summary

Stage `L01` anchored cleanly to verified canonical `origin/main` (`184175a14e5eb6659396c95624e2d3b08d1078a1`) and established `11/11` baseline checks (`195/195` harness selftests, `157/157` `debug` unit tests, `157/157` `pilot` unit tests, both APKs validated, `Evidence Audit PASSED`). Stage `L01F` closed six original findings (`FIND-L01R-01`, `02`, `07`, `08`, `09`, `10`). Independent `L01R2` review identified seven focused defects (`FIND-L01R2-01`..`07`), all now remediated under Stage `L01F2` (`LOC-001` rev `1.2.0`) without modifying production code, test code, Gradle configs, manifests, `policies/`, `verification/`, CI workflows, root `AGENTS.md`, or rejected candidate `51bdd70`.

---

## 2. Proposed ADRs Awaiting Decision (`ADR-L001` .. `ADR-L010`)

| ADR | Decision Summary | Decision Authority | Status |
|---|---|---|---|
| `ADR-L001` | Anchor exclusively to canonical `origin/main` (`184175a`); isolate worktree | Lead Maintainer | `PROPOSED` |
| `ADR-L002` | Dedicated `local` buildType (`com.weback.robot.local`) with variant composition roots (`DefaultPilotContainer` + `src/cloudPilot/` in `debug`/`pilot`; `OfflineAppContainer` in `local`/`release`) + approved `L04A` test migration inventory preserving regression coverage | PO + Lead Maintainer | `PROPOSED` |
| `ADR-L003` | Preserve offline simulator (`com.weback.robot`) + frozen cloud pilot (`com.weback.robot.pilot`); zero automatic fallback | Product Owner | `PROPOSED` |
| `ADR-L004` | HOSLAM `hoslmap\0` decoded as raw u8 grayscale at offset 36 (no `Inflater`/ZLIB/LZ4); cloud `REG-04` ZLIB isolated separately; `hosltraj` validated on opaque byte length with zero float parsing | Lead Maintainer | `PROPOSED` |
| `ADR-L005` | Structurally receive-only `LocalTelemetryStreamSource` without `RobotTransport.sendCommand` or socket output writes | Lead Maintainer | `PROPOSED` |
| `ADR-L006` | Keep stock firmware untouched; zero Zigma Free install/flash in this plan | Product Owner | `PROPOSED` |
| `ADR-L007` | Extend existing verification harness/policy (`policies/checks.json`, `checks.baseline.json`, `$`-aware multi-DEX `apk_validator.py`) atomically via governed `L04A` T4 transition + post-merge `vars.APPROVED_POLICY_SHA256` sync | Lead Maintainer | `PROPOSED` |
| `ADR-L008` | Defer `perf/opt-a-ci-diagnostic-cache` and `perf/opt-b-gradle-harness-tests` | Lead Maintainer | `PROPOSED` |
| `ADR-L009` | Keep single `:app` module, shared common `src/test/` across all variants + additive `src/testCloudPilot/`, untouched shared `SecretRedactor.kt`, manual `AppContainer` DI, and `DomainPurityTest` | Lead Maintainer | `PROPOSED` |
| `ADR-L010` | Small vertical staged PRs (`L02A` -> `L02B` -> `L03A` -> `L04A` -> `L04B` -> `L05` -> `L06`), strictly 1 AC per owning stage | PO + Lead Maintainer | `PROPOSED` |

---

## 3. Requirement-to-Test Traceability Summary (`10` REQs / `22` Single-Stage ACs / `23` Oracles)

| Requirement | ACs (`1` AC -> `1` Stage) | Owning Stage(s) & Task(s) | Test Oracle ID(s) | Pack Mapping | Status |
|---|---|---|---|---|---|
| `REQ-LOCAL-501` | `AC-501.1` (`L01`), `AC-501.2` (`L01`), `AC-501.3` (`L03A`) | `Task 1` (`L01`), `Task 4` (`L03A`) | `TEST-LOCAL-501`, `513`, `522` | `V-BASE`, `V-U03`, `V-R01` | `PLANNED` (`l01-baseline` 11/11 verified) |
| `REQ-LOCAL-502` | `AC-502.1` (`L02A`), `AC-502.2` (`L02A`) | `Task 2` (`L02A`) | `TEST-LOCAL-502`..`505`, `510` | `V-P01`..`V-P04`, `V-P09` | `PLANNED` (next gate `L02A`) |
| `REQ-LOCAL-503` | `AC-503.1` (`L02B`), `AC-503.2` (`L02B`) | `Task 3` (`L02B`) | `TEST-LOCAL-506`, `507`, `509`..`511` | `V-P05`, `506`, `508`..`510` | `PLANNED` (`TEST-LOCAL-509` private BIN `NOT_RUN`) |
| `REQ-LOCAL-504` | `AC-504.1` (`L02B`), `AC-504.2` (`L02B`) | `Task 3` (`L02B`) | `TEST-LOCAL-508` | `V-P07` | `PLANNED` (opaque length, `U04` unresolved) |
| `REQ-LOCAL-505` | `AC-505.1` (`L03A`), `AC-505.2` (`L03A`) | `Task 4` (`L03A`) | `TEST-LOCAL-512`, `513` | `V-U01`..`V-U03` | `PLANNED` |
| `REQ-LOCAL-506` | `AC-506.1` (`L04A`), `AC-506.2` (`L04B`) | `Task 5` (`L04A`), `Task 6` (`L04B`) | `TEST-LOCAL-514`, `515`, `522` | `V-S01`, `V-S02`, `V-R01` | `PLANNED` (requires `L04A` T4) |
| `REQ-LOCAL-507` | `AC-507.1` (`L04B`), `AC-507.2` (`L04B`) | `Task 6` (`L04B`) | `TEST-LOCAL-515`, `516` | `V-S02`, `V-S03` | `PLANNED` (requires `L04A` T4) |
| `REQ-LOCAL-508` | `AC-508.1` (`L04B`), `AC-508.2` (`L04B`) | `Task 6` (`L04B`) | `TEST-LOCAL-517`..`520` | `V-S04`, `V-T01`..`V-T03` | `PLANNED` |
| `REQ-LOCAL-509` | `AC-509.1` (`L04A`), `AC-509.2` (`L01`) | `Task 1` (`L01`), `Task 5` (`L04A`) | `TEST-LOCAL-501`, `521` | `V-BASE` | `PLANNED` |
| `REQ-LOCAL-510` | `AC-510.1` (`L03A`), `AC-510.2` (`L05`), `AC-510.3` (`L06`) | `Task 4`, `Task 7`, `Task 8` | `TEST-LOCAL-513`, `523` | `V-U03`, `HIL-01`..`06` | `NOT_RUN` (human-gated `HIL`) |

---

## 4. T4 Transition Proposal Summary (`L04A` Future PR Only)

Full details in [t4-transition-proposal.md](../../specs/changes/LOC-001/t4-transition-proposal.md) and [L01F_T4_ARCHITECTURE_DECISIONS.md](L01F_T4_ARCHITECTURE_DECISIONS.md):
- **Isolated Variant & Composition Root:** Add `local` `buildType` (`com.weback.robot.local`) with `INTERNET` only in `app/src/local/AndroidManifest.xml`, explicit `localImplementation` Compose BOM + `ui-test-manifest`, pinned `testBuildType = "pilot"`, and decoupled `VariantAppContainerFactory` + `DefaultPilotContainer`/`PilotCapabilityBridgeImpl` in `src/cloudPilot/` (wired to `debug`/`pilot`; excluded from `local`/`release`). Common `app/src/test/{java,resources}` runs across `testDebugUnitTest`, `testLocalUnitTest`, AND `testPilotUnitTest` without `setSrcDirs` exclusions, while `src/testCloudPilot/` holds the `45` relocated cloud/pilot/session test files under approved `L04A` migration inventory verified via JUnit XML identities (`gradle_test_adapter.py`). Shared `SecretRedactor.kt` stays untouched.
- **Symmetric 3-Way `$`-Aware Validator Extension:** Extend `apk_validator.py` with symmetric `offline`/`pilot`/`local` token/path rules (line 370), `$`-aware class-family matching (`cls == f"{stem};" or cls.startswith(f"{stem}$")`) + package prefixes across **all `.dex` files** covering complete `CLOUD_PILOT_DEX_RULES` and `LOCAL_LIVE_DEX_RULES`, backed by `$Companion`/coroutine/secondary-DEX selftests.
- **Policy Addition, Baseline Update & CI Sync:** Keep all `11` current checks (`fd870ee6...`), add `4` symmetric `local-*` checks (`15` total), atomically update `policies/checks.baseline.json` (`e688b6...` -> `fd870ee6...`), promote `traceability.json` provisional check bindings under `TRANSITION_AUTHORIZATION` + dual PO & Lead Maintainer approval (`TO_BE_COMPUTED_IN_L04A_PR`), and synchronize `vars.APPROVED_POLICY_SHA256` immediately post-merge prior to `L04B`.

---

## 5. Gate Approval Requests (Pending Human Decisions — Zero Minted Approvals)

- [ ] **Independent `L01R3` Review:** Confirm closure of `FIND-L01R2-01`..`07` and preservation of closed `FIND-L01R-01`, `02`, `07`, `08`, `09`, `10` (`PASS`).
- [ ] **Gate G0 (Product Owner):** Approve `LOC-001` problem scope, preserved offline simulator & frozen cloud pilot, explicit unsupported-capability boundaries (`LF-010`..`LF-015`), and overall `T4` risk ceiling.
- [ ] **Gate G1 (Product Owner):** Approve `REQ-LOCAL-501`..`REQ-LOCAL-510` and `AC-501.1`..`AC-510.3`.
- [ ] **Gate G2 (Lead Maintainer):** Approve `ADR-L001`..`ADR-L010`, clean-room codec/framer contract, variant composition-root & test source-set architecture, complete normative FSM/memory budgets, Threat Model (`THREAT-501`..`508`), and non-executing `L04A` T4 transition design.
- [ ] **Gate G3 (`L02A` / `Task 2`):** Confirm bounded `L02A` paths (common `app/src/test/java/` + synthetic-only `app/src/test/resources/hoslam/` SHA-verified import procedure) and independent oracles (`TEST-LOCAL-502`..`505`, `510`) after the approved `LOC-001` rev `1.2.0` spec PR lands cleanly on `main`.

---

## 6. Changed Paths Inventory (Worktree `.worktrees/rapido-local-spec` Only)

**Modified / Added Specification & Engineering Docs (`20` Files):**
- `docs/engineering/L01_BASELINE_RECONCILIATION_REPORT.md`
- `docs/engineering/L01_SPEC_REVIEW_PACKET.md`
- `docs/engineering/L01F_{SPEC_REMEDIATION_REPORT,FINDINGS_CLOSURE_MATRIX,UPDATED_TRACEABILITY_REPORT,PROTOCOL_CONTRACT_DECISIONS,T4_ARCHITECTURE_DECISIONS,SPEC_DIFF_REVIEW}.md`
- `docs/engineering/L01F2_{REMEDIATION_REPORT,FINDINGS_CLOSURE_MATRIX,VERIFICATION_REPORT}.md`
- `specs/changes/LOC-001/{proposal,requirements,design,risk-assessment,tasks,validation,t4-transition-proposal,hoslam-evidence-and-unknowns}.md`
- `specs/changes/LOC-001/traceability.json`

**Explicitly Untouched:** Root checkout (`51bdd70`), `policies/*`, `verification/*`, `.github/*`, `app/*`, `gradle/*`, `AGENTS.md`, `specs/changes/FCT-*`, and deferred branches.
