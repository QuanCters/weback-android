# FCT-002 Regression Backlog & Remediated Defect Catalog

**Change ID:** `FCT-002`  
**Milestone:** `FCT-002`  
**Document ID:** `SPEC-REG-FCT002`  
**Version:** `1.0.0 (Bounded Acceptance Closeout)`  
**Status:** `RESOLVED (Remediated under FIX-201 through FIX-203)`  

---

## 1. Principles of Defect Governance

In strict accordance with Constitution Invariant `C-009` (Defect-to-Regression Lifecycle), [policies/verification-integrity.md](../../../policies/verification-integrity.md), and SDD 2.0:
- Defect IDs for milestone `FCT-002` begin at `DEF-2026-035`, preserving `DEF-2026-001` through `DEF-2026-024` (`FCT-000`) and `DEF-2026-025` through `DEF-2026-034` (`FCT-001`).
- Historical findings fixed prior to the bounded acceptance cycle (`DEF-2026-036`, `DEF-2026-037`) preserve `pre_fix_red_result: null` without fabricated historical logs, paired with their permanent verified regression tests.

---

## 2. Remediated Defect Catalog (`verification/defects.jsonl`)

### DEF-2026-035: Viewport Scroll Hiding `simulator_badge` & Merged Banner Semantics
- **Classification:** `CONFIRMED_APPLICATION_DEFECT`
- **Requirement / Invariant:** `REQ-CTRL-208`
- **Severity:** `P1`
- **Affected Module:** `app/src/main/java/com/weback/robot/ui/screens/DashboardScreen.kt`
- **Evidence:** Robolectric Compose UI test execution on `320x470dp` viewport failed `2/3` tests because scrolling down to controls pushed `simulator_badge` out of viewport bounds, and `command_status_banner` lacked merged descendant text semantics.
- **Root Cause:** `SimulatorBadgeSection` was placed inside the `verticalScroll` `Column` instead of pinned above scrollable content, and `command_status_banner` did not merge descendant text semantics separately from `btn_dismiss_feedback`.
- **Regression Test Reference:** `app/src/test/java/com/weback/robot/ui/screens/DashboardScreenControlTest.kt`
- **Review Status:** `RESOLVED`

### DEF-2026-036: Telemetry Rollback on `Acknowledged` Settlement
- **Classification:** `CONFIRMED_APPLICATION_DEFECT`
- **Requirement / Invariant:** `AC-203.3`
- **Severity:** `P0`
- **Affected Module:** `app/src/main/java/com/weback/robot/ui/DashboardViewModel.kt`
- **Evidence:** Review of PR #6 identified that `settleCommandResult` unconditionally overwrote `committedRobotState` with `result.resultingState` on `CommandResult.Acknowledged`, rolling back newer transport telemetry emitted between acknowledgement construction and settlement.
- **Root Cause:** `DashboardViewModel` lacked `telemetryVersion` / `PendingSnapshot` tracking to detect forward telemetry progression since command submission.
- **Regression Test Reference:** `app/src/test/java/com/weback/robot/ui/DashboardViewModelControlTest.kt::testAcknowledgedSettlementDoesNotRollbackNewerTelemetry`
- **Pre-Fix RED Result:** `null` (remediated in `04cba32` prior to bounded acceptance cycle; historical RED output not fabricated)
- **Post-Fix GREEN Result:** `PASS: testAcknowledgedSettlementDoesNotRollbackNewerTelemetry passes in DashboardViewModelControlTest (12/12 PASS)`
- **Review Status:** `RESOLVED`

### DEF-2026-037: Pre-Start and Inactive-Scope Cancellation Leaving `Pending` Stuck
- **Classification:** `CONFIRMED_APPLICATION_DEFECT`
- **Requirement / Invariant:** `AC-203.5`
- **Severity:** `P0`
- **Affected Module:** `app/src/main/java/com/weback/robot/ui/DashboardViewModel.kt`
- **Evidence:** Review of PR #6 identified that cancelling `scope` before `dispatchPendingCommand` entered its coroutine body or invoking `onIntent` on an inactive scope left `CommandExecutionState.Pending` stuck without cleanup.
- **Root Cause:** `onIntent` admitted `Pending` without checking `scope.isActive`, and `dispatchPendingCommand` lacked `job.invokeOnCompletion` cancellation cleanup.
- **Regression Test Reference:** `app/src/test/java/com/weback/robot/ui/DashboardViewModelControlTest.kt::testPreStartAndInactiveScopeCancellationNeverLeavesPendingStuck`
- **Pre-Fix RED Result:** `null` (remediated in `04cba32` prior to bounded acceptance cycle; historical RED output not fabricated)
- **Post-Fix GREEN Result:** `PASS: testPreStartAndInactiveScopeCancellationNeverLeavesPendingStuck passes in DashboardViewModelControlTest (12/12 PASS)`
- **Review Status:** `RESOLVED`

