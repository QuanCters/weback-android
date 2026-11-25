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
- **AC-201.2:** `enum class RobotMode` explicitly models `DOCK`, `CLEAN`, `PAUSED`, `RETURNING_DOCK`, `CHARGING`, `DORMANT`, `IDLE`, and `FAULT`, distinguishing active cleaning, paused, return-to-dock transition, charging, dormant, idle, and fault states.
- **AC-201.3:** All files under package `com.weback.robot.domain` MUST contain zero `android.*`, `androidx.*`, `kotlinx.serialization`, or `okhttp3.*` imports (`C-002`).

---

### REQ-CTRL-202: Deterministic Control State Machine & Transition Guards
- **Category:** Core State Machine `[RE-OBSERVED]` + `[SIMULATOR-DESIGN]`
- **Priority:** `P0`
- **Description:** A pure domain evaluator `RobotStateMachine.evaluateTransition(current: RobotState, command: RobotCommand)` MUST determine deterministically whether a requested command is valid for the current telemetry state without side effects, enforcing strict rejection precedence (`IC-203`).

#### Acceptance Criteria
- **AC-202.1:** Valid transitions are accepted deterministically: (`DOCK` | `CHARGING` | `DORMANT` | `IDLE`) + `CLEAN` -> target `CLEAN` (when `batteryPercentage >= 15` and not faulted); `CLEAN` + `PAUSE` -> target `PAUSED`; `PAUSED` + `RESUME` -> target `CLEAN` (when `batteryPercentage >= 15` and not faulted); (`CLEAN` | `PAUSED`) + `DOCK` -> target `RETURNING_DOCK`.
- **AC-202.2:** Guard evaluation MUST enforce deterministic rejection precedence: (1) `FAULT_ACTIVE` if `mode == RobotMode.FAULT || errorCode != 0`; (2) `LOW_BATTERY` if `(command == CLEAN || command == RESUME) && batteryPercentage < 15`; (3) `INVALID_TRANSITION` for any command outside the authorized matrix (`DOCK+PAUSE`, `DOCK+RESUME`, `DOCK+DOCK`, `CHARGING+DOCK`, `CLEAN+CLEAN`, `CLEAN+RESUME`, etc.), preserving existing `RobotState` unchanged.
- **AC-202.3:** Battery boundary behavior MUST allow `CLEAN` and `RESUME` at `batteryPercentage == 15` when otherwise valid (including `100%`) and deterministically reject `CLEAN` and `RESUME` with `LOW_BATTERY` at `batteryPercentage == 14` and `0` (unless `FAULT_ACTIVE` takes precedence per `AC-202.2`), leaving `RobotState` unmodified.

---

### REQ-CTRL-203: Asynchronous Command Lifecycle, Timeout, Exceptions & Cancellation
- **Category:** Asynchronous Control Flow `[SIMULATOR-DESIGN]` (`IC-202`)
- **Priority:** `P0`
- **Description:** Control command execution MUST track an explicit immutable lifecycle (`Idle`, `Pending`, `Acknowledged`, `Rejected`) using monotonic command identities and an injectable monotonic clock, handling transport rejections, bounded timeouts, unexpected exceptions, and coroutine cancellation cleanly without rolling back committed telemetry.

