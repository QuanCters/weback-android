# Stage L04A-S Specification & Inventory Reconciliation Report (`LOC-001` rev `1.3.0`)

- **Stage:** `L04A-S` — Governed `T4` Inventory Reconciliation (Specification-Only Amendment)
- **Change ID:** `LOC-001` (`Revision 1.3.0`)
- **Risk Classification:**
  - **Amendment PR Risk:** `T2 / Spec-Only Governance Review` (touches `specs/changes/LOC-001/*` and `docs/engineering/L04A_S_*` only; zero edits to `policies/`, `verification/`, `app/`, `gradle/`, or `.github/`).
  - **Target Implementation Risk (`Task 5` / `L04A`):** Remains strictly **`T4 (Critical Risk)`** per [POL-RISK-01](../../policies/risk-tiers.md); this specification amendment does **not** authorize `L04A` implementation, `T4` policy activation, or `TRANSITION_AUTHORIZATION`.
- **Required Gates for This Amendment:** Specification-amendment review (`G5` independent review) and PO/Lead specification merge authorization (`G6`), prior to any separate `L04A` `G3`/`T4` implementation authorization.
- **Canonical Anchor (`origin/main`):** `948c0b23a70a73a83bf2d8cb02e88e071618e2d4` (`tree` `397db62c2a865c25390e3dd4fb6ec3901257502d`, PR `#20`, accepted `L03A-R2` candidate `10bba30269a93d87f6a8ef38b57a734449f67c49`)
- **Active Policy Digest (`policies/checks.json`):** `fd870ee6b2b324aa1d2bfbe84d9282df13708821cb7d291d2560d0a80a77bbc4` (unmodified)
- **Baseline Policy Digest (`policies/checks.baseline.json`):** `e688b6fef22290281facf91d26f42ae53ff527ca7f6b599a750d24d66873985e` (unmodified)
- **Isolated Spec Worktree / Branch:** `.worktrees/loc-001-l04a-spec` (`spec/loc-001-l04a-inventory-reconciliation`)

---

## 1. Phase 0 — Governance, Provenance & Baseline Verification

1. **Historical Failed Pre-Audit Run Preserved Intact:**
   - `.worktrees/loc-001-l04a-audit/artifacts/verification/l04a-pre-audit/report.json` remains preserved as **`FAIL` (`10/11`)** (`harness-selftests` failed with `ModuleNotFoundError: No module named 'yaml'` because default `python3` on `PATH` resolved to a `.venv` Python `3.14.0` lacking `PyYAML`). It is never relabeled `PASS`.
   - Separate isolated execution of `verification/checks/test_adapter.py --discover verification/selftests` under system Python `3.12.3` (`PyYAML 6.0.3`) passed **`195/195`** (`0` failures, `0` errors, `0` skipped).
2. **Clean Canonical Baseline Verification (`run_id = l04a-s-baseline`):**
   - Executed on clean `origin/main` (`948c0b23a70a73a83bf2d8cb02e88e071618e2d4`, tree `397db62c2a865c25390e3dd4fb6ec3901257502d`) inside `.worktrees/loc-001-l04a-spec` with system `/usr/bin/python3` (`3.12.3`, `PyYAML 6.0.3`) and zero modifications to `verification/` or `policies/`:
   - Required outcome: `11/11` checks `PASS`, `195/195` `harness-selftests`, `241/241` `testDebugUnitTest`, `241/241` `testPilotUnitTest`, `apk-validation` `PASS`, `pilot-apk-validation` `PASS`, and `evidence.py --require-candidate` `PASS`.
3. **Preserved Workspaces:** Root checkout (`fct-004c-candidate`), `.worktrees/loc-001-l04a-audit`, and all historical worktrees remain untouched.

---

## 2. Phase 1 & Phase 2 — Reconciled Test & JUnit Identity Inventory

Full machine-generated records reside in [L04A_S_TEST_MIGRATION_INVENTORY.json](L04A_S_TEST_MIGRATION_INVENTORY.json) and [L04A_S_JUNIT_IDENTITY_MAPPING.json](L04A_S_JUNIT_IDENTITY_MAPPING.json).

| Inventory Slice | Source Files | Test Classes | Helper Files | Generated JUnit XMLs | Executed Tests |
|---|---:|---:|---:|---:|---:|
| **Legacy (`184175a`) Total** | `65` | `64` | `1` | `64` | `157` |
| — Legacy Common (`src/test/`) | `20` | `19` | `1` (`TestDispatcherProvider.kt`) | `19` | `67` |
| — Legacy Cloud/Pilot (`src/testCloudPilot/`) | `45` | `45` | `0` | `45` | `90` |
| **New `L02A`–`L03A` (`948c0b2`) Total** | `17` | `16` | `1` | `16` | `84` |
| — New Cloud-Independent (`src/test/`) | `15` | `14` | `1` (`HoslamTestFixtures.kt`) | `14` | `81` |
| — New `L03A` Cloud-Dependent Delta (`src/testCloudPilot/`) | `2` | `2` | `0` | `2` | `3` |
| **Current `948c0b2` Unit Test Total (`debug` & `pilot`)** | **`82`** | **`80`** | **`2`** | **`80`** | **`241`** |
| **Eligible Existing Cloud-Independent `local` Baseline** | **`35`** | **`33`** | **`2`** | **`33`** | **`148`** |
| **Instrumentation (`src/androidTest/`, `pilot` only)** | `2` | `2` | `0` | N/A | `3` (`@Test`) |

