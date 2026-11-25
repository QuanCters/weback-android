package com.weback.robot.domain

import com.weback.robot.domain.control.RobotStateMachine
import com.weback.robot.domain.control.TransitionDecision
import com.weback.robot.domain.model.CommandRejectionReason
import com.weback.robot.domain.model.RobotCommand
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.RobotState
import com.weback.robot.domain.model.SimulatorErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotStateMachineTest {

    private fun expectedDecisionOracle(
        mode: RobotMode,
        command: RobotCommand,
        battery: Int,
        errorCode: Int
    ): TransitionDecision {
        // Independent literal specification oracle (does NOT delegate to RobotStateMachine)
        if (mode == RobotMode.FAULT || errorCode != 0) {
            return TransitionDecision.Rejected(
                reason = CommandRejectionReason.FAULT_ACTIVE,
                message = "expected fault active"
            )
        }
        if ((command == RobotCommand.CLEAN || command == RobotCommand.RESUME) && battery < 15) {
            return TransitionDecision.Rejected(
                reason = CommandRejectionReason.LOW_BATTERY,
                message = "expected low battery"
            )
        }
        return when (mode to command) {
            RobotMode.DOCK to RobotCommand.CLEAN,
            RobotMode.CHARGING to RobotCommand.CLEAN,
            RobotMode.DORMANT to RobotCommand.CLEAN,
            RobotMode.IDLE to RobotCommand.CLEAN -> TransitionDecision.Accepted(RobotMode.CLEAN)

            RobotMode.CLEAN to RobotCommand.PAUSE -> TransitionDecision.Accepted(RobotMode.PAUSED)
            RobotMode.PAUSED to RobotCommand.RESUME -> TransitionDecision.Accepted(RobotMode.CLEAN)

            RobotMode.CLEAN to RobotCommand.DOCK,
            RobotMode.PAUSED to RobotCommand.DOCK -> TransitionDecision.Accepted(RobotMode.RETURNING_DOCK)

            else -> TransitionDecision.Rejected(
                reason = CommandRejectionReason.INVALID_TRANSITION,
                message = "expected invalid transition"
            )
        }
    }

    @Test
    fun testExhaustiveEightByFourTransitionMatrixAcrossBoundariesAndFaults() {
        val batteryBoundaries = listOf(0, 14, 15, 100)
        val errorCodes = listOf(0, SimulatorErrorCodes.SYNTHETIC_BATTERY_DEPLETED)

        for (mode in RobotMode.entries) {
            for (command in RobotCommand.entries) {
                for (battery in batteryBoundaries) {
                    for (errorCode in errorCodes) {
                        val initialState = RobotState(
                            name = "WeBack Matrix Bot",
                            batteryPercentage = battery,
                            mode = mode,
                            isDustbinClean = false,
                            errorCode = errorCode
                        )
                        val expected = expectedDecisionOracle(mode, command, battery, errorCode)
                        val actual = RobotStateMachine.evaluateTransition(initialState, command)

                        val context = "mode=$mode, cmd=$command, batt=$battery, err=$errorCode"
                        when (expected) {
                            is TransitionDecision.Accepted -> {
                                assertTrue("Expected Accepted for $context, got $actual", actual is TransitionDecision.Accepted)
                                assertEquals(
                                    "Target mode mismatch for $context",
                                    expected.targetMode,
                                    (actual as TransitionDecision.Accepted).targetMode
                                )
                            }
                            is TransitionDecision.Rejected -> {
                                assertFalse("Expected Rejected for $context, got $actual", actual.isAccepted)
                                assertTrue("Expected Rejected instance for $context", actual is TransitionDecision.Rejected)
                                val rejected = actual as TransitionDecision.Rejected
                                assertEquals(
                                    "Rejection reason mismatch for $context",
                                    expected.reason,
                                    rejected.reason
                                )
                                assertTrue("Rejection message must not be blank for $context", rejected.message.isNotBlank())
                            }
                        }

                        // Verify purity / non-mutation of input RobotState
                        assertEquals(mode, initialState.mode)
                        assertEquals(battery, initialState.batteryPercentage)
                        assertEquals(errorCode, initialState.errorCode)
                        assertEquals("WeBack Matrix Bot", initialState.name)
                        assertFalse(initialState.isDustbinClean)
                    }
                }
            }
        }
    }

    @Test
    fun testRejectionPrecedenceOrderExplicitly() {
        // 1. FAULT_ACTIVE overrides LOW_BATTERY and INVALID_TRANSITION
        val faultLowBattInvalid = RobotState(mode = RobotMode.FAULT, batteryPercentage = 0, errorCode = 9001)
        val d1 = RobotStateMachine.evaluateTransition(faultLowBattInvalid, RobotCommand.CLEAN)
        assertEquals(CommandRejectionReason.FAULT_ACTIVE, (d1 as TransitionDecision.Rejected).reason)

        val nonFaultModeWithErrorLowBatt = RobotState(mode = RobotMode.DOCK, batteryPercentage = 0, errorCode = 9002)
        val d2 = RobotStateMachine.evaluateTransition(nonFaultModeWithErrorLowBatt, RobotCommand.CLEAN)
        assertEquals(CommandRejectionReason.FAULT_ACTIVE, (d2 as TransitionDecision.Rejected).reason)

        // 2. LOW_BATTERY overrides INVALID_TRANSITION for CLEAN and RESUME when not faulted
        val pausedLowBatt = RobotState(mode = RobotMode.PAUSED, batteryPercentage = 14, errorCode = 0)
        val d3 = RobotStateMachine.evaluateTransition(pausedLowBatt, RobotCommand.CLEAN)
        assertEquals(CommandRejectionReason.LOW_BATTERY, (d3 as TransitionDecision.Rejected).reason)

        val cleanLowBattResume = RobotState(mode = RobotMode.CLEAN, batteryPercentage = 14, errorCode = 0)
        val d4 = RobotStateMachine.evaluateTransition(cleanLowBattResume, RobotCommand.RESUME)
        assertEquals(CommandRejectionReason.LOW_BATTERY, (d4 as TransitionDecision.Rejected).reason)

        // 3. PAUSE and DOCK are NOT blocked by LOW_BATTERY when valid (only CLEAN/RESUME require >= 15%)
        val d5 = RobotStateMachine.evaluateTransition(cleanLowBattResume, RobotCommand.DOCK)
        assertEquals(TransitionDecision.Accepted(RobotMode.RETURNING_DOCK), d5)

        val d6 = RobotStateMachine.evaluateTransition(cleanLowBattResume, RobotCommand.PAUSE)
        assertEquals(TransitionDecision.Accepted(RobotMode.PAUSED), d6)
    }
}
