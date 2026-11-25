# FCT-002 Requirements Specification — Simulated Robot Control

- **Change ID:** `FCT-002`
- **Risk Tier:** `T2`
- **Related Documents:** [proposal.md](proposal.md) | [design.md](design.md) | [validation.md](validation.md) | [tasks.md](tasks.md) | [traceability.json](traceability.json)

---

## 1. Provenance Legend

Each requirement distinguishes:
- **[RE-OBSERVED]** Grounded in frozen reverse-engineering evidence referenced by [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md) (`SPEC-PROT-01`, `SPEC-STATE-01`, `SPEC-MODEL-01`, `SPEC-FEAT-01`).
- **[SIMULATOR-DESIGN]** Clean-room offline application/simulator specification designed for deterministic client verification; does not assert physical robot firmware behavior (`REG-04`).

---

## 2. Functional & Technical Requirements

### REQ-CTRL-201: Typed Robot Command & Mode Taxonomy
- **Category:** Domain Model & Protocol Mapping `[RE-OBSERVED]` / `[SIMULATOR-DESIGN]`
- **Priority:** `P0`
- **Description:** The pure Kotlin domain layer (`com.weback.robot.domain`) MUST define strongly typed command and mode enums grounded in documented protocol strings while preserving strict framework independence (`C-002`).

#### Acceptance Criteria
- **AC-201.1:** Pure Kotlin domain defines `enum class RobotCommand` containing `CLEAN`, `PAUSE`, `RESUME`, and `DOCK`, each exposing its canonical RE-observed wire token (`"Clean"` for `CLEAN` and `RESUME`, `"Pause"` for `PAUSE`, `"GoHome"` for `DOCK`) matching `SPEC-PROT-01` (`PROT-WS-01`) and `SPEC-STATE-01`.
- **AC-201.2:** `enum class RobotMode` explicitly models `DOCK`, `CLEAN`, `PAUSED`, `RETURNING_DOCK`, `CHARGING`, `DORMANT`, `IDLE`, and `FAULT`, distinguishing active cleaning, paused, return-to-dock transition, charging, dormant, and fault states.
- **AC-201.3:** All files under package `com.weback.robot.domain` MUST contain zero `android.*`, `androidx.*`, `kotlinx.serialization`, or `okhttp3.*` imports (`C-002`).

---

### REQ-CTRL-202: Deterministic Control State Machine & Transition Guards
- **Category:** Core State Machine `[RE-OBSERVED]` + `[SIMULATOR-DESIGN]`
- **Priority:** `P0`
- **Description:** A pure domain evaluator `RobotStateMachine.evaluateTransition(current: RobotState, command: RobotCommand)` MUST determine deterministically whether a requested command is valid for the current telemetry state without side effects.

#### Acceptance Criteria
- **AC-202.1:** Valid transitions are accepted deterministically: (`DOCK` | `CHARGING` | `DORMANT` | `IDLE`) + `CLEAN` -> target `CLEAN`; `CLEAN` + `PAUSE` -> target `PAUSED`; `PAUSED` + `RESUME` -> target `CLEAN`; (`CLEAN` | `PAUSED`) + `DOCK` -> target `RETURNING_DOCK`.
- **AC-202.2:** Any transition outside the authorized matrix (such as `PAUSE` when `DOCK`, `RESUME` when `CLEAN` or `DOCK`, `DOCK` when `DOCK` or `CHARGING`, or any motion command when `mode == RobotMode.FAULT` or `errorCode != 0`) MUST return `TransitionDecision.Rejected` with an explicit `CommandRejectionReason` (`INVALID_TRANSITION` or `FAULT_ACTIVE`) and preserve existing `RobotState` unchanged.
- **AC-202.3:** Submitting `CLEAN` or `RESUME` when `batteryPercentage < 15` MUST deterministically return `TransitionDecision.Rejected(CommandRejectionReason.LOW_BATTERY)` while leaving `RobotState` unmodified.