### Reconciliation of the Two Cloud-Dependent `L03A` Test Files
The historical `45`-file (`90`-test) legacy migration inventory is preserved intact. For the `2` new cloud-dependent `L03A` files (`3` tests):
1. **`app/src/test/java/com/weback/robot/app/local/LocalAppContainerNonFallbackTest.kt` (`1` test):**
   - Relocates to `app/src/testCloudPilot/java/com/weback/robot/app/local/LocalAppContainerNonFallbackTest.kt` (`DefaultAppContainer` -> `DefaultPilotContainer`), preserving the exact `debug`/`pilot` JUnit identity (`#ac501_3_defaultAppContainerExposesProviderFactoryAndResolutionOracleSeams`).
   - Paired with `app/src/testLocal/java/com/weback/robot/core/LocalAppContainerTest.kt` exercising `OfflineAppContainer` (`robotTransportProvider`, `localStreamSourceProvider`, `dependencyResolutionObserver`, `pilotCapabilityBridge == null`) without cloud stubs or `testCloudPilot` classpath.
2. **`app/src/test/java/com/weback/robot/ui/map/LocalMapActivityNavigationTest.kt` (`2` tests):**
   - Relocates to `app/src/testCloudPilot/java/com/weback/robot/ui/map/LocalMapActivityNavigationTest.kt` (`DefaultAppContainer` -> `DefaultPilotContainer`), preserving both exact `debug`/`pilot` JUnit identities (`#syntheticReplaySurvivesRecreateAndExplicitNavigationHasZeroFallbackOnEmptyAndErrorStreams` and `#ac501_3_nonFallbackOracleRequiresRealProviderAndCachedResolutionTelemetryAcrossScenarios`).
   - Paired with variant-neutral `app/src/test/java/com/weback/robot/ui/map/OfflineLocalMapActivityNavigationTest.kt` (`2` tests in shared `src/test/java/`, running in `testDebugUnitTest`, `testPilotUnitTest`, AND `testLocalUnitTest`) using `OfflineAppContainer` + `SpyRobotTransport` to verify all `4` scenarios (valid synthetic replay + `Activity.recreate()`, zero-map clean EOF, codec error before first MAP, codec error after valid MAP), explicit navigation only, zero automatic simulator fallback, truthful labels, and constructor-seam anti-vacuity.
   - `148` is the exact eligible existing cloud-independent test count (`67 + 81`), not a fixed final `testLocalUnitTest` total (`148` + `OfflineLocalMapActivityNavigationTest` + `testLocal/` suites).

---

## 3. Phase 3 & Phase 4 — Source Ownership & DEX Class-Family Reconciliation

Full machine-generated records reside in [L04A_S_SOURCE_OWNERSHIP_INVENTORY.json](L04A_S_SOURCE_OWNERSHIP_INVENTORY.json), [L04A_S_DEX_CLASS_FAMILY_INVENTORY.json](L04A_S_DEX_CLASS_FAMILY_INVENTORY.json), and [L04A_S_DEX_RULE_COVERAGE_REPORT.json](L04A_S_DEX_RULE_COVERAGE_REPORT.json).

1. **Key Ownership Resolutions (`948c0b2`):**
   - **`security/TokenStore.kt`** (`TokenStore`, `TokenSaveResult`, `TokenLoadResult`, `TokenClearResult`): Verified `100%` cloud/session-owned (`0` offline/local references); moves to `app/src/cloudPilot/java/com/weback/robot/security/TokenStore.kt`.
   - **`PilotUiWorkflow`** (`MainActivity.kt`): Private enum controlling `PilotReadinessSheet`; moves out of shared `MainActivity.kt` into `src/cloudPilot/` behind `PilotCapabilityBridge?`.
   - **`AppContainerDependency` & R1 Seams (`core/AppContainer.kt`):** Pure enum `AppContainerDependency` (zero cloud imports) remains in shared `src/main/core/AppContainer.kt` and `OfflineAppContainer` exposes `robotTransportProvider`, `localStreamSourceProvider`, and `dependencyResolutionObserver`, while `DefaultPilotContainer` retains all 5 provider seams + `dependencyResolutionObserver` in `src/cloudPilot/`.
   - **`core/BootSessionTimeProvider.kt`:** Retained in shared `src/main/core/` per [design.md](../../specs/changes/LOC-001/design.md) §1.1 (zero cloud/OkHttp/Keystore dependencies).
