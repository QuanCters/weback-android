# Change Proposal: LOC-001 — Rapido R8S Local-First HOSLAM Read-Only Map Capability

- **Change ID:** `LOC-001`
- **Revision:** `1.2.0 (Stage L01F2 Focused Remediation & Freeze for L01R3 Review)`
- **Initiative / Planning Aliases:** Rapido R8S Local-First (`L01` -> `L01F` -> `L01F2` Specification Freeze; planned progression `L02A`..`L06`, `L07+` research)
- **Canonical Base SHA:** `184175a14e5eb6659396c95624e2d3b08d1078a1` (`origin/main`, tree `4a460199553cb237114895d105f4e20971f409d6`)
- **Risk Ceiling:** `T4 (Critical Risk — Future Isolated Variant, Permission Policy Transition, and Explicit LAN Transport)` per [policies/risk-tiers.md](../../../policies/risk-tiers.md) (`POL-RISK-01`)
- **Governance Links:** [specs/constitution.md](../../constitution.md) | [specs/roadmap.md](../../roadmap.md) | [specs/tech-stack.md](../../tech-stack.md) | [specs/deployment.md](../../deployment.md) | [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md)
- **Status:** `REMEDIATED_FOR_L01R3_INDEPENDENT_REVIEW` *(Stage L01F2 spec-only remediation; NOT signed authorization for T4 policy change, production code, live network contact, or hardware control)*

---

## 1. Problem Statement

The canonical `weback-android` repository (`origin/main` at `184175a14e5eb6659396c95624e2d3b08d1078a1`) provides a clean-room offline robot simulator (`com.weback.robot`, `FCT-002`) and a strictly bounded, default-disabled cloud connectivity pilot (`com.weback.robot.pilot`, `FCT-004C`). Owner-operated Rapido R8S diagnostics require a read-only LAN map viewer capable of decoding and displaying observed HOSLAM TCP frames (`hoslmap\0`, `hoslcac\0`, `hosltraj`) without relying on vendor cloud availability or weakening existing offline and cloud pilot trust boundaries.

This specification reconciles the Rapido R8S Local-First draft specification pack into the repository's Verification-First SDD 2.0 change-spec framework on exact canonical `origin/main` while:
1. Leaving the rejected `FCT-004C` candidate workspace (`51bdd70d0314a1bdf3a13a670ec5d418fc74879b`) completely untouched.
2. Deferring both unmerged CI/Gradle performance branches (`perf/opt-a-ci-diagnostic-cache`, `perf/opt-b-gradle-harness-tests`).
3. Preserving the existing offline simulator (`com.weback.robot`) and cloud pilot (`com.weback.robot.pilot`) unchanged, while enforcing true structural source-set & DEX isolation for the future `com.weback.robot.local` variant from its initial `L04A` T4 introduction.
4. Correcting the Stage 0 ZLIB assumption for HOSLAM TCP 9999.
5. Keeping physical robot control, uncalibrated trajectory/pose overlays, semantic telemetry, and WAN-independence claims explicitly unsupported until independently verified.

---

## 2. Stage 0 Errata & Baseline Reconciliation

### 2.1 Correction of Stage 0 ZLIB Assumption (HOSLAM TCP vs. Cloud ZLIB)
- **Erratum:** Earlier Stage 0 planning extrapolated the legacy cloud map decompression finding (`REG-04` in [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md) and `ZlibMapDecoder` / `java.util.zip.Inflater` in [specs/tech-stack.md](../../tech-stack.md) and `FCT-005` in [specs/roadmap.md](../../roadmap.md)) onto the Rapido R8S HOSLAM LAN TCP stream.
- **Empirical Evidence (`E-002`):** Byte-level inspection of the owner-supplied Windows capture (`hoslmap_20261127_075438.bin`, `4,602,152` bytes, `230` frames: `77` map, `77` aux, `76` trajectory) confirmed that `hoslmap\0` frames encode **uncompressed raw unsigned 8-bit grayscale raster bytes starting directly at offset 36** (`36 + W * H` bytes, followed by `0..3` zero padding bytes to a 4-byte boundary and a 4-byte u32 LE modular byte-sum checksum).
- **Resolution:** The local HOSLAM codec (`LOC-001` / `L02`+) **MUST NOT** invoke `java.util.zip.Inflater`, ZLIB, or LZ4 on the HOSLAM TCP stream. Existing `REG-04` / `FCT-005` cloud ZLIB documentation remains preserved strictly for the separate, unverified cloud map protocol path and is unaffected by `LOC-001`.

