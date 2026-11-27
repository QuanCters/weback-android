# L01 Baseline Reconciliation Report — Rapido R8S Local-First Initiative

- **Stage:** `L01` (`L01A` Canonical Baseline Reconciliation + `L01B` Spec-Only Proposal, updated under `L01F` `rev 1.1.0` and `L01F2` `rev 1.2.0`)
- **Date:** `2026-11-27`
- **Target Repository:** `weback-android` (`git@github.com:QuanCters/weback-android.git`)
- **Authoritative Base Commit (`origin/main`):** `184175a14e5eb6659396c95624e2d3b08d1078a1`
- **Authoritative Base Tree:** `4a460199553cb237114895d105f4e20971f409d6`
- **Active Policy Digest (`policies/checks.json`):** `fd870ee6b2b324aa1d2bfbe84d9282df13708821cb7d291d2560d0a80a77bbc4`
- **Baseline Policy Digest (`policies/checks.baseline.json`):** `e688b6fef22290281facf91d26f42ae53ff527ca7f6b599a750d24d66873985e`
- **Risk Classification:** `T4 (Critical Risk)` for overall `LOC-001` horizon (`L04A`–`L05`); `L01` / `L01F` deliverable scope is **spec-only** (zero production/policy/manifest/harness edits)

---

## 1. Git Remote, Branch & Worktree Reconciliation

### 1.1 Remote Verification & Canonical Anchor
- Executed `git fetch origin` from repository root.
- Verified `refs/remotes/origin/main` = `184175a14e5eb6659396c95624e2d3b08d1078a1` (`feat(fct-004c): controlled authentication, conservative quarantine, and single-inventory pilot UI (#13)`), matching the Stage 0 reported SHA exactly.
- Verified `184175a14e5eb6659396c95624e2d3b08d1078a1^{tree}` = `4a460199553cb237114895d105f4e20971f409d6`.

### 1.2 Untouched Root Candidate & Deferred Branches
| Branch / Worktree | Commit SHA | Status & Action Taken |
|---|---|---|
| Root checkout (`fct-004c-candidate`) | `51bdd70d0314a1bdf3a13a670ec5d418fc74879b` | **Untouched.** Only untracked `docs/rapido_spec_pack/` present; zero edits, zero resets, zero merges. |
| Local `main` | `bc7f6d6589b4962c092574d998f6dfce532b17c7` | **Untouched.** Behind `origin/main`; not fast-forwarded or reset. |
| `perf/opt-a-ci-diagnostic-cache` (`.worktrees/perf-001-pr-a`) | `00a258d` | **Deferred & untouched.** |
| `perf/opt-b-gradle-harness-tests` (`.worktrees/perf-001-pr-b`) | `c1d0804` | **Deferred & untouched.** |
| **New Isolated Worktree (`.worktrees/rapido-local-spec`)** | **`184175a14e5eb6659396c95624e2d3b08d1078a1`** | Created new branch `spec/rapido-local-first` cleanly from verified `origin/main` (`.worktrees/` ignored via `.git/info/exclude`; zero collision). |

### 1.3 Divergence Between Canonical `origin/main` (`184175a`) and Rejected `fct-004c-candidate` (`51bdd70`)
`git diff --stat 184175a14e5eb6659396c95624e2d3b08d1078a1 51bdd70d0314a1bdf3a13a670ec5d418fc74879b` shows `30` changed files (`3,194` insertions, `5,405` deletions):
- Rejected `51bdd70` omitted `ExternalPilotAuthorization.kt`, `PilotDeviceAcceptanceInstrumentationTest.kt`, `Fct004CApprovedOperationScopeTest.kt`, `Fct004CDurableConsumptionFaultTest.kt`, and `Fct004CExternalApprovalTrustBoundaryTest.kt`.
- **Stage 0 Test-Count Erratum Resolved:** Earlier Stage 0 observations reflected `51bdd70`. Fresh execution on canonical `origin/main` (`184175a`) confirms `195/195` harness selftests, `157/157` `testDebugUnitTest`, and `157/157` `testPilotUnitTest`.

---

## 2. Governance, Policy Digests & Gate Semantics (Verified on `184175a`)

