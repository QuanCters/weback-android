# FCT-002 Implementation Plan — Simulated Robot Control

> **Gate Status:** Drafted for Gate G3 Implementation Readiness Review. **Do NOT begin editing Kotlin/Gradle files under this branch until Product Owner & Lead Maintainer authorize G3 / implementation start.**

**Goal:** Deliver typed robot control commands (`CLEAN`, `PAUSE`, `RESUME`, `DOCK`), pure finite-state transition guards, an explicit asynchronous `Pending` / `Acknowledged` / `Rejected` command lifecycle, virtual-time `SimulatorRobotTransport`, MVI `DashboardViewModel`, interactive Compose controls with persistent `OFFLINE` labeling, and full JVM + Compose UI test coverage under the existing 7-check policy.

**Architecture:** Extends the existing single-module `:app` clean architecture (`core`, `domain`, `protocol`, `ui`) established in FCT-001. Pure Kotlin `domain` remains 100% decoupled from Android/coroutines/serialization dependencies (`C-002`). Coroutine determinism is achieved through injected `DispatcherProvider` and virtual-time simulator loops verified under `kotlinx-coroutines-test` (`StandardTestDispatcher` / `TestScope`) and Robolectric Compose UI rules inside `testDebugUnitTest`.

**Tech Stack:** Kotlin 2.1.21, Jetpack Compose BOM 2024.10.01, Material 3 1.3.1, Coroutines 1.9.0 + Coroutines Test 1.9.0, JUnit 4.13.2, Robolectric + Compose UI Test JUnit4 (`androidx.compose.ui:ui-test-junit4`) executed in JVM `android-unit-tests`. Zero changes to `policies/` or `verification/`.

**Spec:** [proposal.md](proposal.md) | [requirements.md](requirements.md) | [design.md](design.md) | [validation.md](validation.md) | [risk-assessment.md](risk-assessment.md) | [traceability.json](traceability.json)

---

## Global Constraints
- **Spec-First Enforcement:** Do NOT execute implementation tasks until Gates G1, G2, and G3 are explicitly approved by Product Owner / Lead Maintainer.
- **Zero Policy/Harness Mutation:** Do NOT alter `policies/` or `verification/` scripts.
- **Zero Live Network / Hardware:** Simulator-only (`C-006`, `C-011`). Retain visible `OFFLINE` `simulator_badge`.
- **Domain Purity:** Zero `android.*` or `androidx.*` imports inside `com.weback.robot.domain` (`C-002`).

---

### Task 1: Pure Kotlin Control Domain Models & State Machine (`domain`)

**Files:**
- Create: `app/src/main/java/com/weback/robot/domain/model/RobotCommand.kt`
- Modify: `app/src/main/java/com/weback/robot/domain/model/RobotMode.kt`
- Modify: `app/src/main/java/com/weback/robot/domain/model/RobotState.kt`
- Create: `app/src/main/java/com/weback/robot/domain/model/CommandExecutionState.kt`
- Create: `app/src/main/java/com/weback/robot/domain/model/CommandResult.kt`
- Create: `app/src/main/java/com/weback/robot/domain/model/ControlAvailability.kt`
- Create: `app/src/main/java/com/weback/robot/domain/control/RobotStateMachine.kt`

**Covers:** `REQ-CTRL-201`, `REQ-CTRL-202`, `REQ-CTRL-203` (`AC-203.1`), `REQ-CTRL-206` (`AC-206.1..3`), `REQ-CTRL-207` (`AC-207.1`).

- [ ] **Step 1 (TDD RED):** Write `RobotCommandTest.kt`, `RobotModeTest.kt`, `RobotStateMachineTest.kt`, `RobotStateInvariantsTest.kt`, and `ControlAvailabilityTest.kt` asserting expected wire tokens, valid/invalid transition matrix, low-battery (`< 15%`) and fault rejections, `0..100` battery `require` bounds, `isDocked` equivalence (`DOCK`, `CHARGING`), and button enablement tuples. Observe compilation/test failures.
- [ ] **Step 2 (GREEN):** Implement pure Kotlin `RobotCommand` (`wireStatus`), expanded `RobotMode` (`8` modes), guarded `RobotState`, `CommandExecutionState`, `CommandRejectionReason`, `CommandResult`, `RobotStateMachine.evaluateTransition`, and `ControlAvailability.from` (delegating to `RobotStateMachine`).
- [ ] **Step 3 (Verify):** Run `./gradlew testDebugUnitTest --tests "com.weback.robot.domain.*"` and confirm all domain tests pass with zero `android.*` imports in `domain`.

---

### Task 2: Transport Contract & Deterministic Virtual-Time `SimulatorRobotTransport` (`protocol`)

**Files:**
- Modify: `app/src/main/java/com/weback/robot/protocol/RobotTransport.kt`
- Create: `app/src/main/java/com/weback/robot/protocol/SimulatorConfig.kt`
- Modify: `app/src/main/java/com/weback/robot/protocol/SimulatorRobotTransport.kt`
- Modify: `app/src/main/java/com/weback/robot/core/AppContainer.kt`

**Covers:** `REQ-CTRL-205` (`AC-205.1..3`), `REQ-CTRL-206` (`AC-206.1..3`).

