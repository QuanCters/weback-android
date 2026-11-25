# FCT-002 Validation Plan & Independent Test Oracles

- **Change ID:** `FCT-002`
- **Risk Tier:** `T2`
- **References:** [requirements.md](requirements.md) | [design.md](design.md) | [tasks.md](tasks.md) | [traceability.json](traceability.json)

---

## 1. Validation Strategy

Every FCT-002 requirement and acceptance criterion MUST be independently verified against the existing 7-check policy (`policies/checks.json`):
- **Specification & Schema Hygiene (`spec-hygiene`):** Enforces cross-spec ID uniqueness (`REQ-CTRL-201..208`, `AC-201.1..208.3`), portable paths, 100% traceability mapping, and valid existing check IDs.
- **Automated JVM & Compose UI Unit Tests (`android-unit-tests`):** Executes pure Kotlin domain unit tests, `TestScope`/`StandardTestDispatcher` coroutine transport & ViewModel contract tests, and Robolectric + Jetpack Compose UI tests via `./gradlew testDebugUnitTest` producing fresh JUnit XML telemetry.
- **Static Analysis & Artifact Assembly (`android-lint`, `android-build`, `apk-validation`):** Enforces zero Lint errors/security regressions and clean APK packaging after FCT-002 code tasks begin.

Per `AGENTS.md` Verification Integrity Rule #3 (**No Mocking the Oracle**), test assertions compare actual state transitions, wire tokens, battery bounds, emitted `StateFlow` sequences, and Compose semantics against fixed expected values rather than re-invoking production helper formulas to compute expected results.

---

## 2. Independent Test Oracles

