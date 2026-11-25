# FCT-002 Implementation Plan — Simulated Robot Control

> **Gate Status:** Updated for bounded G3 clarifications (`IC-201` through `IC-205`). Implement strictly inside isolated branch/worktree `feature/fct-002-simulated-robot-control` after PR #5 squash-merge closure.

**Goal:** Deliver typed robot control commands (`CLEAN`, `PAUSE`, `RESUME`, `DOCK`), pure finite-state transition guards with deterministic rejection precedence, explicit asynchronous `Idle -> Pending -> Acknowledged / Rejected` lifecycle with monotonic `commandId`, bounded timeout, exception/cancellation safety and stale-completion guards, unified `stateMutex` `SimulatorRobotTransport` with generation-checked virtual-time progression and `close()` lifecycle cleanup, MVI `DashboardViewModel`, accessible string-resourced Compose controls with persistent `OFFLINE` labeling, and full JVM + Robolectric Compose UI test coverage under the existing 7-check policy.

**Architecture:** Extends the existing single-module `:app` clean architecture (`core`, `domain`, `protocol`, `ui`) established in FCT-001. Pure Kotlin `domain` remains 100% decoupled from Android/coroutines/serialization dependencies (`C-002`). Coroutine determinism is achieved through injected `DispatcherProvider`, `MonotonicClock`, `SimulatorConfig`, and `stateMutex` verified under `kotlinx-coroutines-test` (`StandardTestDispatcher` / `TestScope`) and Robolectric Compose UI rules inside `testDebugUnitTest`.

**Tech Stack:** Kotlin 2.1.21, Jetpack Compose BOM 2024.10.01, Material 3 1.3.1, Coroutines 1.9.0 + Coroutines Test 1.9.0, JUnit 4.13.2, Robolectric 4.14.1 + `androidx.test.ext:junit:1.2.1` + Compose UI Test JUnit4 (`androidx.compose.ui:ui-test-junit4`) executed in JVM `android-unit-tests`. Zero changes to `policies/` or `verification/`.

**Spec:** [proposal.md](proposal.md) | [requirements.md](requirements.md) | [design.md](design.md) | [validation.md](validation.md) | [risk-assessment.md](risk-assessment.md) | [traceability.json](traceability.json)

---

## Global Constraints
- **Spec-First Enforcement:** Do NOT execute implementation tasks until PR #5 is squash-merged and isolated implementation branch `feature/fct-002-simulated-robot-control` is created from updated `main`.
- **Zero Policy/Harness Mutation:** Do NOT alter `policies/` or `verification/` scripts.
- **Zero Live Network / Hardware:** Simulator-only (`C-006`, `C-011`). Retain visible `OFFLINE` `simulator_badge`.
- **Domain Purity:** Zero `android.*`, `androidx.*`, `kotlinx.coroutines.*`, `kotlinx.serialization.*`, or `okhttp3.*` imports inside `com.weback.robot.domain` (`C-002`).
- **Preserve FCT-001 Regressions:** Retain all existing FCT-001 assertions (adapting only test-double `sendCommand` signatures where required).

---

### Task 1: Pure Kotlin Control Domain Models, Monotonic Clock & State Machine (`domain`, `core`)

**Files:**
- Create: `app/src/main/java/com/weback/robot/core/MonotonicClock.kt`
- Create: `app/src/main/java/com/weback/robot/domain/model/RobotCommand.kt`
- Modify: `app/src/main/java/com/weback/robot/domain/model/RobotMode.kt`
- Modify: `app/src/main/java/com/weback/robot/domain/model/RobotState.kt`
- Create: `app/src/main/java/com/weback/robot/domain/model/SimulatorErrorCodes.kt`
- Create: `app/src/main/java/com/weback/robot/domain/model/CommandExecutionState.kt`
- Create: `app/src/main/java/com/weback/robot/domain/model/CommandResult.kt`
- Create: `app/src/main/java/com/weback/robot/domain/model/ControlAvailability.kt`
- Create: `app/src/main/java/com/weback/robot/domain/control/RobotStateMachine.kt`

**Covers:** `REQ-CTRL-201`, `REQ-CTRL-202`, `REQ-CTRL-203` (`AC-203.1`), `REQ-CTRL-206` (`AC-206.1..3`), `REQ-CTRL-207` (`AC-207.1`).