### DEF-2026-038: `SimulatorRobotTransport.close()` Close/Commit Synchronization Race (`FIX-201`)
- **Classification:** `CONFIRMED_APPLICATION_DEFECT`
- **Requirement / Invariant:** `AC-205.5`
- **Severity:** `P0`
- **Affected Module:** `app/src/main/java/com/weback/robot/protocol/SimulatorRobotTransport.kt`
- **Evidence:** `close()` mutated `closed` and cancelled jobs outside commit synchronization, allowing a command or timed mutation past its `closed` check to mutate state or acknowledge after `close()` returned.
- **Root Cause:** `close()` did not share a non-blocking `commitLock` monitor with `sendCommand`, timed progression ticks, and `injectFault`.
- **Regression Test Reference:** `app/src/test/java/com/weback/robot/protocol/SimulatorRobotTransportTest.kt::testCloseWaitsForInFlightCommitAndOrdersSynchronously`
- **Pre-Fix RED Result:** `FAILED (12/13 passed, 1 failed): SimulatorRobotTransportTest.testCloseWaitsForInFlightCommitAndOrdersSynchronously - java.lang.AssertionError: close() must wait for in-flight commit critical section rather than completing concurrently`
- **Post-Fix GREEN Result:** `PASS: 17/17 tests in SimulatorRobotTransportTest pass including close during pending ackDelayMs, synchronous commit ordering, queued-command no-deadlock, and concurrent tick stress tests`
- **Review Status:** `RESOLVED`

### DEF-2026-039: Single Battery Threshold Divergence & Charging Arithmetic Overflow (`FIX-202`)
- **Classification:** `CONFIRMED_APPLICATION_DEFECT`
- **Requirement / Invariant:** `AC-202.3`
- **Severity:** `P1`
- **Affected Module:** `app/src/main/java/com/weback/robot/protocol/SimulatorConfig.kt`
- **Evidence:** `SimulatorConfig.lowBatteryThreshold` allowed arbitrary values `1..100` that disagreed with `RobotStateMachine.MIN_CLEAN_BATTERY_PERCENT = 15`, and unbounded `chargeStepPercent` permitted `Int` overflow in `current.batteryPercentage + config.chargeStepPercent`.
- **Root Cause:** Independent threshold validation in `SimulatorConfig` and un-widened `Int` battery step arithmetic.
- **Regression Test Reference:** `app/src/test/java/com/weback/robot/protocol/SimulatorRobotTransportTest.kt::testSimulatorConfigRejectsThresholdDisagreementAndInvalidBounds`
- **Pre-Fix RED Result:** `FAILED (16/17 passed, 1 failed): SimulatorRobotTransportTest.testSimulatorConfigRejectsThresholdDisagreementAndInvalidBounds - java.lang.AssertionError: Expected IllegalArgumentException for lowBatteryThreshold=14`
- **Post-Fix GREEN Result:** `PASS: 17/17 tests in SimulatorRobotTransportTest and 2/2 in RobotStateMachineTest pass; threshold fixed to RobotStateMachine.MIN_CLEAN_BATTERY_PERCENT (15), step bounds 1..100 enforced, and Long-clamped arithmetic verified`
- **Review Status:** `RESOLVED`

### DEF-2026-040: Stale `FCT-001 Foundation` Version Label & UI State Synchronization (`FIX-203`)
- **Classification:** `CONFIRMED_APPLICATION_DEFECT`
- **Requirement / Invariant:** `AC-208.2`
- **Severity:** `P2`
- **Affected Module:** `app/src/main/res/values/strings.xml`
- **Evidence:** `strings.xml` and `DashboardViewModel.kt` still carried `"v0.1.0-alpha (FCT-001 Foundation)"`, and `DashboardScreen.kt` read `R.string.app_version` directly rather than rendering `uiState.appVersion`.
- **Root Cause:** Version/milestone display string was left at the FCT-001 label and duplicated across resources and ViewModel defaults.
- **Regression Test Reference:** `app/src/test/java/com/weback/robot/ui/screens/DashboardScreenControlTest.kt::testDisplayedMilestoneVersionLabelSynchronizedWithStringsAndUiState`
- **Pre-Fix RED Result:** `FAILED (5/7 passed, 2 failed): DashboardViewModelTest.testInitialUiStateEmission and DashboardScreenControlTest.testDisplayedMilestoneVersionLabelSynchronizedWithStringsAndUiState - org.junit.ComparisonFailure: expected:<v0.1.0-alpha (FCT-00[2 Simulated Robot Control])> but was:<v0.1.0-alpha (FCT-00[1 Foundation])>`
- **Post-Fix GREEN Result:** `PASS: 54/54 tests in testDebugUnitTest pass; MainActivity passes R.string.app_version into DashboardViewModel and DashboardScreen renders uiState.appVersion synchronized at v0.1.0-alpha (FCT-002 Simulated Robot Control)`
- **Review Status:** `RESOLVED`