### 2.1 Protected File SHA-256 Digests
| Asset | SHA-256 Digest |
|---|---|
| `policies/checks.json` | `fd870ee6b2b324aa1d2bfbe84d9282df13708821cb7d291d2560d0a80a77bbc4` |
| `policies/checks.baseline.json` | `e688b6fef22290281facf91d26f42ae53ff527ca7f6b599a750d24d66873985e` |
| `policies/quality-gates.md` | `47e213919fc56746ab065664d53de8c282162e176658549c6ba2ac76f8158ba1` |
| `policies/risk-tiers.md` | `19480f3778d585e698636316b2b59193a4961e7b3b5c01dfda71a789a3c3a9f0` |
| `policies/verification-integrity.md` | `fb21fe7b2686d70184e46e0f7dcbddca34560a1060915c33713be76060962596` |
| `verification/run.py` | `4587209ce3702a863abe71fdfa034fffc1886b09d3f117d9179caf1bd3e28baa` |
| `verification/checks/apk_validator.py` | `ada34d5d3de80663322d196e2908bd10cc14438bcba30b2650f7c546a6db7237` |
| `verification/checks/transition.py` | `0746e2970e083a984e15c8cea948969e0ee01004c12e0f49a23f1a974887ec91` |
| `verification/checks/spec_validator.py` | `b7414949cd8e4450fce13f83b00f4c62c901c193c707f741cb779c3ec50421f7` |
| `verification/checks/evidence.py` | `11a956e230781bbaef69435b5d5987fb93db0af5ec11dba513015d3bf06ce260` |

### 2.2 Gate & Authority Semantics (`POL-GATE-01`, `POL-RISK-01`, `transition.py`)
- **G0 (Scope Ready):** Product Owner approves problem, scope, non-goals, and Risk Tier (`T0`–`T4`).
- **G1 (Spec Approved):** Product Owner approves persistent `REQ-*` IDs, `AC-*` criteria, and zero unresolved blocking ambiguities.
- **G2 (Design Ready):** Lead Maintainer approves interfaces, FSM, error recovery, and Threat Model (`T3`/`T4`).
- **G3 (Implementation Ready):** Lead Maintainer & Verification Agent verify vertical slice tasks (`tasks.md`), independent test oracles (`validation.md`), and complete traceability (`traceability.json`).
- **G4 (Verification Passed):** 100% required checks in `policies/checks.json` pass on a clean checkout (`--lane candidate`); `SKIP`, `ERROR`, `NOT_RUN`, or `TIMEOUT` blocks the gate. `G4 PASS` never skips `G5` or `G6`.
- **G5 (Review Passed):** Multi-perspective independent read-only review closes all P0/P1 findings.
- **G6 (Merge Authorized):** Lead Maintainer approves squash-merge to `main`.
- **G7/G8 (Release Authorized / Verified):** Product Owner (+ Release Operator for `G8`) approves release APK, SHA sidecar, UAT, and target smoke observation.
- **T4 Policy Transitions (`transition.py` + `evidence.py`):** Protected edits to `policies/` or `verification/` require `TRANSITION_AUTHORIZATION` JSON binding `candidate_sha`, `target_base_sha`, `candidate_tree_sha`, `canonical_policy_sha256`, `retired_policies`, and full `required_checks` (preserving `spec-hygiene` and `harness-selftests`), evaluated under explicit `--event-name pull_request|push` with post-squash-merge tree SHA and single-parent provenance checks.

### 2.3 Inspected Build, Composition-Root & Manifest Hardening (`184175a`)
- `app/build.gradle.kts`: `compileSdk = 35`, `minSdk = 31`, `targetSdk = 35`, `versionName = "0.1.0-alpha"`, Java/Kotlin JVM `17`, Gradle `8.10.2`, AGP `8.7.2`, Kotlin `2.1.21`, Compose BOM `2024.10.01`, OkHttp `4.12.0`, `kotlinx-serialization-json 1.7.3`, explicit `testBuildType = "pilot"`, and separate `debugImplementation` / `pilotImplementation` declarations for `androidx.compose.bom` and `androidx.compose.ui.test.manifest`.
- `app/src/main/AndroidManifest.xml`: `com.weback.robot`, `usesCleartextTraffic="false"`, `allowBackup="false"`, `networkSecurityConfig="@xml/network_security_config"`, **zero** `INTERNET` or external permissions.
- `app/src/pilot/AndroidManifest.xml`: `com.weback.robot.pilot`, adds **solely** `android.permission.INTERNET` (`PILOT_INTERNET_CAPABLE = true`).
- `AuthorizedPilotEndpointTrustPolicy` & `PilotIpAddressIsolationTest`: HTTPS/WSS `:443` only, `Proxy.NO_PROXY`, no redirects, special-use IPv4/IPv6 CIDR filter blocking loopback, RFC1918, CGNAT, link-local, ULA, multicast, and IPv4-mapped IPv6.
- `DefaultAppContainer` & `MainActivity` (`app/src/main/`): On `184175a`, `MainActivity` references `app.container.pilotSessionController` and `DefaultAppContainer` directly constructs cloud pilot gateways, WS transport, and `KeystoreAeadTokenStore`; `LOC-001` (`L04A`) decouples this via `VariantAppContainerFactory`, `PilotCapabilityBridge?`, and `src/cloudPilot/` vs `src/local/` source-set separation so `com.weback.robot.local` never compiles or instantiates cloud factories.