---

### REQ-CTRL-203: Asynchronous Command Lifecycle (Pending, Acknowledged, Rejected)
- **Category:** Asynchronous Control Flow `[SIMULATOR-DESIGN]`
- **Priority:** `P0`
- **Description:** Control command execution MUST track an explicit immutable lifecycle (`Idle`, `Pending`, `Acknowledged`, `Rejected`) rather than optimistically mutating robot mode prior to transport confirmation.

#### Acceptance Criteria
- **AC-203.1:** Domain defines sealed `CommandExecutionState` variants: `Idle`, `Pending(command, commandId, submittedAtMs)`, `Acknowledged(command, commandId, settledAtMs)`, and `Rejected(command, commandId, reason, message)` with unique monotonic `commandId` identifiers.
- **AC-203.2:** Dispatching a locally valid command immediately emits `CommandExecutionState.Pending` prior to transport response, holds committed `RobotState.mode` unchanged until acknowledgement arrives, and transitions to `CommandExecutionState.Acknowledged` alongside updated telemetry once confirmed.
- **AC-203.3:** When transport returns a rejection (`TransportRejected`, `Timeout`, or fault condition), `CommandExecutionState` transitions cleanly from `Pending` to `Rejected` with descriptive feedback while keeping `RobotState` synchronized with the last confirmed telemetry.

---

### REQ-CTRL-204: Duplicate & Conflicting In-Flight Command Protection
- **Category:** Concurrency & Idempotency `[SIMULATOR-DESIGN]`
- **Priority:** `P0`
- **Description:** The control coordinator and transport pipeline MUST serialize in-flight commands and prevent duplicate or conflicting command races while an unacknowledged command is active.

#### Acceptance Criteria
- **AC-204.1:** Submitting an identical `RobotCommand` while `CommandExecutionState.Pending` holds that same command MUST NOT dispatch a second `RobotTransport.sendCommand` invocation.
- **AC-204.2:** Submitting a conflicting `RobotCommand` (such as `DOCK` while `CLEAN` is `Pending`) MUST be blocked without dispatching a second transport call, preserving `Pending` state for the active command and exposing non-destructive busy/conflict feedback (`COMMAND_IN_FLIGHT`).
- **AC-204.3:** Immediately after an in-flight `Pending` command resolves to `Acknowledged` or `Rejected`, subsequent valid commands are accepted cleanly without requiring manual reset.

---

### REQ-CTRL-205: Deterministic Offline Simulator Transport
- **Category:** Transport & Simulation `[SIMULATOR-DESIGN]`
- **Priority:** `P0`
- **Description:** `RobotTransport` and `SimulatorRobotTransport` MUST expose structured command results and execute simulated transitions deterministically using injected coroutine dispatchers and virtual time (`C-006`).

#### Acceptance Criteria
- **AC-205.1:** `RobotTransport` defines `suspend fun sendCommand(command: RobotCommand): CommandResult` returning structured `CommandResult.Acknowledged(resultingState)` or `CommandResult.Rejected(reason, message)` alongside reactive `robotState: StateFlow<RobotState>`.
- **AC-205.2:** `SimulatorRobotTransport` injects `DispatcherProvider` (and configurable virtual-time simulation timings: ack latency, return-to-dock duration, battery tick interval) with zero dependency on `Thread.sleep` or ambient wall clocks, allowing `StandardTestDispatcher` / `TestScope` to drive `RETURNING_DOCK -> CHARGING -> DOCK` deterministically.
- **AC-205.3:** `SimulatorRobotTransport` provides an explicit deterministic failure-injection configuration (reject next command with `TRANSPORT_REJECTED`, `TIMEOUT`, or `FAULT_ACTIVE`) usable solely in unit tests without reflection or network I/O.

---

### REQ-CTRL-206: Telemetry, Battery & Docking Consistency Invariants
- **Category:** Domain Invariants `[SIMULATOR-DESIGN]`
- **Priority:** `P0`
- **Description:** `RobotState` and simulated progression ticks MUST enforce strict domain invariants covering battery bounds, docking state consistency, and fault metadata preservation.