#### Acceptance Criteria
- **AC-203.1:** Domain defines sealed `CommandExecutionState` variants (`Idle`, `Pending(command, commandId, submittedAtMs)`, `Acknowledged(command, commandId, settledAtMs)`, `Rejected(command, commandId, reason, message, settledAtMs)`) and `CommandRejectionReason` (`FAULT_ACTIVE`, `LOW_BATTERY`, `INVALID_TRANSITION`, `COMMAND_IN_FLIGHT`, `TRANSPORT_REJECTED`, `TIMEOUT`, `TRANSPORT_ERROR`), emitting unique monotonic `commandId` values and timestamps from an injectable `MonotonicClock`.
- **AC-203.2:** Dispatching a locally valid command immediately emits `CommandExecutionState.Pending` prior to transport response, holds committed `RobotState.mode` unchanged until transport confirmation, and transitions to `CommandExecutionState.Acknowledged` alongside updated telemetry once confirmed; locally invalid commands immediately transition to `Rejected` without invoking `RobotTransport.sendCommand`.
- **AC-203.3:** When transport returns `CommandResult.Rejected` (`TRANSPORT_REJECTED` or `FAULT_ACTIVE`, etc.) or throws an unexpected non-cancellation exception (`TRANSPORT_ERROR`), `CommandExecutionState` transitions cleanly from `Pending` to `Rejected` while keeping committed `RobotState` authoritative (no rollback of already committed telemetry).
- **AC-203.4:** Command dispatch enforces an actual bounded coroutine timeout (`withTimeout(commandTimeoutMs)`), transitioning `Pending` to `Rejected(TIMEOUT)` on expiry while leaving committed telemetry intact and allowing subsequent commands.
- **AC-203.5:** External `CancellationException` is NEVER swallowed: it synchronously cleans up owned `Pending` state to `Idle`, preserves committed telemetry, and rethrows `CancellationException` so subsequent commands on an active scope are not deadlocked.
- **AC-203.6:** Every terminal settlement (acknowledgement, rejection, timeout, error, or cancellation cleanup) MUST verify active `Pending` ownership (`Pending.commandId == commandId`), preventing stale completions from overwriting newer command lifecycles.

---

### REQ-CTRL-204: Duplicate & Conflicting In-Flight Command Protection
- **Category:** Concurrency & Idempotency `[SIMULATOR-DESIGN]` (`IC-201`, `IC-202`)
- **Priority:** `P0`
- **Description:** The control coordinator MUST serialize `Pending` admission atomically and prevent duplicate or conflicting command races while an unacknowledged command is active.

#### Acceptance Criteria
- **AC-204.1:** Submitting an identical `RobotCommand` while `CommandExecutionState.Pending` holds that same command is rejected atomically at admission and MUST NOT dispatch a second `RobotTransport.sendCommand` invocation, preserving the active `Pending` `commandId`.
- **AC-204.2:** Submitting a conflicting `RobotCommand` (such as `DOCK` while `CLEAN` is `Pending`) is blocked atomically without dispatching a second transport call, preserving `Pending` state for the active command and exposing non-destructive `busyConflictNotice` feedback referencing `COMMAND_IN_FLIGHT`.
- **AC-204.3:** Immediately after an in-flight `Pending` command resolves to `Acknowledged`, `Rejected`, or cancellation cleanup, subsequent valid commands are accepted cleanly without requiring manual reset.
- **AC-204.4:** Invoking `DashboardIntent.DismissCommandFeedback` while `commandState is CommandExecutionState.Pending` MUST NOT release the active `Pending` command lock (it may dismiss only `busyConflictNotice` while keeping `Pending` intact).

---

### REQ-CTRL-205: Deterministic Offline Simulator Transport & Unified Mutation Ownership
- **Category:** Transport & Simulation `[SIMULATOR-DESIGN]` (`IC-201`, `IC-202`, `IC-203`)
- **Priority:** `P0`
- **Description:** `RobotTransport` and `SimulatorRobotTransport` MUST expose structured command results, enforce unified single-mutex state mutation ownership without holding locks across simulated delays, verify job generation applicability, and clean up owned coroutine scopes deterministically.