| Test Oracle ID | Target ACs | Verification Mechanism | Independent Expected Assertion (Oracle) | Approved Check ID |
| :--- | :--- | :--- | :--- | :--- |
| `TEST-CTRL-201` | `AC-201.1`, `AC-201.3` | JUnit + static source scan test (`RobotCommandTest`, domain purity scan) | Exact mapping assertions: `CLEAN -> "Clean"`, `PAUSE -> "Pause"`, `RESUME -> "Clean"`, `DOCK -> "GoHome"`; AST/line scan confirms zero `android.` / `androidx.` / `okhttp3.` / `kotlinx.serialization.` imports under `domain/`. | `android-unit-tests` |
| `TEST-CTRL-202` | `AC-201.2` | JUnit (`RobotModeTest`) | Verifies all 8 enum entries (`DOCK`, `CLEAN`, `PAUSED`, `RETURNING_DOCK`, `CHARGING`, `DORMANT`, `IDLE`, `FAULT`) exist and map cleanly to docked/non-docked classifications. | `android-unit-tests` |
| `TEST-CTRL-203` | `AC-202.1` | Table-driven JUnit (`RobotStateMachineTest`) | Every authorized `(sourceMode, command)` pair (`{DOCK, CHARGING, DORMANT, IDLE}+CLEAN -> CLEAN`, `CLEAN+PAUSE -> PAUSED`, `PAUSED+RESUME -> CLEAN`, `{CLEAN, PAUSED}+DOCK -> RETURNING_DOCK`) at battery >= 15% and `errorCode == 0` returns `Accepted` with exact target mode. | `android-unit-tests` |
| `TEST-CTRL-204` | `AC-202.2`, `AC-202.3` | Negative matrix JUnit (`RobotStateMachineTest`) | Unauthorized pairs (`DOCK+PAUSE`, `DOCK+RESUME`, `DOCK+DOCK`, `CHARGING+DOCK`, `CLEAN+CLEAN`, `CLEAN+RESUME`, any command in `FAULT` or `errorCode != 0`) return `Rejected(INVALID_TRANSITION | FAULT_ACTIVE)`; `batteryPercentage = 14` + `CLEAN`/`RESUME` returns `Rejected(LOW_BATTERY)`; `RobotState` remains untouched. | `android-unit-tests` |
| `TEST-CTRL-205` | `AC-203.1`, `AC-203.2` | `runTest` + `StandardTestDispatcher` (`DashboardViewModelControlTest`) | Dispatching `Clean` from `DOCK` with simulator ack delay `500ms`: at `t=0` `commandState` is `Pending(CLEAN)` and `robotState.mode == DOCK`; after `advanceTimeBy(500); runCurrent()`, `commandState` is `Acknowledged(CLEAN)` and `robotState.mode == CLEAN`. | `android-unit-tests` |
| `TEST-CTRL-206` | `AC-203.3` | `runTest` with fault-injected `SimulatorRobotTransport` | When next transport send fails (`TRANSPORT_REJECTED` or `TIMEOUT`), `commandState` transitions `Pending -> Rejected` with exact reason & non-blank message while `robotState.mode` remains `DOCK`. | `android-unit-tests` |
| `TEST-CTRL-207` | `AC-204.1` | `runTest` with spy `RobotTransport` counter (`DashboardViewModelControlTest`) | Invoking `onIntent(Clean)` twice consecutively while first ack is suspended increments transport send counter to exactly `1`, preserving active `Pending` `commandId`. | `android-unit-tests` |
| `TEST-CTRL-208` | `AC-204.2`, `AC-204.3` | `runTest` conflict sequence (`DashboardViewModelControlTest`) | While `CLEAN` is `Pending`, dispatching `DOCK` sends `0` extra transport commands, keeps `Pending(CLEAN)`, sets `busyConflictNotice`; once `CLEAN` acks (`advanceTimeBy`), dispatching `DOCK` immediately transitions to `Pending(DOCK) -> Acknowledged(DOCK)`. | `android-unit-tests` |
| `TEST-CTRL-209` | `AC-205.1`, `AC-205.3` | Contract JUnit (`SimulatorRobotTransportTest`) | Concurrent calls to `sendCommand` serialize cleanly via mutex without interleaved corrupted states; injected failure returns `CommandResult.Rejected` cleanly with unchanged `robotState.value`. | `android-unit-tests` |
| `TEST-CTRL-210` | `AC-205.2` | Virtual-time `runTest` lifecycle (`SimulatorRobotTransportTest`) | Issuing `DOCK` while `CLEAN` returns `RETURNING_DOCK` immediately at ack; advancing virtual clock by `returnToDockDurationMs` transitions `robotState.mode` to `CHARGING`; advancing charging ticks brings battery to `100` and mode to `DOCK`. | `android-unit-tests` |
| `TEST-CTRL-211` | `AC-206.1` | Property/boundary JUnit (`RobotStateInvariantsTest`, `SimulatorRobotTransportTest`) | Constructing `RobotState(batteryPercentage = -1)` or `101` throws `IllegalArgumentException`; prolonged simulated `CLEAN` drainage never goes negative and triggers auto-`RETURNING_DOCK` below `15%`; prolonged `CHARGING` clamps cleanly at `100%`. | `android-unit-tests` |
| `TEST-CTRL-212` | `AC-206.2`, `AC-206.3` | Invariant matrix JUnit (`RobotStateInvariantsTest`) | `isDocked == true` strictly for `DOCK` and `CHARGING` across all 8 modes; `name` and `isDustbinClean` remain identical across `CLEAN -> PAUSE -> RESUME -> DOCK -> CHARGING`; `errorCode != 0` consistently maps fault guard behavior. | `android-unit-tests` |
| `TEST-CTRL-213` | `AC-207.1`, `AC-207.2` | Table-driven JUnit (`ControlAvailabilityTest`, `DashboardViewModelControlTest`) | Independent expected `(canClean, canPause, canResume, canDock)` tuples verified for all 8 modes, battery `< 15%`, `errorCode != 0`, and `Pending` overlay; MVI `onIntent` drives all state transitions cleanly on `TestDispatcherProvider`. | `android-unit-tests` |
| `TEST-CTRL-214` | `AC-207.3` | `runTest` feedback dismissal (`DashboardViewModelControlTest`) | Invoking `onIntent(DismissCommandFeedback)` after `Rejected` resets `commandState` to `Idle` and clears `busyConflictNotice` while `robotState` telemetry remains intact. | `android-unit-tests` |
| `TEST-CTRL-215` | `AC-208.1`, `AC-208.2`, `AC-208.3` | Robolectric + `createComposeRule()` UI test (`DashboardScreenControlTest`) | Renders `DashboardScreen` across `DOCK`, `CLEAN`, `PAUSED`, `FAULT`, and `Pending` states: asserts exact `assertIsEnabled()` / `assertIsNotEnabled()` on `btn_clean`, `btn_pause`, `btn_resume`, `btn_dock`, checks `command_status_banner` text, asserts `simulator_badge` (`"OFFLINE"`) remains displayed, and verifies button click invokes expected intent. | `android-unit-tests` |
| `TEST-CTRL-216` | `REQ-CTRL-201`..`208` Spec Gate | Harness CLI (`spec-hygiene`, `android-lint`, `android-build`, `apk-validation`) | `spec_validator.py` confirms 100% traceability coverage and globally unique IDs; post-implementation CI validates clean lint (`lintDebug`), APK assembly (`assembleDebug`), and APK structural/security compliance (`apk_validator.py`). | `spec-hygiene` |

---

## 3. Defect-to-Regression Commitment

Any defect uncovered during FCT-002 implementation or review MUST follow the mandatory lifecycle before merging:
1. Record minimal reproduction in `verification/defects.jsonl` + `specs/changes/FCT-002/regression-backlog.md` (if needed).
2. Add a failing (RED) unit/Compose test targeting the exact root cause.
3. Implement minimal clean fix -> observe GREEN -> verify 7-check candidate pipeline.