- [ ] **Step 1 (TDD RED):** Write table-driven/boundary tests `RobotCommandTest.kt`, `RobotModeTest.kt`, `DomainPurityTest.kt`, `RobotStateMachineTest.kt` (full 8x4 mode-command matrix across battery boundaries `0, 14, 15, 100` x `errorCode` 0/9001 testing precedence `FAULT_ACTIVE > LOW_BATTERY > INVALID_TRANSITION`), `RobotStateInvariantsTest.kt`, and `ControlAvailabilityTest.kt` (all 8 modes, low-battery, fault, `Pending`). Observe compilation/test failures.
- [ ] **Step 2 (GREEN):** Implement `MonotonicClock` (`SystemMonotonicClock`), pure Kotlin `RobotCommand` (`wireStatus`), expanded `RobotMode` (`8` modes), `RobotState` (`0..100` guard, `isDocked`, `isFault`), `SimulatorErrorCodes` (`9001`, `9002`), `CommandExecutionState`, `CommandRejectionReason`, `CommandResult`, `RobotStateMachine.evaluateTransition` (with explicit 1->2->3 precedence), and `ControlAvailability.from`.
- [ ] **Step 3 (Verify):** Run `./gradlew testDebugUnitTest --tests "com.weback.robot.domain.*"` and confirm all domain tests pass with zero framework imports in `domain`.

---

### Task 2: Transport Contract, Unified Synchronization & Deterministic `SimulatorRobotTransport` (`protocol`, `core`)

**Files:**
- Modify: `app/src/main/java/com/weback/robot/protocol/RobotTransport.kt`
- Create: `app/src/main/java/com/weback/robot/protocol/SimulatorConfig.kt`
- Modify: `app/src/main/java/com/weback/robot/protocol/SimulatorRobotTransport.kt`
- Modify: `app/src/main/java/com/weback/robot/core/AppContainer.kt`
- Create: `app/src/test/java/com/weback/robot/protocol/SimulatorRobotTransportTest.kt`
- Update: `app/src/test/java/com/weback/robot/core/AppContainerTest.kt` (add lifecycle/close checks while preserving existing assertions)

**Covers:** `REQ-CTRL-205` (`AC-205.1..4`), `REQ-CTRL-206` (`AC-206.1..4`).

- [ ] **Step 1 (TDD RED):** Write `SimulatorRobotTransportTest.kt` using `runTest` + `StandardTestDispatcher` + fake `MonotonicClock` covering: `sendCommand(RobotCommand): CommandResult`, `stateMutex` concurrency serialization without holding lock across `delay`, explicit commit-point guard revalidation, command-vs-tick race (`CLEAN` drain crossing `15%` or fault injection during `ackDelayMs`), virtual-time progression (`RETURNING_DOCK -> CHARGING -> DOCK`), charging clamp at `100%` (including initial `CHARGING` at `100%`), undocked zero-battery `FAULT` (`9001`) at init and runtime, obsolete job cancellation and generation checks preventing stale overwrites, deterministic failure/exception injection, and `close()` lifecycle cleanup with zero leaked jobs.
- [ ] **Step 2 (GREEN):** Update `RobotTransport`, implement `SimulatorConfig` with exact documented defaults, implement `SimulatorRobotTransport` (`stateMutex`, `stateGeneration`, single `activeSimulationJob`, `MonotonicClock`, `AutoCloseable.close()`), and wire `DefaultAppContainer` (`close()`).
- [ ] **Step 3 (Verify):** Run `./gradlew testDebugUnitTest --tests "com.weback.robot.protocol.*" --tests "com.weback.robot.core.*"` and confirm deterministic virtual-time pass.

---

### Task 3: MVI `DashboardViewModel` Command Lifecycle, Timeout, Cancellation & Conflict Guards (`ui`)

**Files:**
- Modify: `app/src/main/java/com/weback/robot/ui/DashboardViewModel.kt`
- Create: `app/src/test/java/com/weback/robot/ui/DashboardViewModelControlTest.kt`
- Update: `app/src/test/java/com/weback/robot/ui/DashboardViewModelTest.kt` (adapt `FakeRobotTransport.sendCommand` to typed contract while preserving ALL existing FCT-001 assertions!)

**Covers:** `REQ-CTRL-203` (`AC-203.2..5`), `REQ-CTRL-204` (`AC-204.1..4`), `REQ-CTRL-207` (`AC-207.2..3`).

- [ ] **Step 1 (TDD RED):** Write `DashboardViewModelControlTest.kt` covering: `Idle -> Pending -> Acknowledged` with monotonic `commandId` & `MonotonicClock` timestamps, locally invalid rejection without calling transport, `Pending -> Rejected` on transport rejection AND unexpected `RuntimeException` (`TRANSPORT_ERROR`) preserving committed telemetry, actual bounded `withTimeout` (`TIMEOUT`), `CancellationException` non-swallowing + `Pending` cleanup + subsequent recovery, stale completion `commandId` protection, atomic duplicate/conflicting admission + `busyConflictNotice`, `DismissCommandFeedback` during `Pending` preserving lock vs post-settlement clearing to `Idle`, and subsequent command acceptance.
- [ ] **Step 2 (GREEN):** Add sealed `DashboardIntent`, backward-compatible `RobotUiState` (`errorCode`, `isFault`, `statusSummary`, `commandState`, `busyConflictNotice`, `controls`), `stateLock` atomic admission/settlement/telemetry synchronization, `withTimeout(commandTimeoutMs)`, `CancellationException` cleanup/rethrow, and `commandId` identity correlation in `DashboardViewModel`.
- [ ] **Step 3 (Verify):** Run `./gradlew testDebugUnitTest --tests "com.weback.robot.ui.*"` and confirm all ViewModel tests pass.

