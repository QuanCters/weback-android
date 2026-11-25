# Change Proposal: FCT-002 — Simulated Robot Control

- **Change ID:** `FCT-002`
- **Milestone:** Milestone 2 — Simulated Robot Control
- **Risk Tier:** `T2` (Application State Machine, Transport Contract & UI Control; see [policies/risk-tiers.md](../../../policies/risk-tiers.md))
- **Status:** `G1/G2/G3 BOUNDED SPECIFICATION REVISION` (Incorporating IC-201 through IC-205)
- **Prerequisite:** [FCT-001](../FCT-001/proposal.md) merged on `main` (`e7d518137fdca573b7ae799ea0705165c541c1a4`) with active 7-check policy (`policies/checks.json`)
- **Governance Links:** [specs/constitution.md](../../constitution.md) | [specs/tech-stack.md](../../tech-stack.md) | [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md)

---

## 1. Problem Statement

FCT-001 delivered the clean-room Android application foundation (`com.weback.robot`), read-only telemetry models (`RobotState`, `RobotMode`), a stubbed `SimulatorRobotTransport`, a passive `DashboardViewModel`, and an offline Compose dashboard displaying the `OFFLINE` simulator badge. Currently, `SimulatorRobotTransport.sendCommand(command: String)` is a no-op stub, the domain layer has no command model or transition guard rules, and the dashboard renders no interactive Clean, Pause, Resume, or Dock controls.

Before introducing live AWS IoT, WebSocket, or Wi-Fi provisioning networking in later milestones, the application needs a strictly deterministic, verifiable **robot control state machine and asynchronous command lifecycle** running against an offline simulator. Doing this first isolates state-transition bugs, duplicate/conflicting command races, command-versus-tick concurrency races, timeout/cancellation leaks, low-battery/fault safety rejections, and UI state synchronization issues behind JVM unit tests, virtual-time coroutine tests, and Robolectric Compose UI tests.

---

## 2. Objectives

1. **Formalize Robot Control Domain:** Retain the typed `RobotCommand` (`CLEAN`, `PAUSE`, `RESUME`, `DOCK`), expanded `RobotMode` (`DOCK`, `CLEAN`, `PAUSED`, `RETURNING_DOCK`, `CHARGING`, `DORMANT`, `IDLE`, `FAULT`), explicit `CommandExecutionState` (`Idle`, `Pending`, `Acknowledged`, `Rejected`), and pure Kotlin transition evaluator `RobotStateMachine`.
2. **Enforce Safety, Precedence & Concurrency Ownership (`IC-201`..`IC-203`):** Enforce deterministic guard precedence (`FAULT_ACTIVE` > `LOW_BATTERY` for `CLEAN`/`RESUME` > `INVALID_TRANSITION`), unified synchronization ownership (`stateMutex` with generation-checked delayed jobs and no lock held across simulated delays), atomic `Pending` admission, bounded timeout, exception isolation, and `CancellationException`-safe `Pending` cleanup.
3. **Upgrade `SimulatorRobotTransport` with Deterministic Time & Lifecycle (`IC-201`, `IC-203`):** Inject `DispatcherProvider`, `SimulatorConfig`, and `MonotonicClock` so command acknowledgments, `RETURNING_DOCK -> CHARGING -> DOCK` sequences, battery drain/recharge progression, undocked zero-battery `FAULT`, and test fault injections execute deterministically under `TestScope` virtual time and cancel cleanly on `close()`.
4. **Deliver MVI Interactive Controls & Robolectric Compose Verification (`IC-205`):** Extend `DashboardViewModel` and `DashboardScreen` with unidirectional intents, derived `ControlAvailability`, visible `Pending`/`Acknowledged`/`Rejected` feedback, string-resourced light/dark accessible UI, persistent `OFFLINE` simulator labeling, and real Compose UI tests executed on JVM via Robolectric inside `testDebugUnitTest`.
5. **Maintain Policy Stability:** Verify FCT-002 entirely under the existing 7-check approved verification policy (`policies/checks.json`) without modifying protected verification harness scripts or policies.

