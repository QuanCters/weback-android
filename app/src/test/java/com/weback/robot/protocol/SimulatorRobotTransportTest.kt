package com.weback.robot.protocol

import com.weback.robot.core.MonotonicClock
import com.weback.robot.domain.model.CommandRejectionReason
import com.weback.robot.domain.model.CommandResult
import com.weback.robot.domain.model.RobotCommand
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.RobotState
import com.weback.robot.domain.model.SimulatorErrorCodes
import com.weback.robot.testutils.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorRobotTransportTest {

    private fun TestScope.createClock(): MonotonicClock = MonotonicClock { testScheduler.currentTime }

    @Test
    fun testSimulatorConfigHasDocumentedSyntheticDefaults() {
        val cfg = SimulatorConfig()
        assertEquals(250L, cfg.ackDelayMs)
        assertEquals(2_000L, cfg.returnToDockDurationMs)
        assertEquals(5_000L, cfg.cleanBatteryDrainIntervalMs)
        assertEquals(1, cfg.cleanBatteryDrainStep)
        assertEquals(1_000L, cfg.chargeIntervalMs)
        assertEquals(5, cfg.chargeStepPercent)
        assertEquals(15, cfg.lowBatteryThreshold)
    }

    @Test
    fun testConcurrentSendCommandSerializesSafelyViaStateMutex() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = SimulatorRobotTransport(
            initialState = RobotState(mode = RobotMode.DOCK, batteryPercentage = 100),
            dispatcherProvider = TestDispatcherProvider(dispatcher),
            config = SimulatorConfig(ackDelayMs = 200L),
            clock = createClock()
        )

        val first = async { transport.sendCommand(RobotCommand.CLEAN) }
        val second = async { transport.sendCommand(RobotCommand.CLEAN) }

        advanceTimeBy(200L)
        runCurrent()

        val r1 = first.await()
        val r2 = second.await()

        val ackCount = listOf(r1, r2).count { it is CommandResult.Acknowledged }
        val rejectCount = listOf(r1, r2).count {
            it is CommandResult.Rejected && it.reason == CommandRejectionReason.INVALID_TRANSITION
        }
        assertEquals("Exactly one concurrent CLEAN should succeed", 1, ackCount)
        assertEquals("Second concurrent CLEAN should see CLEAN mode and reject", 1, rejectCount)
        assertEquals(RobotMode.CLEAN, transport.robotState.value.mode)

        transport.close()
    }

    @Test
    fun testDeterministicVirtualTimeReturnToDockChargingToDockLifecycle() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = SimulatorRobotTransport(
            initialState = RobotState(
                name = "WeBack Alpha",
                batteryPercentage = 90,
                mode = RobotMode.CLEAN,
                isDustbinClean = false
            ),
            dispatcherProvider = TestDispatcherProvider(dispatcher),
            config = SimulatorConfig(
                ackDelayMs = 250L,
                returnToDockDurationMs = 2_000L,
                cleanBatteryDrainIntervalMs = 5_000L,
                chargeIntervalMs = 1_000L,
                chargeStepPercent = 5
            ),
            clock = createClock()
        )

        val result = transport.sendCommand(RobotCommand.DOCK)
        assertTrue(result is CommandResult.Acknowledged)
        assertEquals(RobotMode.RETURNING_DOCK, transport.robotState.value.mode)
        assertFalse(transport.robotState.value.isDocked)

        // Just before arrival delay completes: still RETURNING_DOCK
        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(RobotMode.RETURNING_DOCK, transport.robotState.value.mode)

        // Arrival completes -> CHARGING
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(RobotMode.CHARGING, transport.robotState.value.mode)
        assertTrue(transport.robotState.value.isDocked)
        assertEquals(90, transport.robotState.value.batteryPercentage)

        // 1s charge tick -> 95%
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(95, transport.robotState.value.batteryPercentage)
        assertEquals(RobotMode.CHARGING, transport.robotState.value.mode)

        // Next 1s charge tick -> 100% and transitions to DOCK
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(100, transport.robotState.value.batteryPercentage)
        assertEquals(RobotMode.DOCK, transport.robotState.value.mode)
        assertTrue(transport.robotState.value.isDocked)
        assertEquals("WeBack Alpha", transport.robotState.value.name)
        assertFalse(transport.robotState.value.isDustbinClean)

        // Subsequent ticks do not exceed 100%
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(100, transport.robotState.value.batteryPercentage)
        assertEquals(RobotMode.DOCK, transport.robotState.value.mode)

        transport.close()
    }

    @Test
    fun testInitialChargingAtHundredTransitionsToDockOnTick() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = SimulatorRobotTransport(
            initialState = RobotState(mode = RobotMode.CHARGING, batteryPercentage = 100),
            dispatcherProvider = TestDispatcherProvider(dispatcher),
            config = SimulatorConfig(chargeIntervalMs = 500L),
            clock = createClock()
        )

        assertEquals(RobotMode.CHARGING, transport.robotState.value.mode)
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(RobotMode.DOCK, transport.robotState.value.mode)
        assertEquals(100, transport.robotState.value.batteryPercentage)

        transport.close()
    }

    @Test
    fun testCleanDrainageAutoReturnsBelowFifteenAndZeroBatteryUndockedEntersFault() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val autoReturnTransport = SimulatorRobotTransport(
            initialState = RobotState(mode = RobotMode.CLEAN, batteryPercentage = 16),
            dispatcherProvider = TestDispatcherProvider(dispatcher),
            config = SimulatorConfig(
                cleanBatteryDrainIntervalMs = 1_000L,
                cleanBatteryDrainStep = 1,
                returnToDockDurationMs = 2_000L,
                chargeIntervalMs = 1_000L
            ),
            clock = createClock()
        )

        // Tick 1: 16 -> 15 (still CLEAN)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(15, autoReturnTransport.robotState.value.batteryPercentage)
        assertEquals(RobotMode.CLEAN, autoReturnTransport.robotState.value.mode)

        // Tick 2: 15 -> 14 (< 15 -> auto RETURNING_DOCK)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(14, autoReturnTransport.robotState.value.batteryPercentage)
        assertEquals(RobotMode.RETURNING_DOCK, autoReturnTransport.robotState.value.mode)
        autoReturnTransport.close()

        // Step drop straight to 0 while undocked enters FAULT with synthetic error code 9001
        val depletedTransport = SimulatorRobotTransport(
            initialState = RobotState(mode = RobotMode.CLEAN, batteryPercentage = 10),
            dispatcherProvider = TestDispatcherProvider(dispatcher),
            config = SimulatorConfig(
                cleanBatteryDrainIntervalMs = 1_000L,
                cleanBatteryDrainStep = 15
            ),
            clock = createClock()
        )
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(0, depletedTransport.robotState.value.batteryPercentage)
        assertEquals(RobotMode.FAULT, depletedTransport.robotState.value.mode)
        assertEquals(SimulatorErrorCodes.SYNTHETIC_BATTERY_DEPLETED, depletedTransport.robotState.value.errorCode)
        assertFalse(depletedTransport.robotState.value.isDocked)
        depletedTransport.close()
    }

    @Test
    fun testInitialZeroBatteryAndNonZeroErrorNormalization() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)

        // Undocked 0% initial state enters FAULT
        val undockedZero = SimulatorRobotTransport(
            initialState = RobotState(mode = RobotMode.CLEAN, batteryPercentage = 0),
            dispatcherProvider = TestDispatcherProvider(dispatcher)
        )
        assertEquals(RobotMode.FAULT, undockedZero.robotState.value.mode)
        assertEquals(SimulatorErrorCodes.SYNTHETIC_BATTERY_DEPLETED, undockedZero.robotState.value.errorCode)
        undockedZero.close()

        // Docked 0% initial state remains DOCK
        val dockedZero = SimulatorRobotTransport(
            initialState = RobotState(mode = RobotMode.DOCK, batteryPercentage = 0),
            dispatcherProvider = TestDispatcherProvider(dispatcher)
        )
        assertEquals(RobotMode.DOCK, dockedZero.robotState.value.mode)
        assertEquals(0, dockedZero.robotState.value.errorCode)
        dockedZero.close()

        // Nonzero errorCode normalizes mode to FAULT
        val errorInit = SimulatorRobotTransport(
            initialState = RobotState(mode = RobotMode.IDLE, batteryPercentage = 80, errorCode = 9002),
            dispatcherProvider = TestDispatcherProvider(dispatcher)
        )
        assertEquals(RobotMode.FAULT, errorInit.robotState.value.mode)
        assertEquals(9002, errorInit.robotState.value.errorCode)
        errorInit.close()
    }

    @Test
    fun testCommandVsTickRaceRevalidatesAtCommitPoint() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = SimulatorRobotTransport(
            initialState = RobotState(mode = RobotMode.CLEAN, batteryPercentage = 15),
            dispatcherProvider = TestDispatcherProvider(dispatcher),
            config = SimulatorConfig(
                ackDelayMs = 600L,
                cleanBatteryDrainIntervalMs = 300L,
                cleanBatteryDrainStep = 1,
                returnToDockDurationMs = 5_000L
            ),
            clock = createClock()
        )

        // Launch PAUSE whose ack latency spans the 300ms drain tick (15 -> 14 -> RETURNING_DOCK)
        val pauseDeferred = async { transport.sendCommand(RobotCommand.PAUSE) }

        // Advance past drain tick but before command commit
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(RobotMode.RETURNING_DOCK, transport.robotState.value.mode)
        assertEquals(14, transport.robotState.value.batteryPercentage)

        // Advance to command commit at 600ms -> PAUSE must revalidate against RETURNING_DOCK and reject
        advanceTimeBy(300L)
        runCurrent()
        val result = pauseDeferred.await()
        assertTrue(result is CommandResult.Rejected)
        assertEquals(CommandRejectionReason.INVALID_TRANSITION, (result as CommandResult.Rejected).reason)
        assertEquals(RobotMode.RETURNING_DOCK, transport.robotState.value.mode)

        transport.close()
    }

    @Test
    fun testFaultInjectionDuringAckDelayPreventsStaleCommandCommit() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = SimulatorRobotTransport(
            initialState = RobotState(mode = RobotMode.DOCK, batteryPercentage = 100),
            dispatcherProvider = TestDispatcherProvider(dispatcher),
            config = SimulatorConfig(ackDelayMs = 500L),
            clock = createClock()
        )

        val cleanDeferred = async { transport.sendCommand(RobotCommand.CLEAN) }
        advanceTimeBy(200L)
        runCurrent()
        transport.injectFault(SimulatorErrorCodes.SYNTHETIC_INJECTED_FAULT)

        advanceTimeBy(300L)
        runCurrent()
        val result = cleanDeferred.await()
        assertTrue(result is CommandResult.Rejected)
        assertEquals(CommandRejectionReason.FAULT_ACTIVE, (result as CommandResult.Rejected).reason)
        assertEquals(RobotMode.FAULT, transport.robotState.value.mode)

        transport.close()
    }

    @Test
    fun testObsoleteDockingAndChargingJobsCancelledByNewerTransitions() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = SimulatorRobotTransport(
            initialState = RobotState(mode = RobotMode.CHARGING, batteryPercentage = 50),
            dispatcherProvider = TestDispatcherProvider(dispatcher),
            config = SimulatorConfig(
                ackDelayMs = 100L,
                returnToDockDurationMs = 1_000L,
                cleanBatteryDrainIntervalMs = 10_000L,
                chargeIntervalMs = 400L,
                chargeStepPercent = 10
            ),
            clock = createClock()
        )

        // 400ms: CHARGING tick -> 60%
        advanceTimeBy(400L)
        runCurrent()
        assertEquals(60, transport.robotState.value.batteryPercentage)

        // 500ms: CLEAN interrupts CHARGING, cancelling charging job
        val cleanResult = transport.sendCommand(RobotCommand.CLEAN)
        assertTrue(cleanResult is CommandResult.Acknowledged)
        assertEquals(RobotMode.CLEAN, transport.robotState.value.mode)

        // Advance past what would have been next charging tick -> battery must NOT increment
        advanceTimeBy(800L)
        runCurrent()
        assertEquals(RobotMode.CLEAN, transport.robotState.value.mode)
        assertEquals(60, transport.robotState.value.batteryPercentage)

        // Issue DOCK -> RETURNING_DOCK scheduled for 1_000ms, then inject FAULT before arrival
        val dockResult = transport.sendCommand(RobotCommand.DOCK)
        assertTrue(dockResult is CommandResult.Acknowledged)
        assertEquals(RobotMode.RETURNING_DOCK, transport.robotState.value.mode)

        advanceTimeBy(300L)
        runCurrent()
        transport.injectFault()
        assertEquals(RobotMode.FAULT, transport.robotState.value.mode)

        // Advance well past returnToDockDurationMs -> obsolete dock arrival must NOT overwrite FAULT
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(RobotMode.FAULT, transport.robotState.value.mode)

        transport.close()
    }

    @Test
    fun testOneShotFailureInjectionAndCloseLifecycleLeakPrevention() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = SimulatorRobotTransport(
            initialState = RobotState(mode = RobotMode.DOCK, batteryPercentage = 100),
            dispatcherProvider = TestDispatcherProvider(dispatcher),
            config = SimulatorConfig(
                ackDelayMs = 100L,
                cleanBatteryDrainIntervalMs = 500L
            ),
            clock = createClock()
        )

        transport.injectNextCommandRejection(CommandRejectionReason.TRANSPORT_REJECTED, "Simulated transport rejection")
        val rejected = transport.sendCommand(RobotCommand.CLEAN)
        assertTrue(rejected is CommandResult.Rejected)
        assertEquals(CommandRejectionReason.TRANSPORT_REJECTED, (rejected as CommandResult.Rejected).reason)
        assertEquals(RobotMode.DOCK, transport.robotState.value.mode)

        // One-shot exception injection propagates, leaves state unchanged, and releases stateMutex
        transport.injectNextCommandException(IllegalStateException("Simulated transport exception"))
        var caughtException: Throwable? = null
        try {
            transport.sendCommand(RobotCommand.CLEAN)
        } catch (ex: IllegalStateException) {
            caughtException = ex
        }
        assertEquals("Simulated transport exception", caughtException?.message)
        assertEquals(RobotMode.DOCK, transport.robotState.value.mode)

        // Next valid command succeeds cleanly (both one-shot hooks cleared and mutex released)
        val acked = transport.sendCommand(RobotCommand.CLEAN)
        assertTrue(acked is CommandResult.Acknowledged)
        assertEquals(RobotMode.CLEAN, transport.robotState.value.mode)
        assertTrue(transport.hasActiveSimulationJob())

        // Closing transport immediately cancels active simulation jobs and prevents further mutations
        transport.close()
        assertFalse(transport.hasActiveSimulationJob())
        advanceUntilIdle()
        assertEquals(100, transport.robotState.value.batteryPercentage)
        assertEquals(RobotMode.CLEAN, transport.robotState.value.mode)
    }
}