---

### Task 4: Robolectric Spike, Compose Test Wiring & Interactive `DashboardScreen` Controls (`ui/screens`, `res`, `gradle`)

**Files:**
- Modify: `gradle/libs.versions.toml` (pin Robolectric `4.14.1`, `androidx.test.ext:junit:1.2.1`, Compose `ui-test-junit4` / `ui-test-manifest`)
- Modify: `app/build.gradle.kts` (`testImplementation` + `debugImplementation` + `testOptions.unitTests.isIncludeAndroidResources = true`)
- Modify: `app/src/main/res/values/strings.xml` (all new UI labels, button texts, status banners, fault/offline strings)
- Modify: `app/src/main/java/com/weback/robot/ui/screens/DashboardScreen.kt`
- Modify: `app/src/main/java/com/weback/robot/MainActivity.kt` (pass `viewModel::onIntent` cleanly)
- Create: `app/src/test/java/com/weback/robot/ui/screens/DashboardScreenControlTest.kt`

**Covers:** `REQ-CTRL-208` (`AC-208.1..3`).

- [ ] **Step 1 (Robolectric Compatibility Spike — `IC-205`):** Pin test-only Robolectric + Compose test dependencies and `isIncludeAndroidResources = true`, write a minimal `createComposeRule()` smoke test on `DashboardScreen`, and run `python3 -m verification.checks.gradle_test_adapter --task testDebugUnitTest` + `./gradlew lintDebug assembleDebug` + `python3 verification/checks/apk_validator.py` to confirm real JUnit XML generation and zero policy/lint/APK conflicts within the 180s timeout.
- [ ] **Step 2 (TDD RED):** Expand `DashboardScreenControlTest.kt` with full Robolectric + `createComposeRule()` UI tests asserting: `btn_clean`, `btn_pause`, `btn_resume`, `btn_dock` enablement matrix across `DOCK`, `CLEAN`, `PAUSED`, `RETURNING_DOCK`, `CHARGING`, `FAULT`, and `Pending`; visible `command_status_banner`, `busy_conflict_banner`, and `btn_dismiss_feedback` interaction; persistent `"OFFLINE"` `simulator_badge`; light & dark theme rendering; and button click callbacks invoking expected intents.
- [ ] **Step 3 (GREEN):** Update `strings.xml`, `DashboardScreen.kt`, and `MainActivity.kt` with accessible Material 3 control buttons (`testTag`s `btn_clean`, `btn_pause`, `btn_resume`, `btn_dock`), `command_status_banner`, `busy_conflict_banner`, fault status indication, light/dark readability, and uninterrupted `simulator_badge`.
- [ ] **Step 4 (Verify):** Execute full `./gradlew testDebugUnitTest` verifying both pure-JVM and Robolectric Compose UI tests pass in JUnit XML.

---

### Task 5: Full 7-Check Policy Verification, Lint, APK Build, Defect Closure & Candidate Handoff

**Files:**
- Modify: `specs/changes/FCT-002/traceability.json` (update `PLANNED` -> `EXECUTED` / `VERIFIED` strictly from observed execution outputs)

**Covers:** All FCT-002 requirements and ACs (`REQ-CTRL-201`..`REQ-CTRL-208`), `TEST-CTRL-216`.

- [ ] **Step 1:** Run `./gradlew lintDebug assembleDebug testDebugUnitTest` and verify zero errors across Lint, build, and all unit/Compose UI tests.
- [ ] **Step 2:** Verify FCT-000 & FCT-001 existing regression tests remain green and unweakened (`RobotStateTest`, `AppContainerTest`, `DashboardViewModelTest`, Python harness selftests).
- [ ] **Step 3:** Update `traceability.json` accurately based on real JUnit XML, lint, build, and APK evidence; commit clean candidate state; execute `python3 verification/run.py --lane candidate --event-name pull_request --base-ref main --run-id fct002-candidate-verify` and `evidence.py` audit confirming 7/7 checks `PASS`.
- [ ] **Step 4:** Export APK + acceptance evidence artifacts, push `feature/fct-002-simulated-robot-control`, and open implementation PR against `main` (do NOT merge).