#### Acceptance Criteria
- **AC-206.1:** `RobotState` enforces `batteryPercentage in 0..100` at construction (`require`) and simulated `CLEAN` drainage or `CHARGING` replenishment monotonically clamps within `[0, 100]` without wrap-around or negative values, automatically transitioning `CLEAN` to `RETURNING_DOCK` (or stopping safely if critical `0%`) when battery falls below threshold.
- **AC-206.2:** `RobotState.isDocked` is true if and only if `mode == RobotMode.DOCK || mode == RobotMode.CHARGING` and false for `CLEAN`, `PAUSED`, `RETURNING_DOCK`, `DORMANT`, `IDLE`, and `FAULT`.
- **AC-206.3:** Control transitions (`CLEAN`, `PAUSE`, `RESUME`, `DOCK`, `FAULT`) preserve robot identity (`name`) and dustbin status (`isDustbinClean`) unless explicitly updated, and `errorCode != 0` consistently surfaces `RobotMode.FAULT`.

---

### REQ-CTRL-207: MVI Control Availability & ViewModel State Synchronization
- **Category:** Presentation / ViewModel `[SIMULATOR-DESIGN]`
- **Priority:** `P0`
- **Description:** `DashboardViewModel` MUST expose unidirectional MVI intent handling and derive exact button availability strictly from committed telemetry and pending command state.

#### Acceptance Criteria
- **AC-207.1:** A pure mapper `ControlAvailability.from(robotState, commandState)` computes `canClean`, `canPause`, `canResume`, and `canDock` booleans matching `REQ-CTRL-202` and `REQ-CTRL-204`, disabling all four actions while `commandState is CommandExecutionState.Pending` or `robotState.mode == RobotMode.FAULT`.
- **AC-207.2:** `DashboardViewModel` exposes `fun onIntent(intent: DashboardIntent)` (`Clean`, `Pause`, `Resume`, `Dock`, `DismissCommandFeedback`) updating `StateFlow<RobotUiState>` atomically on injected `DispatcherProvider`.
- **AC-207.3:** Invoking `DashboardIntent.DismissCommandFeedback` clears transient `Acknowledged` or `Rejected` user banners to `Idle` without altering `RobotState` telemetry or cancelling active background simulator progress.

---

### REQ-CTRL-208: Interactive Compose Control Surface & Simulator Labeling
- **Category:** UI / Accessibility / Provenance `[SIMULATOR-DESIGN]` (`C-011`)
- **Priority:** `P1`
- **Description:** `DashboardScreen` MUST render accessible Material 3 robot control buttons, clear pending/rejection feedback, and persistent offline simulator provenance indicators.

#### Acceptance Criteria
- **AC-208.1:** `DashboardScreen` displays four distinct Material 3 controls (`Clean`, `Pause`, `Resume`, `Dock` with stable Compose semantics `testTag`s `btn_clean`, `btn_pause`, `btn_resume`, `btn_dock`) whose `enabled` state binds directly to `RobotUiState.controls`, plus a visible command feedback card (`command_status_banner`) showing `Pending`, `Acknowledged`, or `Rejected` details.
- **AC-208.2:** `DashboardScreen` maintains the persistent `OFFLINE` simulator badge (`simulator_badge`) and explanatory text clarifying local simulated execution (never presenting simulator telemetry or acknowledgements as a live physical robot or cloud connection, satisfying `C-011`).
- **AC-208.3:** Compose UI tests executed on JVM (`createComposeRule()` under Robolectric via `android-unit-tests` / `testDebugUnitTest`) verify enabled/disabled button matrices across modes (`DOCK`, `CLEAN`, `PAUSED`, `RETURNING_DOCK`, `FAULT`), visible pending/rejected states, click dispatch into intents, and continuous `simulator_badge` presence.
