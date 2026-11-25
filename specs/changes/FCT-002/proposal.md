# Change Proposal: FCT-002 — Simulated Robot Control

- **Change ID:** `FCT-002`
- **Milestone:** Milestone 2 — Simulated Robot Control
- **Risk Tier:** `T2` (Application State Machine, Transport Contract & UI Control; see [policies/risk-tiers.md](../../../policies/risk-tiers.md))
- **Status:** `PROPOSED` (Specification Baseline for Gates G1 / G2 / G3)
- **Prerequisite:** [FCT-001](../FCT-001/proposal.md) merged on `main` (`e7d518137fdca573b7ae799ea0705165c541c1a4`) with active 7-check policy (`policies/checks.json`)
- **Governance Links:** [specs/constitution.md](../../constitution.md) | [specs/tech-stack.md](../../tech-stack.md) | [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md)

---

## 1. Problem Statement

FCT-001 delivered the clean-room Android application foundation (`com.weback.robot`), read-only telemetry models (`RobotState`, `RobotMode`), a stubbed `SimulatorRobotTransport`, a passive `DashboardViewModel`, and an offline Compose dashboard displaying the `OFFLINE` simulator badge. Currently, `SimulatorRobotTransport.sendCommand(command: String)` is a no-op stub, the domain layer has no command model or transition guard rules, and the dashboard renders no interactive Clean, Pause, Resume, or Dock controls.

Before introducing live AWS IoT, WebSocket, or Wi-Fi provisioning networking in later milestones, the application needs a strictly deterministic, verifiable **robot control state machine and asynchronous command lifecycle** running against an offline simulator. Doing this first isolates state-transition bugs, duplicate/conflicting command races, low-battery/fault safety rejections, and UI state synchronization issues behind JVM unit tests and virtual-time coroutine tests.

---

## 2. Objectives

1. **Formalize Robot Control Domain:** Introduce typed `RobotCommand` (`CLEAN`, `PAUSE`, `RESUME`, `DOCK`), expanded `RobotMode` (`DOCK`, `CLEAN`, `PAUSED`, `RETURNING_DOCK`, `CHARGING`, `DORMANT`, `IDLE`, `FAULT`), explicit `CommandExecutionState` (`Idle`, `Pending`, `Acknowledged`, `Rejected`), and pure Kotlin transition evaluator `RobotStateMachine`.
2. **Enforce Safety & Idempotency Guards:** Prevent invalid transitions, duplicate in-flight submissions, conflicting concurrent commands, low-battery cleaning starts (`< 15%`), and active-fault control commands cleanly without mutating committed telemetry.
3. **Upgrade `SimulatorRobotTransport` with Deterministic Time:** Inject coroutine dispatchers/clocks so command acknowledgments, `RETURNING_DOCK -> CHARGING -> DOCK` sequences, battery drain/recharge progression, and test fault injections execute deterministically under `TestScope` virtual time.
4. **Deliver MVI Interactive Controls & Clear Offline Semantics:** Extend `DashboardViewModel` and `DashboardScreen` with unidirectional intents, derived `ControlAvailability`, visible command lifecycle feedback, and persistent `OFFLINE` simulator labeling.
5. **Maintain Policy Stability:** Verify FCT-002 entirely under the existing 7-check approved verification policy (`policies/checks.json`) without modifying protected verification harness scripts or policies.

---

## 3. Scope Boundaries

### 3.1 In Scope
- Clean-room Kotlin domain models and finite-state transition rules inside `:app` (`com.weback.robot.domain`) grounded in documented RE evidence (`SPEC-PROT-01`, `SPEC-STATE-01`, `SPEC-MODEL-01`) with explicit separation between RE-observed wire tokens and newly designed simulator behaviors.
- Typed `RobotTransport` contract extension and deterministic offline `SimulatorRobotTransport` with injectable delay/scheduler and fault injection hooks.
- Telemetry consistency invariants (battery `0..100` bounds, docking vs. active-mode exclusivity, metadata preservation).
- Unidirectional MVI intents and `ControlAvailability` derivation in `DashboardViewModel`.
- Interactive Material 3 Compose controls (`Clean`, `Pause`, `Resume`, `Dock`), command lifecycle banner/indicator, and Compose UI tests executed on JVM via Robolectric inside `testDebugUnitTest`.

### 3.2 Out of Scope (Explicit Non-Goals)
- Live AWS Cognito authentication, AWS IoT MQTT/Shadow networking, or live OkHttp WebSocket connections (deferred to later cloud/telemetry milestones).
- BLE, SoftAP Wi-Fi provisioning, or hardware discovery.
- SLAM floor-plan/map binary parsing, room selection, suction power sliders, or water-level controls beyond core Clean/Pause/Resume/Dock (`FEAT-03` extensions deferred).
- Live physical robot hardware or cloud staging execution (`C-006`, `C-011`).
- Modifications to `policies/` or `verification/` harness scripts.

---

## 4. Separation of Observed RE Evidence vs. Designed Simulator Behavior

Per [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md) (`REG-01`..`REG-05`), FCT-002 maintains strict provenance honesty:
- **RE-Observed Facts (`SPEC-PROT-01`, `SPEC-STATE-01`, `SPEC-MODEL-01`):**
  - Outbound control frame shape (`PROT-WS-01`) carries `topic_name: "explore/app/cmd/..."` and `topic_payload.state` (`working_status`).
  - Documented `working_status` strings include `"Clean"`, `"Pause"`, `"GoHome"`, `"Charging"`, `"Dormant"`, and `"Fault"`.
  - Resuming from `PAUSED` dispatches `working_status = "Clean"` on the wire while logically acting as `Resume`.
- **Newly Designed Clean-Room App & Simulator Semantics (Not Claims of Hardware Behavior):**
  - Typed `CommandExecutionState` (`Idle`, `Pending`, `Acknowledged`, `Rejected`), single in-flight command serialization, `< 15%` low-battery guard threshold, deterministic `RETURNING_DOCK -> CHARGING -> DOCK` simulator step timings, and Compose UI `ControlAvailability` mapping are clean-room client/simulator engineering designs and are explicitly labeled `OFFLINE` in the UI (`C-011`).