---

## 3. Scope Boundaries

### 3.1 In Scope
- Clean-room Kotlin domain models and finite-state transition rules inside `:app` (`com.weback.robot.domain`) grounded in documented RE evidence (`SPEC-PROT-01`, `SPEC-STATE-01`, `SPEC-MODEL-01`) with explicit separation between RE-observed wire tokens and newly designed simulator behaviors.
- Typed `RobotTransport` contract extension, `SimulatorConfig`, `MonotonicClock`, and deterministic offline `SimulatorRobotTransport` with unified `Mutex` state ownership, generation-scoped simulation jobs, failure injection hooks, and explicit `close()` lifecycle cleanup.
- Telemetry consistency invariants (battery `0..100` bounds, docking vs. active-mode exclusivity, undocked zero-battery `FAULT` with synthetic error code, metadata preservation).
- Unidirectional MVI intents, atomic `Pending` admission, timeout/cancellation/error settlement, `commandId` completion correlation, and `ControlAvailability` derivation in `DashboardViewModel`.
- Interactive Material 3 Compose controls (`Clean`, `Pause`, `Resume`, `Dock`), command lifecycle banner/indicator, Android string resources, light/dark theme compatibility, and real Compose UI tests executed on JVM via Robolectric inside `testDebugUnitTest`.

### 3.2 Out of Scope (Explicit Non-Goals)
- Live AWS Cognito authentication, AWS IoT MQTT/Shadow networking, or live OkHttp WebSocket connections (deferred to later cloud/telemetry milestones).
- BLE, SoftAP Wi-Fi provisioning, or hardware discovery.
- SLAM floor-plan/map binary parsing, room selection, suction power sliders, or water-level controls beyond core Clean/Pause/Resume/Dock (`FEAT-03` extensions deferred).
- User-triggered in-flight command cancellation UI buttons or `FAULT` clear/reset commands (deferred until hardware-confirmed semantics).
- Live physical robot hardware or cloud staging execution (`C-006`, `C-011`).
- Modifications to `policies/` or `verification/` harness scripts.

---

## 4. Separation of Observed RE Evidence vs. Designed Simulator Behavior

Per [docs/reference/RE_BASELINE.md](../../../docs/reference/RE_BASELINE.md) (`REG-01`..`REG-05`), FCT-002 maintains strict provenance honesty:
- **RE-Observed Facts (`SPEC-PROT-01`, `SPEC-STATE-01`, `SPEC-MODEL-01`):**
  - Outbound real-time WebSocket control frame shape (`PROT-WS-01`) carries `"opt": "send_to_device"`, `"topic_name": "$aws/things/{thing}/shadow/update"`, and flat `"topic_payload": {"state": {"working_status": ...}}` (without a `desired` wrapper).
  - Documented `working_status` strings include `"Clean"`, `"Pause"`, `"GoHome"`, `"Charging"`, `"Dormant"`, and `"Fault"`.
  - Resuming from `PAUSED` dispatches `working_status = "Clean"` on the wire while logically acting as `Resume`.
- **Newly Designed Clean-Room App & Simulator Semantics (Not Claims of Hardware Behavior):**
  - Typed `CommandExecutionState` (`Idle`, `Pending`, `Acknowledged`, `Rejected`), monotonic `commandId` correlation, bounded command timeout, `CancellationException` `Pending` cleanup, single in-flight command admission, `<= 14%` (`< 15%`) low-battery guard threshold and `FAULT_ACTIVE > LOW_BATTERY > INVALID_TRANSITION` precedence, synthetic simulator error codes (`9001`, `9002`), deterministic `SimulatorConfig` step timings (`RETURNING_DOCK -> CHARGING -> DOCK`), undocked zero-battery `FAULT`, and Compose UI `ControlAvailability` mapping are clean-room client/simulator engineering designs and are explicitly labeled `OFFLINE` in the UI (`C-010`, `C-011`).