- [ ] **Step 1 (TDD RED):** Write `SimulatorRobotTransportTest.kt` using `runTest` + `StandardTestDispatcher` covering `sendCommand(RobotCommand): CommandResult`, mutex concurrency serialization, virtual-time progression (`RETURNING_DOCK -> CHARGING -> DOCK`), cleaning battery drain + auto-docking below `15%`, charging clamp at `100%`, and deterministic failure injection (`TRANSPORT_REJECTED`, `TIMEOUT`, `FAULT_ACTIVE`).
- [ ] **Step 2 (GREEN):** Update `RobotTransport` contract (`sendCommand(command: RobotCommand): CommandResult`), implement `SimulatorConfig` + deterministic coroutine `SimulatorRobotTransport` driven by injected `DispatcherProvider` / child `CoroutineScope` and `Mutex`, and wire `DefaultAppContainer` cleanly without breaking constructor injectability.
- [ ] **Step 3 (Verify):** Run `./gradlew testDebugUnitTest --tests "com.weback.robot.protocol.*"` and confirm deterministic virtual-time pass with zero flakiness.

---

### Task 3: MVI `DashboardViewModel` Command Lifecycle & Conflict Guards (`ui`)

**Files:**
- Modify: `app/src/main/java/com/weback/robot/ui/DashboardViewModel.kt`
- Create/Update: `app/src/test/java/com/weback/robot/ui/DashboardViewModelControlTest.kt`
- Update if needed: existing `app/src/test/java/com/weback/robot/ui/DashboardViewModelTest.kt` (preserving all existing FCT-001 assertions!)

**Covers:** `REQ-CTRL-203` (`AC-203.2..3`), `REQ-CTRL-204` (`AC-204.1..3`), `REQ-CTRL-207` (`AC-207.2..3`).

- [ ] **Step 1 (TDD RED):** Write `DashboardViewModelControlTest.kt` covering: `Idle -> Pending -> Acknowledged` state timeline before and after transport ack, `Pending -> Rejected` recovery with mode preservation, duplicate in-flight command suppression (spy transport call count == 1), conflicting in-flight command blocking + `busyConflictNotice`, post-settlement next-command acceptance, and `DismissCommandFeedback` reset.
- [ ] **Step 2 (GREEN):** Add sealed `DashboardIntent`, enrich `RobotUiState` with `commandState`, `busyConflictNotice`, and `controls`, enforce single-writer `Pending` guard + local `RobotStateMachine` pre-validation in `DashboardViewModel.onIntent`, and preserve reactive `robotTransport.robotState` telemetry subscription.
- [ ] **Step 3 (Verify):** Run `./gradlew testDebugUnitTest --tests "com.weback.robot.ui.*"` and confirm all ViewModel tests pass.

---

### Task 4: Compose Test Harness Wiring & Interactive `DashboardScreen` Controls (`ui/screens`, `gradle`)

**Files:**
- Modify: `gradle/libs.versions.toml` (pin Robolectric, `androidx.test.ext:junit`, Compose `ui-test-junit4` / `ui-test-manifest`)
- Modify: `app/build.gradle.kts` (`testImplementation` + `debugImplementation` + `testOptions.unitTests.isIncludeAndroidResources = true`)
- Modify: `app/src/main/java/com/weback/robot/ui/screens/DashboardScreen.kt`
- Modify: `app/src/main/java/com/weback/robot/MainActivity.kt` (pass `viewModel::onIntent` cleanly)
- Create: `app/src/test/java/com/weback/robot/ui/screens/DashboardScreenControlTest.kt`

**Covers:** `REQ-CTRL-208` (`AC-208.1..3`).

- [ ] **Step 1 (TDD RED):** Add Robolectric + `createComposeRule()` UI tests in `DashboardScreenControlTest.kt` asserting: `btn_clean`, `btn_pause`, `btn_resume`, `btn_dock` enablement matrix across `DOCK`, `CLEAN`, `PAUSED`, `FAULT`, and `Pending`; visible `command_status_banner` rendering; persistent `"OFFLINE"` `simulator_badge`; and click callbacks invoking expected intents.
- [ ] **Step 2 (GREEN):** Update `DashboardScreen` with accessible Material 3 control buttons (`testTag`s `btn_clean`, `btn_pause`, `btn_resume`, `btn_dock`), `command_status_banner` showing `Pending` progress / `Acknowledged` / `Rejected` reason + dismiss action, and uninterrupted `simulator_badge`.
- [ ] **Step 3 (Verify):** Execute full `./gradlew testDebugUnitTest` verifying both pure-JVM and Robolectric Compose UI tests pass.

---

### Task 5: Full 7-Check Policy Verification, Lint, APK Build & G4 Readiness

**Files:**
- Modify: `specs/changes/FCT-002/traceability.json` (update `PLANNED` -> `EXECUTED` / `VERIFIED` during implementation verification only — NEVER mark verified before real execution)

**Covers:** All FCT-002 requirements and ACs (`REQ-CTRL-201`..`REQ-CTRL-208`), `TEST-CTRL-216`.

- [ ] **Step 1:** Run `./gradlew lintDebug assembleDebug testDebugUnitTest` and verify zero errors across Lint, build, and all unit/UI tests.
- [ ] **Step 2:** Verify FCT-000 & FCT-001 existing regression tests remain untouched and green (`RobotStateTest`, `AppContainerTest`, `DashboardViewModelTest`, all 186 Python harness selftests).
- [ ] **Step 3:** Execute `python3 verification/run.py --lane candidate --event-name pull_request --base-ref main --run-id fct002-candidate-verify` and confirm 7/7 checks `PASS` before requesting G4/G5 review.