#### Acceptance Criteria
- **AC-205.1:** `RobotTransport` defines `suspend fun sendCommand(command: RobotCommand): CommandResult` returning structured `CommandResult.Acknowledged(resultingState, settledAtMs)` or `CommandResult.Rejected(reason, message, settledAtMs)` alongside reactive `robotState: StateFlow<RobotState>`.
- **AC-205.2:** `SimulatorRobotTransport` injects `DispatcherProvider`, `SimulatorConfig` (exact synthetic defaults: `ackDelayMs = 250L`, `returnToDockDurationMs = 2_000L`, `cleanBatteryDrainIntervalMs = 5_000L`, `cleanBatteryDrainStep = 1`, `chargeIntervalMs = 1_000L`, `chargeStepPercent = 5`, `lowBatteryThreshold = 15`), and `MonotonicClock` with zero `Thread.sleep` or wall-clock dependencies, enabling `StandardTestDispatcher`/`TestScope` to drive `RETURNING_DOCK -> CHARGING -> DOCK` deterministically.
- **AC-205.3:** All `_robotState` mutations (command commit, battery drain, automatic docking, charging ticks, fault injection) follow one unified `stateMutex` contract WITHOUT holding `stateMutex` across simulated `delay(...)`; the simulator command commit point occurs after `ackDelayMs` inside `stateMutex.withLock`, revalidating guards against current committed telemetry before committing.
- **AC-205.4:** Every delayed simulation job records its originating `(mode, stateGeneration)`, cancels obsolete timed jobs whenever leaving a mode, verifies `stateGeneration` applicability inside `stateMutex` before mutating (preventing stale charging/docking jobs from overwriting newer commands), and supports deterministic test failure injection (`TRANSPORT_REJECTED`, `TIMEOUT`, `FAULT_ACTIVE`, or synthetic exception).
- **AC-205.5:** `SimulatorRobotTransport` implements `AutoCloseable` (`close()` cancels its owned child `CoroutineScope` and active jobs), alongside `AppContainer.close()` and `DashboardViewModel` `scope` lifecycle cleanup, leaking zero background coroutine jobs.

---

### REQ-CTRL-206: Telemetry, Battery & Docking Consistency Invariants
- **Category:** Domain Invariants `[SIMULATOR-DESIGN]` (`IC-203`)
- **Priority:** `P0`
- **Description:** `RobotState` and simulated progression ticks MUST enforce strict domain invariants covering battery bounds, undocked zero-battery fault safety, synthetic error codes, docking state consistency, and metadata preservation.

#### Acceptance Criteria
- **AC-206.1:** `RobotState` enforces `batteryPercentage in 0..100` at construction (`require`); simulated `CLEAN` drainage monotonically clamps within `[0, 100]` and triggers automatic `RETURNING_DOCK` when dropping below `15%` (`> 0%`), while `CHARGING` monotonically clamps at `100%` and transitions to `DOCK` upon reaching `100%` (including initial `CHARGING` at `100%` on tick).
- **AC-206.2:** `RobotState.isDocked` is true if and only if `mode == RobotMode.DOCK || mode == RobotMode.CHARGING` and false for `CLEAN`, `PAUSED`, `RETURNING_DOCK`, `DORMANT`, `IDLE`, and `FAULT`.
- **AC-206.3:** Control transitions (`CLEAN`, `PAUSE`, `RESUME`, `DOCK`, `FAULT`) preserve robot identity (`name`) and unaffected telemetry fields (`isDustbinClean`), and `errorCode != 0` consistently represents `RobotMode.FAULT` (`RobotState.isFault` is true iff `mode == RobotMode.FAULT || errorCode != 0`, and `SimulatorRobotTransport` normalizes nonzero `errorCode` to `RobotMode.FAULT`).
- **AC-206.4:** Reaching `batteryPercentage == 0` while undocked (`!isDocked`, whether at simulator initialization or during active progression) stops simulated movement and enters `RobotMode.FAULT` with a synthetic simulator error code (`SimulatorErrorCodes.SYNTHETIC_BATTERY_DEPLETED = 9001`, explicitly documented as synthetic and NOT an observed hardware firmware code).

---