---

## 3. Canonical Baseline Verification Results (`run_id = l01-baseline`)

The `l01-baseline` run executed on clean `.worktrees/rapido-local-spec` at exact canonical commit `184175a14e5eb6659396c95624e2d3b08d1078a1` prior to creating any `LOC-001` files:

### 3.1 Observed Check Table (`artifacts/verification/l01-baseline/report.json`)
- **Pre/Post Cleanliness (`git status --porcelain --untracked-files=all`):** Empty (`CLEAN`).
- **Overall Verdict:** `PASS` | **Evidence Audit:** `Evidence Audit PASSED` (`2026-11-27T07:21:31Z` – `2026-11-27T07:31:42Z`, Python `3.12.3`, Linux x86_64).

| Check ID | Status | Exit | Duration (s) | Observed Metrics / Artifact Provenance |
|---|---|---:|---:|---|
| `spec-hygiene` | `PASS` | `0` | `0.16` | 65 markdown files audited, all existing change specs traced |
| `harness-selftests` | `PASS` | `0` | `18.13` | `195/195` passed (`0` failed, `0` errors, `0` skipped) |
| `gradle-wrapper-validation` | `PASS` | `0` | `0.06` | Gradle `8.10.2` wrapper JAR + distribution SHA-256 verified |
| `android-lint` | `PASS` | `0` | `158.96` | `lintDebug` zero errors |
| `android-unit-tests` | `PASS` | `0` | `127.52` | `157/157` passed (`testDebugUnitTest`, `0` skipped) |
| `android-build` | `PASS` | `0` | `36.68` | `assembleDebug` exit `0` |
| `apk-validation` | `PASS` | `0` | `6.12` | `app-debug.apk` (`25,366,637` B, `com.weback.robot`, SHA-256 `af34eeb2b8fabadc97dc23d5284f806ab59fac7e98ccc4e56b07e0b1bfeeff8f`) |
| `pilot-android-lint` | `PASS` | `0` | `127.44` | `lintPilot` zero errors |
| `pilot-android-unit-tests` | `PASS` | `0` | `117.09` | `157/157` passed (`testPilotUnitTest`, `0` skipped) |
| `pilot-android-build` | `PASS` | `0` | `34.98` | `assemblePilot` exit `0` |
| `pilot-apk-validation` | `PASS` | `0` | `5.75` | `app-pilot.apk` (`25,366,689` B, `com.weback.robot.pilot`, SHA-256 `ccf6ea4071bb6eb7ac5c8347354a6edf67eeedce28f2e7d5fc4414170ecd31d2`) |

---

## 4. Synthetic HOSLAM Fixture Verification & Private Oracle Scope

### 4.1 Synthetic Fixture Reproduction (`docs/rapido_spec_pack/fixtures/` — Clean-Room Vectors Only)
- Verified SHA-256 digests for `valid_mixed.bin` (`631aa7fabc331da5b9245a193a4f00c0a242096e487db96dfca2707835a46fe5`) and all 6 negative vectors against `manifest.json`.
- Executed `python3 docs/rapido_spec_pack/tools/verify_capture.py`:
  - `valid_mixed.bin`: `PASS` (`220` bytes, `counts: {map: 4, aux: 1, trajectory: 1}`, `dimensions: {2x2: 1, 3x1: 1, 2x1: 1, 1x1: 1}`, `padding: {0: 1, 1: 1, 2: 1, 3: 1}`, `unique_rasters: 4`).
  - Negative vectors (all exited `1` with expected diagnostic): `bad_checksum.bin`, `bad_padding.bin`, `truncated.bin`, `oversized_header.bin`, `unknown_magic.bin`, `zero_width.bin`.

### 4.2 Private Capture Oracle Status (`E-002`)
- **`hoslmap_20261127_075438.bin` (`f31a89d74346ec7f9d5e74970d454cd69c3a66759dff907d6150cfaeeb41358a`):** **`NOT_RUN`** in Stage L01 and Stage L01F (no authorized private file path supplied; never searched or copied into repository).
- **Physical HIL Tests (`HIL-01`..`HIL-06`):** **`NOT_RUN`** (explicitly prohibited in Stage L01 and Stage L01F).

---

## 5. Technical Conflicts Reconciled & Repository Path Crosswalk

### 5.1 Stage 0 ZLIB Correction vs. Existing Cloud Specs
- **Existing Cloud Baseline (`specs/tech-stack.md`, `specs/roadmap.md` `FCT-005`, `docs/reference/RE_BASELINE.md` `REG-04`):** Documents `ZlibMapDecoder` / `java.util.zip.Inflater` for legacy **cloud** map packets.
- **Stage L01 / L01F / L01F2 Reconciliation:** Stage 0 extrapolated `ZLIB`/`Inflater` onto HOSLAM LAN TCP 9999. Capture `E-002` proves `hoslmap\0` frames carry **uncompressed raw unsigned 8-bit grayscale bytes starting at offset 36**. `LOC-001` specifies zero `Inflater`/ZLIB/LZ4 calls for HOSLAM TCP while leaving `REG-04` / `FCT-005` untouched for the separate unverified cloud map path.