### 2.2 Baseline Test-Count & State Reconciliation
- Stage 0 notes referenced test counts and diffs observed against the rejected `FCT-004C` candidate workspace (`51bdd70d0314a1bdf3a13a670ec5d418fc74879b`), which differs from canonical `origin/main` (`184175a14e5eb6659396c95624e2d3b08d1078a1`) across 30 files.
- Fresh canonical baseline verification on `184175a14e5eb6659396c95624e2d3b08d1078a1` (`run_id = l01-baseline`) established the actual baseline: `11/11` required checks PASS, `195/195` harness selftests PASS, `157/157` `testDebugUnitTest` PASS, `157/157` `testPilotUnitTest` PASS, and `Evidence Audit PASSED`. See [L01 Baseline Reconciliation Report](../../../docs/engineering/L01_BASELINE_RECONCILIATION_REPORT.md), [L01F Spec Remediation Report](../../../docs/engineering/L01F_SPEC_REMEDIATION_REPORT.md), and [L01F2 Remediation Report](../../../docs/engineering/L01F2_REMEDIATION_REPORT.md).

---

## 3. Proposed Architecture Decision Records (`ADR-L001` .. `ADR-L010`)

All ADRs below are **proposed** for G0–G2 human review; none constitute enacted authorization.

| ADR ID | Proposed Choice | Rejected Alternative & Rationale |
|---|---|---|
| **`ADR-L001`** | Branch new isolated worktree from exact verified canonical `origin/main` (`184175a14e5eb6659396c95624e2d3b08d1078a1`) | Using rejected `51bdd70` or stale local `main` (`bc7f6d6`) diverges from accepted canonical hardening. |
| **`ADR-L002`** | Propose a dedicated `local` buildType (`com.weback.robot.local`) with variant source-set composition roots (`DefaultPilotContainer` + `PilotCapabilityBridgeImpl` in `src/cloudPilot/` wired to `debug`/`pilot` ONLY; `OfflineAppContainer` / `LocalAppContainer` in `src/local/` with `pilotCapabilityBridge = null`), preserving existing tested behavior and regression coverage across variants under an explicit `L04A` test migration inventory | Extending `pilot` (`com.weback.robot.pilot`) conflates cloud `:443` (RFC1918 blocked) with local TCP `9999`; gating solely via `BuildConfig` booleans in `src/main/DefaultAppContainer` leaves cloud OkHttp/WS/Keystore classes inside `app-local.apk` DEX; claiming `src/test/java/` stays untouched while removing cloud properties from `AppContainer` breaks compilation (`FIND-L01R-03`, `FIND-L01R2-01`). |
| **`ADR-L003`** | Preserve existing offline simulator (`com.weback.robot`) and cloud pilot (`com.weback.robot.pilot`, frozen for this initiative), with zero auto-fallback across cloud/simulator/local | Removing pilot discards tested FCT-004C work; auto-fallback between cloud/local/simulator creates false provenance. |
| **`ADR-L004`** | Decode HOSLAM `hoslmap\0` raster as raw unsigned 8-bit grayscale at offset 36 without `Inflater` | Stage 0 ZLIB assumption contradicted by `E-002`; keep cloud ZLIB separate until cloud map capture exists. |
| **`ADR-L005`** | Define structurally receive-only `LocalTelemetryStreamSource` without `RobotTransport.sendCommand` or socket output stream | Wrapping `RobotTransport` risks accidental command dispatch or misleading command state. |
| **`ADR-L006`** | Keep stock robot firmware untouched; zero installer/flashing/Zigma Free deployment in this plan | Unverified ABI/hardware compatibility and unauthorized device modification risks bricking or safety issues. |
| **`ADR-L007`** | Extend canonical `verification/run.py`, `policies/checks.json`, `policies/checks.baseline.json`, and `apk_validator.py` atomically via governed `L04A` T4 transition PR | Creating a parallel harness or unreviewed gate bypass violates `C-006` and `POL-INT-01`. |
| **`ADR-L008`** | Defer `perf/opt-a-ci-diagnostic-cache` and `perf/opt-b-gradle-harness-tests` | Avoids unrelated merge churn during baseline stabilization and specification review. |
| **`ADR-L009`** | Keep existing single `:app` Gradle module with common shared `src/test/` (retained for all offline/domain/UI/HOSLAM tests), additive `src/testCloudPilot/` (`testDebug`/`testPilot`), variant `src/testLocal/`, shared `SecretRedactor.kt` untouched, manual `AppContainer` DI, and `DomainPurityTest` | Multi-module rewrite adds build complexity unnecessary for local MVP, while additive `testCloudPilot` + unmodified `src/test/` avoids `setSrcDirs` test exclusion and keeps shared cloud/security code undisturbed (`FIND-L01R2-02`, `FIND-L01R2-05`). |
| **`ADR-L010`** | Deliver in small vertical staged PRs (`L01` -> `L02A` -> `L02B` -> `L03A` -> `L04A` -> `L04B` -> `L05` -> `L06`), with 1 AC mapped to 1 owning stage | Big-bang implementation cannot safely validate an unauthenticated empirical LAN protocol; cross-stage ACs obscure readiness gates (`FIND-L01R-01`). |