### REQ-CTRL-207: MVI Control Availability & ViewModel State Synchronization
- **Category:** Presentation / ViewModel `[SIMULATOR-DESIGN]` (`IC-201`, `IC-202`)
- **Priority:** `P0`
- **Description:** `DashboardViewModel` MUST expose unidirectional MVI intent handling, backward-compatible `RobotUiState`, and exact button availability derived strictly from committed telemetry and pending command state.

#### Acceptance Criteria
- **AC-207.1:** A pure mapper `ControlAvailability.from(robotState, commandState)` computes `canClean`, `canPause`, `canResume`, and `canDock` booleans matching `REQ-CTRL-202` and `REQ-CTRL-204`, disabling all four actions while `commandState is CommandExecutionState.Pending`, `robotState.mode == RobotMode.FAULT`, or `robotState.errorCode != 0` (including `RETURNING_DOCK` where all four are disabled).
- **AC-207.2:** `DashboardViewModel` exposes `fun onIntent(intent: DashboardIntent)` (`Clean`, `Pause`, `Resume`, `Dock`, `DismissCommandFeedback`) updating `StateFlow<RobotUiState>` (retaining FCT-001 telemetry fields plus `errorCode`, `isFault`, `statusSummary`, `commandState`, `busyConflictNotice`, `controls`) atomically on injected `DispatcherProvider`.
- **AC-207.3:** Invoking `DashboardIntent.DismissCommandFeedback` clears settled `Acknowledged` or `Rejected` banners to `Idle` and clears `busyConflictNotice` without altering `RobotState` telemetry or cancelling active background simulator progress (and without releasing `Pending` per `AC-204.4`).

---

### REQ-CTRL-208: Interactive Compose Control Surface, Simulator Labeling & Robolectric Execution
- **Category:** UI / Accessibility / Provenance `[SIMULATOR-DESIGN]` (`C-010`, `C-011`, `IC-205`)
- **Priority:** `P1`
- **Description:** `DashboardScreen` MUST render accessible, string-resourced Material 3 robot control buttons supporting light and dark themes, clear pending/rejection feedback, persistent offline simulator provenance indicators, and real Compose UI tests executed via Robolectric.

#### Acceptance Criteria
- **AC-208.1:** `DashboardScreen` displays four distinct Material 3 controls (`Clean`, `Pause`, `Resume`, `Dock` with stable Compose semantics `testTag`s `btn_clean`, `btn_pause`, `btn_resume`, `btn_dock`, accessible text/content descriptions, and Android string resources in `res/values/strings.xml`) whose `enabled` state binds directly to `RobotUiState.controls`, plus a visible command feedback card (`command_status_banner`) showing `Pending`, `Acknowledged`, `Rejected`, and conflict details with dismiss affordance (`btn_dismiss_feedback`).
- **AC-208.2:** `DashboardScreen` maintains the persistent `OFFLINE` simulator badge (`simulator_badge`), string-resourced explanatory text clarifying local simulated execution, readable light and dark Material 3 layouts, and fault/error telemetry presentation when `isFault` is active (never presenting simulated execution as live hardware or cloud connectivity, satisfying `C-011`).
- **AC-208.3:** Real Compose UI tests (`createComposeRule()` with Robolectric under `testDebugUnitTest` and `isIncludeAndroidResources = true`, executed through `gradle_test_adapter.py` emitting fresh JUnit XML within the existing 180s `android-unit-tests` timeout) verify enabled/disabled button matrices across modes (including `DOCK`, `CLEAN`, `PAUSED`, `RETURNING_DOCK`, `CHARGING`, `FAULT`), `Pending` lock and feedback dismissal, click dispatch into intents, light/dark rendering, and continuous `simulator_badge` presence.
- **AC-208.4:** Specification hygiene (`spec-hygiene`), Android Lint (`android-lint`), debug APK build (`android-build`), and APK security/structural validation (`apk-validation`) pass with zero policy or harness modifications (`policies/checks.json`).