### 5.2 Pack-to-Repository Path & ID Crosswalk (`L01F2` `rev 1.2.0`)
| Draft Spec Pack Artifact / ID | Reconciled Canonical Repository Path / ID |
|---|---|
| `MASTER_SPECIFICATION.md` (`LF-001`..`LF-015`, `NF-001`..`NF-008`, `ADR-L001`..`L010`) | [specs/changes/LOC-001/proposal.md](../../specs/changes/LOC-001/proposal.md) & [requirements.md](../../specs/changes/LOC-001/requirements.md) (`REQ-LOCAL-501`..`510`, `AC-501.1`..`AC-510.3`, `22` single-stage ACs) |
| `SYSTEM_ARCHITECTURE.md` | [specs/changes/LOC-001/design.md](../../specs/changes/LOC-001/design.md) & [risk-assessment.md](../../specs/changes/LOC-001/risk-assessment.md) |
| `HOSLAM_PROTOCOL_SPEC.md`, `EVIDENCE_REGISTER.md`, `UNKNOWN_REGISTER.md` | [specs/changes/LOC-001/hoslam-evidence-and-unknowns.md](../../specs/changes/LOC-001/hoslam-evidence-and-unknowns.md) |
| `IMPLEMENTATION_ROADMAP.md`, `task.md` (`L01`..`L07+`) | [specs/changes/LOC-001/tasks.md](../../specs/changes/LOC-001/tasks.md) (`Task 1`..`Task 8`) |
| `VERIFICATION_HARNESS_SPEC.md` (`V-*`, `HIL-*`) | [specs/changes/LOC-001/validation.md](../../specs/changes/LOC-001/validation.md) (`TEST-LOCAL-501`..`523`) & [traceability.json](../../specs/changes/LOC-001/traceability.json) |
| T4 `local` variant & policy delta | [specs/changes/LOC-001/t4-transition-proposal.md](../../specs/changes/LOC-001/t4-transition-proposal.md) |
| `L01` reconciliation & handoff | `docs/engineering/L01_BASELINE_RECONCILIATION_REPORT.md` (this file) & [L01_SPEC_REVIEW_PACKET.md](L01_SPEC_REVIEW_PACKET.md) |
| `L01F` remediation dossier (`FIND-L01R-01`..`10`) | [L01F_SPEC_REMEDIATION_REPORT.md](L01F_SPEC_REMEDIATION_REPORT.md), [L01F_FINDINGS_CLOSURE_MATRIX.md](L01F_FINDINGS_CLOSURE_MATRIX.md), [L01F_UPDATED_TRACEABILITY_REPORT.md](L01F_UPDATED_TRACEABILITY_REPORT.md), [L01F_PROTOCOL_CONTRACT_DECISIONS.md](L01F_PROTOCOL_CONTRACT_DECISIONS.md), [L01F_T4_ARCHITECTURE_DECISIONS.md](L01F_T4_ARCHITECTURE_DECISIONS.md), [L01F_SPEC_DIFF_REVIEW.md](L01F_SPEC_DIFF_REVIEW.md) |
| `L01F2` focused freeze dossier (`FIND-L01R2-01`..`07`) | [L01F2_REMEDIATION_REPORT.md](L01F2_REMEDIATION_REPORT.md), [L01F2_FINDINGS_CLOSURE_MATRIX.md](L01F2_FINDINGS_CLOSURE_MATRIX.md), [L01F2_VERIFICATION_REPORT.md](L01F2_VERIFICATION_REPORT.md) |

---

## 6. Stage L01F / L01F2 Scope & Post-Edit Validation

- Under Stages `L01F` and `L01F2`, remediation edits touched solely `specs/changes/LOC-001/*`, `docs/engineering/L01_*`, `docs/engineering/L01F_*`, and `docs/engineering/L01F2_*` inside `.worktrees/rapido-local-spec` (`spec/rapido-local-first`), leaving `policies/`, `verification/`, `.github/`, `app/`, `gradle/`, root `AGENTS.md`, deferred perf branches, and rejected `51bdd70` completely unmodified.
- All post-remediation validation results are recorded in [L01F2_VERIFICATION_REPORT.md](L01F2_VERIFICATION_REPORT.md) and [L01F2_FINDINGS_CLOSURE_MATRIX.md](L01F2_FINDINGS_CLOSURE_MATRIX.md).