---

## 4. Capability Crosswalk (`LF-001`..`LF-015` -> `REQ-LOCAL-501`..`510`) & Scope Boundaries

| Pack Capability | Target Milestone | Target Status | Canonical Requirement(s) |
|---|---|---|---|
| `LF-001` Existing offline simulator | Baseline (`184175a`) + `L03A` non-fallback | Preserve (`PASS`) | `REQ-LOCAL-501` (`AC-501.1`, `AC-501.2`, `AC-501.3`) |
| `LF-002` Existing cloud pilot | Baseline (`184175a`) + `L04A`/`L04B` isolation | Preserve, frozen (`PASS`) | `REQ-LOCAL-501`, `REQ-LOCAL-506` |
| `LF-003` Decode captured HOSLAM frames | `L02A` (`Task 2`) + `L02B` (`Task 3`) | Capture-verified framing -> Implement | `REQ-LOCAL-502`, `REQ-LOCAL-503` |
| `LF-004` Offline replay map viewer | `L03A` (`Task 4`) | Offline verified | `REQ-LOCAL-505` |
| `LF-005` Explicit LAN endpoint (`RFC1918:9999`) | `L04A` (`Task 5`) + `L04B` (`Task 6`) | Proposed (requires T4 transition) | `REQ-LOCAL-506`, `REQ-LOCAL-507` |
| `LF-006` Live read-only map observation | `L04B` (`Task 6`) -> `L05` (`Task 7`) | Proposed (HIL gated) | `REQ-LOCAL-507`, `REQ-LOCAL-508`, `REQ-LOCAL-510` |
| `LF-007` Connection / last valid frame / staleness | `L03A` (`Task 4`) / `L04B` (`Task 6`) | Proposed | `REQ-LOCAL-505`, `REQ-LOCAL-508` |
| `LF-008` Pan / zoom / reset view | `L03A` (`Task 4`) | Proposed | `REQ-LOCAL-505` |
| `LF-009` Trajectory & aux structural validation | `L02B` (`Task 3`) | Structure observed (no pose overlay) | `REQ-LOCAL-504` |
| `LF-010`..`LF-013` Pose overlay, telemetry, controls, scheduling/rooms | `L03A` non-support + `L07+` Research (`Task 8`) | Explicitly **UNSUPPORTED** | `REQ-LOCAL-510` (`AC-510.1`, `AC-510.3`) |
| `LF-014` Disconnected WAN operation | `L06` (`Task 8`) | Unverified (`HIL-06` gated) | `REQ-LOCAL-510` (`AC-510.3`) |
| `LF-015` Zigma Free compatibility | `L03A` non-support + Research (`Task 8`) | **UNKNOWN** / Excluded | `REQ-LOCAL-510` (`AC-510.1`, `AC-510.3`) |

### Explicit Non-Goals (Stage L01/L01F and MVP Read-Only Horizon)
- No production Kotlin code, test code, Gradle variant edits, manifest edits, or `policies/` / `verification/` edits in Stage `L01` / `L01F`.
- No live socket contact, cloud login probes, packet writes to TCP 9999, discovery scans, or physical robot commands.
- No proprietary legacy code, native `.so` binaries, or household capture/map pixels in Git or public artifacts.
- No metric coordinate, room semantic, battery/suction/water, or robot trajectory claims without closed research evidence (`U03`..`U07`).

---

## 5. Document Index

- [requirements.md](requirements.md) — Functional, security, and governance requirements (`REQ-LOCAL-501` .. `REQ-LOCAL-510`, `AC-501.1` .. `AC-510.3`).
- [design.md](design.md) — Clean-room architecture, variant composition roots, exact `HoslamStreamFramer` contract, complete FSM & retention rules, and `LocalLanTrustPolicy`.
- [risk-assessment.md](risk-assessment.md) — Threat ledger (`THREAT-501`..`508`), privacy controls, and residual risk analysis.
- [tasks.md](tasks.md) — Staged vertical-slice decomposition (`Task 1` .. `Task 8`, `L01`..`L07+`) with exact allowed paths and hash-verified synthetic fixture import procedure.
- [validation.md](validation.md) — Independent test oracles (`TEST-LOCAL-501` .. `TEST-LOCAL-523`), four-tier evidence model, isolated mutation-kill protocol, and HIL plan.
- [traceability.json](traceability.json) — Machine-readable requirement-to-task-to-test traceability matrix (`22` ACs, `1` AC -> `1` stage owner).
- [t4-transition-proposal.md](t4-transition-proposal.md) — Non-executing `L04A` T4 policy, Gradle composition-root, and 3-way `apk_validator.py` transition proposal.
- [hoslam-evidence-and-unknowns.md](hoslam-evidence-and-unknowns.md) — Preserved empirical evidence register (`E-001`..`E-007`), wire details, and unknown register (`U01`..`U12`).
