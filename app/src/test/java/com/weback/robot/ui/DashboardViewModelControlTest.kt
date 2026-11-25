package com.weback.robot.ui

import com.weback.robot.core.MonotonicClock
import com.weback.robot.domain.model.CommandExecutionState
import com.weback.robot.domain.model.CommandRejectionReason
import com.weback.robot.domain.model.CommandResult
import com.weback.robot.domain.model.ControlAvailability
import com.weback.robot.domain.model.RobotCommand
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.RobotState
import com.weback.robot.protocol.RobotTransport
import com.weback.robot.protocol.SimulatorConfig
import com.weback.robot.protocol.SimulatorRobotTransport
import com.weback.robot.testutils.TestDispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelControlTest {

    private class SpyTransport(
        initialState: RobotState = RobotState()
    ) : RobotTransport {
        private val _robotState = MutableStateFlow(initialState)
        override val robotState: StateFlow<RobotState> = _robotState.asStateFlow()

        var sendCount: Int = 0
            private set
        var handler: suspend (RobotCommand, StateFlow<RobotState>) -> CommandResult = { cmd, state ->
            val nextMode = when (cmd) {
                RobotCommand.CLEAN, RobotCommand.RESUME -> RobotMode.CLEAN
                RobotCommand.PAUSE -> RobotMode.PAUSED
                RobotCommand.DOCK -> RobotMode.RETURNING_DOCK
            }
            val updated = state.value.copy(mode = nextMode)
            _robotState.value = updated
            CommandResult.Acknowledged(updated)
        }

        fun emitTelemetry(state: RobotState) {
            _robotState.value = state
        }

        override suspend fun sendCommand(command: RobotCommand): CommandResult {
            sendCount += 1
            return handler(command, _robotState)
        }
    }

    private fun TestScope.clock(): MonotonicClock = MonotonicClock { testScheduler.currentTime }

    @Test
    fun testValidCommandLifecycleIdleToPendingToAcknowledgedWithSimulator() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)
        val clock = clock()
        val transport = SimulatorRobotTransport(
            initialState = RobotState(mode = RobotMode.DOCK, batteryPercentage = 100),
            dispatcherProvider = dispatchers,
            config = SimulatorConfig(ackDelayMs = 500L),
            clock = clock
        )
        val viewModel = DashboardViewModel(
            robotTransport = transport,
            dispatchers = dispatchers,
            coroutineScope = backgroundScope,
            clock = clock
        )
        runCurrent()

        assertEquals(CommandExecutionState.Idle, viewModel.uiState.value.commandState)
        assertEquals(
            ControlAvailability(canClean = true, canPause = false, canResume = false, canDock = false),
            viewModel.uiState.value.controls
        )

        // Dispatch CLEAN: immediately Pending at t=0, mode stays DOCK, all controls locked
        viewModel.onIntent(DashboardIntent.Clean)
        val pendingState = viewModel.uiState.value
        assertTrue(pendingState.commandState is CommandExecutionState.Pending)
        val pending = pendingState.commandState as CommandExecutionState.Pending
        assertEquals(RobotCommand.CLEAN, pending.command)
        assertEquals(1L, pending.commandId)
        assertEquals(0L, pending.submittedAtMs)
        assertEquals(RobotMode.DOCK, pendingState.mode)
        assertEquals(ControlAvailability.ALL_DISABLED, pendingState.controls)

        // Before 500ms ack completes: still Pending and DOCK
        advanceTimeBy(499L)
        runCurrent()
        assertTrue(viewModel.uiState.value.commandState is CommandExecutionState.Pending)
        assertEquals(RobotMode.DOCK, viewModel.uiState.value.mode)

        // At 500ms: settles to Acknowledged and mode updates to CLEAN
        advanceTimeBy(1L)
        runCurrent()
        val ackedState = viewModel.uiState.value
        assertTrue(ackedState.commandState is CommandExecutionState.Acknowledged)
        val acked = ackedState.commandState as CommandExecutionState.Acknowledged
        assertEquals(RobotCommand.CLEAN, acked.command)
        assertEquals(1L, acked.commandId)
        assertEquals(500L, acked.settledAtMs)
        assertEquals(RobotMode.CLEAN, ackedState.mode)
        assertFalse(ackedState.isDocked)
        assertEquals(
            ControlAvailability(canClean = false, canPause = true, canResume = false, canDock = true),
            ackedState.controls
        )

        transport.close()
    }

    @Test
    fun testLocallyInvalidCommandRejectedWithoutInvokingTransport() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val spy = SpyTransport(RobotState(mode = RobotMode.DOCK, batteryPercentage = 14))
        val viewModel = DashboardViewModel(
            robotTransport = spy,
            dispatchers = TestDispatcherProvider(dispatcher),
            coroutineScope = backgroundScope,
            clock = clock()
        )
        runCurrent()

        viewModel.onIntent(DashboardIntent.Clean)
        runCurrent()

        assertEquals(0, spy.sendCount)
        val cmdState = viewModel.uiState.value.commandState
        assertTrue(cmdState is CommandExecutionState.Rejected)
        assertEquals(CommandRejectionReason.LOW_BATTERY, (cmdState as CommandExecutionState.Rejected).reason)
        assertEquals(RobotMode.DOCK, viewModel.uiState.value.mode)
    }

    @Test
    fun testTransportRejectionAndUnexpectedExceptionPreserveCommittedTelemetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val spy = SpyTransport(RobotState(mode = RobotMode.DOCK, batteryPercentage = 90))
        val viewModel = DashboardViewModel(
            robotTransport = spy,
            dispatchers = TestDispatcherProvider(dispatcher),
            coroutineScope = backgroundScope,
            clock = clock()
        )
        runCurrent()

        // 1. Explicit transport rejection
        spy.handler = { _, _ ->
            CommandResult.Rejected(CommandRejectionReason.TRANSPORT_REJECTED, "Motor busy")
        }
        viewModel.onIntent(DashboardIntent.Clean)
        runCurrent()

        val rej1 = viewModel.uiState.value.commandState as CommandExecutionState.Rejected
        assertEquals(CommandRejectionReason.TRANSPORT_REJECTED, rej1.reason)
        assertEquals("Motor busy", rej1.message)
        assertEquals(RobotMode.DOCK, viewModel.uiState.value.mode)
        assertEquals(90, viewModel.uiState.value.batteryPercentage)

        // 2. Unexpected transport exception -> TRANSPORT_ERROR, no crash, no telemetry rollback
        spy.handler = { _, _ -> throw IllegalStateException("Socket pipe broken") }
        viewModel.onIntent(DashboardIntent.Clean)
        runCurrent()

        val rej2 = viewModel.uiState.value.commandState as CommandExecutionState.Rejected
        assertEquals(CommandRejectionReason.TRANSPORT_ERROR, rej2.reason)
        assertTrue(rej2.message.contains("Socket pipe broken"))
        assertEquals(2L, rej2.commandId)
        assertEquals(RobotMode.DOCK, viewModel.uiState.value.mode)
        assertEquals(90, viewModel.uiState.value.batteryPercentage)
    }

    @Test
    fun testDuplicateAndConcurrentAdmissionsDispatchOnlyOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        val spy = SpyTransport(RobotState(mode = RobotMode.DOCK, batteryPercentage = 100))
        spy.handler = { _, state ->
            gate.await()
            val updated = state.value.copy(mode = RobotMode.CLEAN)
            spy.emitTelemetry(updated)
            CommandResult.Acknowledged(updated)
        }
        val viewModel = DashboardViewModel(
            robotTransport = spy,
            dispatchers = TestDispatcherProvider(dispatcher),
            coroutineScope = backgroundScope,
            clock = clock()
        )
        runCurrent()

        // Concurrent duplicate Clean calls
        listOf(
            async { viewModel.onIntent(DashboardIntent.Clean) },
            async { viewModel.onIntent(DashboardIntent.Clean) },
            async { viewModel.onIntent(DashboardIntent.Clean) }
        ).awaitAll()
        runCurrent()

        assertEquals(1, spy.sendCount)
        val pending = viewModel.uiState.value.commandState as CommandExecutionState.Pending
        assertEquals(1L, pending.commandId)

        gate.complete(Unit)
        runCurrent()
        assertEquals(RobotMode.CLEAN, viewModel.uiState.value.mode)
        assertTrue(viewModel.uiState.value.commandState is CommandExecutionState.Acknowledged)
    }

    @Test
    fun testConflictingCommandAndDismissDuringPendingPreserveActiveLock() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)
        val transport = SimulatorRobotTransport(
            initialState = RobotState(mode = RobotMode.CLEAN, batteryPercentage = 80),
            dispatcherProvider = dispatchers,
            config = SimulatorConfig(ackDelayMs = 400L),
            clock = clock()
        )
        val viewModel = DashboardViewModel(
            robotTransport = transport,
            dispatchers = dispatchers,
            coroutineScope = backgroundScope,
            clock = clock()
        )
        runCurrent()

        // Start PAUSE (Pending id=1)
        viewModel.onIntent(DashboardIntent.Pause)
        assertTrue(viewModel.uiState.value.commandState is CommandExecutionState.Pending)

        // Conflicting DOCK while PAUSE Pending
        viewModel.onIntent(DashboardIntent.Dock)
        val conflictState = viewModel.uiState.value
        assertTrue(conflictState.commandState is CommandExecutionState.Pending)
        assertEquals(RobotCommand.PAUSE, (conflictState.commandState as CommandExecutionState.Pending).command)
        assertNotNull(conflictState.busyConflictNotice)
        assertTrue(conflictState.busyConflictNotice!!.contains("COMMAND_IN_FLIGHT"))
        assertEquals(ControlAvailability.ALL_DISABLED, conflictState.controls)

        // DismissCommandFeedback while Pending clears conflict notice ONLY; Pending lock MUST hold
        viewModel.onIntent(DashboardIntent.DismissCommandFeedback)
        val stillPending = viewModel.uiState.value
        assertNull(stillPending.busyConflictNotice)
        assertTrue(stillPending.commandState is CommandExecutionState.Pending)
        assertEquals(ControlAvailability.ALL_DISABLED, stillPending.controls)

        // Let PAUSE settle -> PAUSED, then DOCK immediately succeeds
        advanceTimeBy(400L)
        runCurrent()
        assertEquals(RobotMode.PAUSED, viewModel.uiState.value.mode)
        assertTrue(viewModel.uiState.value.commandState is CommandExecutionState.Acknowledged)

        viewModel.onIntent(DashboardIntent.Dock)
        advanceTimeBy(400L)
        runCurrent()
        assertEquals(RobotMode.RETURNING_DOCK, viewModel.uiState.value.mode)
        assertEquals(2L, (viewModel.uiState.value.commandState as CommandExecutionState.Acknowledged).commandId)

        transport.close()
    }

    @Test
    fun testBoundedCommandTimeoutRejectsAndReleasesLock() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)
        val transport = SimulatorRobotTransport(
            initialState = RobotState(mode = RobotMode.DOCK, batteryPercentage = 100),
            dispatcherProvider = dispatchers,
            config = SimulatorConfig(ackDelayMs = 10_000L),
            clock = clock()
        )
        val viewModel = DashboardViewModel(
            robotTransport = transport,
            dispatchers = dispatchers,
            coroutineScope = backgroundScope,
            clock = clock(),
            commandTimeoutMs = 5_000L
        )
        runCurrent()

        viewModel.onIntent(DashboardIntent.Clean)
        assertTrue(viewModel.uiState.value.commandState is CommandExecutionState.Pending)

        // Just before 5000ms: still Pending
        advanceTimeBy(4_999L)
        runCurrent()
        assertTrue(viewModel.uiState.value.commandState is CommandExecutionState.Pending)

        // At 5000ms: bounded timeout fires -> Rejected(TIMEOUT), telemetry unchanged
        advanceTimeBy(1L)
        runCurrent()
        val timedOut = viewModel.uiState.value
        assertTrue(timedOut.commandState is CommandExecutionState.Rejected)
        val rej = timedOut.commandState as CommandExecutionState.Rejected
        assertEquals(CommandRejectionReason.TIMEOUT, rej.reason)
        assertEquals(5_000L, rej.settledAtMs)
        assertEquals(RobotMode.DOCK, timedOut.mode)
        assertTrue(timedOut.controls.canClean)

        transport.close()
    }

    @Test
    fun testExternalCancellationCleansPendingWithoutSwallowingAndAllowsRecovery() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        val spy = SpyTransport(RobotState(mode = RobotMode.DOCK, batteryPercentage = 100))
        var modeBehavior = "THROW_CANCEL"
        spy.handler = { _, state ->
            when (modeBehavior) {
                "THROW_CANCEL" -> {
                    gate.await()
                    throw CancellationException("Transport cancelled externally")
                }
                else -> {
                    val updated = state.value.copy(mode = RobotMode.CLEAN)
                    spy.emitTelemetry(updated)
                    CommandResult.Acknowledged(updated)
                }
            }
        }

        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        val viewModel = DashboardViewModel(
            robotTransport = spy,
            dispatchers = TestDispatcherProvider(dispatcher),
            coroutineScope = vmScope,
            clock = clock()
        )
        runCurrent()
        val initialChildren = vmScope.coroutineContext[Job]!!.children.toSet()

        viewModel.onIntent(DashboardIntent.Clean)
        val commandJob = (vmScope.coroutineContext[Job]!!.children.toSet() - initialChildren).single()
        var completionCause: Throwable? = null
        var completed = false
        commandJob.invokeOnCompletion { cause ->
            completed = true
            completionCause = cause
        }

        runCurrent()
        assertTrue(viewModel.uiState.value.commandState is CommandExecutionState.Pending)

        // Release gate -> transport throws CancellationException; must NOT be swallowed, Pending resets to Idle
        gate.complete(Unit)
        runCurrent()

        assertTrue(completed)
        assertTrue("CancellationException must propagate out of command job", completionCause is CancellationException)
        assertEquals(CommandExecutionState.Idle, viewModel.uiState.value.commandState)
        assertEquals(RobotMode.DOCK, viewModel.uiState.value.mode)
        assertTrue(viewModel.uiState.value.controls.canClean)

        // Subsequent command recovers cleanly on same active ViewModel
        modeBehavior = "ACK"
        viewModel.onIntent(DashboardIntent.Clean)
        runCurrent()
        assertEquals(RobotMode.CLEAN, viewModel.uiState.value.mode)
        assertTrue(viewModel.uiState.value.commandState is CommandExecutionState.Acknowledged)

        // Scope cancellation during a new Pending command cleans up Pending and leaves zero active jobs
        modeBehavior = "THROW_CANCEL"
        val hangGate = CompletableDeferred<Unit>()
        spy.handler = { _, _ ->
            hangGate.await()
            CommandResult.Acknowledged(RobotState(mode = RobotMode.PAUSED))
        }
        viewModel.onIntent(DashboardIntent.Pause)
        runCurrent()
        assertTrue(viewModel.uiState.value.commandState is CommandExecutionState.Pending)

        vmScope.cancel()
        runCurrent()
        assertEquals(CommandExecutionState.Idle, viewModel.uiState.value.commandState)
        assertEquals(0, vmScope.coroutineContext[Job]!!.children.count { it.isActive })
    }

    @Test
    fun testStaleCompletionCannotOverwriteNewerCommandLifecycle() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        val spy = SpyTransport(RobotState(mode = RobotMode.DOCK, batteryPercentage = 100))
        spy.handler = { _, state ->
            gate.await()
            CommandResult.Acknowledged(state.value.copy(mode = RobotMode.CLEAN))
        }

        val viewModel = DashboardViewModel(
            robotTransport = spy,
            dispatchers = TestDispatcherProvider(dispatcher),
            coroutineScope = backgroundScope,
            clock = clock()
        )
        runCurrent()

        // Start commandId=1, then timeout so commandId=2 can start cleanly
        viewModel.onIntent(DashboardIntent.Clean)
        runCurrent()
        assertEquals(1L, (viewModel.uiState.value.commandState as CommandExecutionState.Pending).commandId)

        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(CommandRejectionReason.TIMEOUT, (viewModel.uiState.value.commandState as CommandExecutionState.Rejected).reason)

        // Start commandId=2 (Pending)
        viewModel.onIntent(DashboardIntent.Clean)
        runCurrent()
        assertEquals(2L, (viewModel.uiState.value.commandState as CommandExecutionState.Pending).commandId)

        // Attempt stale settlement for commandId=1 -> MUST be ignored
        viewModel.settleCommandResult(
            command = RobotCommand.CLEAN,
            commandId = 1L,
            result = CommandResult.Acknowledged(RobotState(mode = RobotMode.CLEAN))
        )
        val currentCmd = viewModel.uiState.value.commandState
        assertTrue(currentCmd is CommandExecutionState.Pending)
        assertEquals(2L, (currentCmd as CommandExecutionState.Pending).commandId)

        // Complete commandId=2 cleanly
        gate.complete(Unit)
        runCurrent()
        assertEquals(2L, (viewModel.uiState.value.commandState as CommandExecutionState.Acknowledged).commandId)
    }

    @Test
    fun testPostSettlementDismissResetsFeedbackWithoutMutatingTelemetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)
        val transport = SimulatorRobotTransport(
            initialState = RobotState(mode = RobotMode.CLEAN, batteryPercentage = 75),
            dispatcherProvider = dispatchers,
            config = SimulatorConfig(ackDelayMs = 100L),
            clock = clock()
        )
        val viewModel = DashboardViewModel(
            robotTransport = transport,
            dispatchers = dispatchers,
            coroutineScope = backgroundScope,
            clock = clock()
        )
        runCurrent()

        viewModel.onIntent(DashboardIntent.Dock)
        advanceTimeBy(100L)
        runCurrent()
        assertTrue(viewModel.uiState.value.commandState is CommandExecutionState.Acknowledged)
        assertEquals(RobotMode.RETURNING_DOCK, viewModel.uiState.value.mode)

        viewModel.onIntent(DashboardIntent.DismissCommandFeedback)
        assertEquals(CommandExecutionState.Idle, viewModel.uiState.value.commandState)
        assertEquals(RobotMode.RETURNING_DOCK, viewModel.uiState.value.mode)
        assertTrue(transport.hasActiveSimulationJob())

        // Simulator progression continues uninterrupted
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(RobotMode.CHARGING, viewModel.uiState.value.mode)
        assertTrue(viewModel.uiState.value.isDocked)

        transport.close()
    }

    @Test
    fun testFullCleanPauseResumeDockMviLifecycle() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)
        val transport = SimulatorRobotTransport(
            initialState = RobotState(mode = RobotMode.DOCK, batteryPercentage = 60),
            dispatcherProvider = dispatchers,
            config = SimulatorConfig(ackDelayMs = 100L, returnToDockDurationMs = 500L),
            clock = clock()
        )
        val viewModel = DashboardViewModel(
            robotTransport = transport,
            dispatchers = dispatchers,
            coroutineScope = backgroundScope,
            clock = clock()
        )
        runCurrent()

        viewModel.onIntent(DashboardIntent.Clean)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(RobotMode.CLEAN, viewModel.uiState.value.mode)

        viewModel.onIntent(DashboardIntent.Pause)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(RobotMode.PAUSED, viewModel.uiState.value.mode)
        assertTrue(viewModel.uiState.value.controls.canResume)
        assertTrue(viewModel.uiState.value.controls.canDock)

        viewModel.onIntent(DashboardIntent.Resume)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(RobotMode.CLEAN, viewModel.uiState.value.mode)

        viewModel.onIntent(DashboardIntent.Dock)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(RobotMode.RETURNING_DOCK, viewModel.uiState.value.mode)
        assertEquals(ControlAvailability.ALL_DISABLED, viewModel.uiState.value.controls)

        transport.close()
    }
}
