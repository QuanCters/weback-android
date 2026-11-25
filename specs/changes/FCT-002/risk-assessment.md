# FCT-002 Risk Assessment — Simulated Robot Control

- **Change ID:** `FCT-002`
- **Classification:** `T2` (Medium-High — Application Domain State Machine, Coroutine Transport & Control UI; per [policies/risk-tiers.md](../../../policies/risk-tiers.md))
- **Gate Target:** Gate G1 (Proposal) / G2 (Specification & Architecture) / G3 (Implementation Readiness)

---

## 1. Risk Classification Rationale

FCT-002 upgrades `:app` from a passive telemetry viewer to an interactive command-driven control client backed by a concurrent coroutine simulator. While it still operates **100% offline with zero live cloud or physical robot communication** (eliminating physical safety hazards in this milestone), bugs in transition logic, async command ordering, cancellation cleanup, timer-vs-command races, or UI button locking establish the core control abstractions that future live WebSocket/AWS IoT transports (`FCT-004+`) will inherit. Per [policies/risk-tiers.md](../../../policies/risk-tiers.md), this places FCT-002 cleanly in **Tier `T2`**.

---

## 2. Threat & Failure Mode Register

| Risk ID | Failure Mode / Hazard | Severity | Mitigation & Verification Strategy |
| :--- | :--- | :--- | :--- |
| **RSK-201** | **Optimistic UI Mode Desync:** UI flips displayed robot mode to `CLEAN` before transport acknowledges, or rolls back committed telemetry on failure/timeout. | High | `DashboardViewModel` emits `CommandExecutionState.Pending` first and keeps committed `robotState` authoritative without optimistic flips or failure rollbacks (`TEST-CTRL-205`, `TEST-CTRL-206`, `TEST-CTRL-217`). |
| **RSK-202** | **Duplicate, Conflicting or Tick-vs-Command Race (TOCTOU):** Rapid double-tap, concurrent intents, or delayed battery ticks/return-to-dock jobs overwrite newer commands or hold locks across `delay`. | High | `DashboardViewModel` uses atomic `stateLock` admission + `commandId` completion correlation (`TEST-CTRL-207`, `TEST-CTRL-208`, `TEST-CTRL-219`); `SimulatorRobotTransport` uses unified `stateMutex` (never held across `delay`) + `stateGeneration` checks + obsolete job cancellation (`TEST-CTRL-209`, `TEST-CTRL-220`, `TEST-CTRL-221`). |
| **RSK-203** | **Duplicate Rule Logic Drift:** `ControlAvailability` UI enablement checks diverge from `RobotStateMachine.evaluateTransition` guards or rejection precedence. | Medium | `ControlAvailability.from` delegates directly to `RobotStateMachine.evaluateTransition` with strict precedence `FAULT_ACTIVE > LOW_BATTERY > INVALID_TRANSITION` (`TEST-CTRL-204`, `TEST-CTRL-213`). |
| **RSK-204** | **Coroutine Timing Flakiness, Timeout/Cancellation Leaks:** Real-time sleeps, swallowed `CancellationException`, stuck `Pending` lock on cancellation, or leaked simulator jobs. | High | All delays and progression loops use injected `DispatcherProvider`, `MonotonicClock`, and `SimulatorConfig` exercised deterministically with `StandardTestDispatcher`; `CancellationException` cleans up `Pending` and rethrows; `close()` cancels simulator scope (`TEST-CTRL-210`, `TEST-CTRL-217`, `TEST-CTRL-218`, `TEST-CTRL-222`). |
| **RSK-205** | **UI / Domain Layer Contamination:** Serialization annotations or Android SDK classes (`Handler`, `Log`, `SystemClock`) leak into `com.weback.robot.domain`. | Medium | Domain package purity test and `lintDebug` enforce zero `android.*` / `androidx.*` / coroutine imports inside `domain` (`TEST-CTRL-201`). |
| **RSK-206** | **Misleading Hardware Simulation Labeling (`C-011`) or Synthetic Error Conflation:** User mistakes simulated acknowledgements, battery changes, or synthetic error codes for physical hardware behavior. | High | Persistent `OFFLINE` `simulator_badge`, string-resourced simulator banner across light/dark modes, and explicit `SimulatorErrorCodes` separation (`TEST-CTRL-215`, `TEST-CTRL-216`). |
| **RSK-207** | **Robolectric Toolchain / Timeout Incompatibility (`IC-205`):** Robolectric Compose tests fail under JDK 21 / AGP 8.7.2 / Compose BOM 2024.10.01 or exceed 180s `android-unit-tests` budget. | Medium | Execute focused Compose/Robolectric spike with pinned `4.14.1` first in Task 4 (`isIncludeAndroidResources = true`) and verify JUnit XML freshness and runtime margin without upgrading toolchain or altering policy. |

---

## 3. Non-Waivable Invariants (FCT-002)

1. `policies/checks.json` (7 checks) and `verification/` harness scripts MUST remain unmodified on `spec/fct-002-simulated-robot-control` and FCT-002 implementation PRs unless separately authorized.
2. Zero real network sockets, AWS SDK endpoints, credentials, or proprietary vendor binaries (`C-001`, `C-006`).
3. Pure Kotlin `com.weback.robot.domain` purity (`C-002`).
4. Every FCT-002 requirement (`REQ-CTRL-201`..`REQ-CTRL-208`) traces 100% through `traceability.json` to an existing approved check ID and independent test oracle.
