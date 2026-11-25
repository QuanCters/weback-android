# FCT-002 Risk Assessment — Simulated Robot Control

- **Change ID:** `FCT-002`
- **Classification:** `T2` (Medium-High — Application Domain State Machine, Coroutine Transport & Control UI; per [policies/risk-tiers.md](../../../policies/risk-tiers.md))
- **Gate Target:** Gate G1 (Proposal) / G2 (Specification & Architecture) / G3 (Implementation Readiness)

---

## 1. Risk Classification Rationale

FCT-002 upgrades `:app` from a passive telemetry viewer to an interactive command-driven control client backed by a concurrent coroutine simulator. While it still operates **100% offline with zero live cloud or physical robot communication** (eliminating physical safety hazards in this milestone), bugs in transition logic, async command ordering, or UI button locking establish the core control abstractions that future live WebSocket/AWS IoT transports (`FCT-004+`) will inherit. Per [policies/risk-tiers.md](../../../policies/risk-tiers.md), this places FCT-002 cleanly in **Tier `T2`**.

---

## 2. Threat & Failure Mode Register

| Risk ID | Failure Mode / Hazard | Severity | Mitigation & Verification Strategy |
| :--- | :--- | :--- | :--- |
| **RSK-201** | **Optimistic UI Mode Desync:** UI flips displayed robot mode to `CLEAN` before transport acknowledges, then silently stays wrong if transport rejects. | High | `DashboardViewModel` MUST emit `CommandExecutionState.Pending` first and mutate committed `robotState` only from `robotTransport.robotState` emissions (`TEST-CTRL-205`, `TEST-CTRL-207`). |
| **RSK-202** | **Duplicate / Conflicting Command Race:** Rapid double-tap on `Clean` or tapping `Dock` while `Clean` ack is in flight dispatches overlapping transport mutations (TOCTOU). | High | Single-writer `Pending` guard in `DashboardViewModel` (`TEST-CTRL-208`, `TEST-CTRL-209`) paired with `Mutex` and state re-validation inside `SimulatorRobotTransport` (`TEST-CTRL-210`). |
| **RSK-203** | **Duplicate Rule Logic Drift:** `ControlAvailability` UI enablement checks diverge from `RobotStateMachine.evaluateTransition` guards, enabling disabled commands or disabling valid ones. | Medium | `ControlAvailability.from` delegates directly to `RobotStateMachine.evaluateTransition` so UI enablement and domain rejection share one truth source (`TEST-CTRL-213`). |
| **RSK-204** | **Coroutines Timing Flakiness in Tests:** Real-time `delay()` or wall-clock `System.currentTimeMillis()` causes intermittent CI unit-test failures under host load. | High | All delays and progression loops use injected `DispatcherProvider` and virtual-time coroutine delays exercised deterministically with `StandardTestDispatcher` + `advanceTimeBy` (`TEST-CTRL-210`, `TEST-CTRL-211`). |
| **RSK-205** | **UI / Domain Layer Contamination:** Serialization annotations or Android SDK classes (`Handler`, `Log`, `SystemClock`) leak into `com.weback.robot.domain`. | Medium | Domain package purity test and `lintDebug` enforce zero `android.*` / `androidx.*` imports inside `domain` (`TEST-CTRL-201`). |
| **RSK-206** | **Misleading Hardware Simulation Labeling (`C-011`):** User mistakes simulated acknowledgements/battery changes for a connected physical robot. | High | Persistent `OFFLINE` `simulator_badge` and explicit simulator banner text verified across all Compose screens and transitions (`TEST-CTRL-215`, `TEST-CTRL-216`). |

---

## 3. Non-Waivable Invariants (FCT-002)

1. `policies/checks.json` (7 checks) and `verification/` harness scripts MUST remain unmodified on `spec/fct-002-simulated-robot-control` and FCT-002 implementation PRs unless separately authorized.
2. Zero real network sockets, AWS SDK endpoints, credentials, or proprietary vendor binaries (`C-001`, `C-006`).
3. Pure Kotlin `com.weback.robot.domain` purity (`C-002`).
4. Every FCT-002 requirement (`REQ-CTRL-201`..`REQ-CTRL-208`) traces 100% through `traceability.json` to an existing approved check ID and independent test oracle.