2. **APK DEX Provenance & Omission Reconciliation (`948c0b2`):**
   - `app-debug.apk` (`06a15a95d99884958f8e639bdbc3248f02526a106d60f43bd574e06dc6791b1e`) and `app-pilot.apk` (`91f732d762f009cbc0eea2222d2538a66541a432f266990e1cf62261e7907043`) each span `12` `.dex` files containing `810` `Lcom/weback/robot/` descriptors and **`201` unique top-level project stems** (`80` cloud-owned, `121` shared/generated).
   - Exact diff against `L01F2` `t4-transition-proposal.md` §3 found **`21` observed cloud stems not covered by the `L01F2` rule list** (`20` omitted auxiliary/result/workflow types: `4` `protocol/auth/*`, `1` `protocol/inventory/*`, `7` `ExternalPilotAuthorization`, `4` `KeystoreAeadTokenStore`, `3` `TokenStore` sealed results, `1` `PilotUiWorkflow`; plus `1` `DefaultAppContainer` which renames to `DefaultPilotContainer` in `L04A`). Earlier narrative summaries of `"18"` omitted `PilotUiWorkflow`, one `ExternalPilotAuthorization` type, and `DefaultAppContainer`.
3. **Hybrid Package-Prefix + Exact Stem `CLOUD_PILOT_DEX_RULES` (`0` Gaps, `0` False Positives):**
   - Pure cloud package prefixes: `Lcom/weback/robot/app/pilot/`, `Lcom/weback/robot/app/session/`, `Lcom/weback/robot/protocol/auth/`, `Lcom/weback/robot/protocol/inventory/`, `Lcom/weback/robot/protocol/pilot/`, `Lcom/weback/robot/protocol/ws/`, `Lcom/weback/robot/ui/pilot/`.
   - Exact `$`-aware stems for mixed `core/`, `security/`, and root `PilotUiWorkflow` (preserving `DiagnosticEvent`, `DiagnosticLogger`, `InMemoryDiagnosticLogger`, `NoOpDiagnosticLogger`, `SecretRedactor`, `AppContainer`, `OfflineAppContainer`, `BootSessionTimeProvider*`, `DispatcherProvider*`, `MonotonicClock*` unblocked).
   - Verified across all `810` `app-pilot.apk` project descriptors: **`398/398` cloud descriptors matched, `0` uncovered, `0` false positives**.
   - **Mandatory Second Reconciliation Gate:** `L04A` MUST re-audit actual built `L04A` `app-debug.apk`, `app-pilot.apk`, and `app-local.apk` DEX files before `G5`/`G6`.

---

## 4. Phase 5 & Phase 6 — Strengthened Oracles & Specification Allowlist

1. **Strengthened `TEST-LOCAL-514`, `TEST-LOCAL-521`, and `TEST-LOCAL-522` Oracles:** Added explicit coverage requirements for `protocol/{auth,inventory,ws}/` prefixes, `TokenStore` sealed result families, newly identified `ExternalPilotAuthorization`/`KeystoreAeadTokenStore` families, `PilotUiWorkflow`, shared `SecretRedactor`/`DiagnosticLogger` acceptance, 3-way substitution/`INTERNET`/`com.weback.robot.local` enforcement, runtime null `PilotCapabilityBridge`, zero hidden cloud stubs/classpath, and local behavioral non-fallback verification via `OfflineLocalMapActivityNavigationTest` + `LocalAppContainerTest`.
2. **Specification-Only File Allowlist (Zero Protected Asset Edits):**
   - Amended `specs/changes/LOC-001/` (`6` files): [design.md](../../specs/changes/LOC-001/design.md), [tasks.md](../../specs/changes/LOC-001/tasks.md), [t4-transition-proposal.md](../../specs/changes/LOC-001/t4-transition-proposal.md), [validation.md](../../specs/changes/LOC-001/validation.md), [requirements.md](../../specs/changes/LOC-001/requirements.md), [traceability.json](../../specs/changes/LOC-001/traceability.json).
   - Preserved `proposal.md`, `risk-assessment.md`, `hoslam-evidence-and-unknowns.md`, all `L02A`–`L03A` `VERIFIED` entries, `M05` equivalence, `M11b`, `TEST-LOCAL-509`, `L04B` carry-forwards, `TEST-LOCAL-523` `NOT_RUN`, and existing `11`-check policy.
   - New `docs/engineering/` deliverables (`7` files): this report, `5` JSON inventory/coverage files, and [L04A_S_INDEPENDENT_SPEC_REVIEW.md](L04A_S_INDEPENDENT_SPEC_REVIEW.md).
