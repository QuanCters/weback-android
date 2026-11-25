package com.weback.robot.domain

import com.weback.robot.domain.model.CommandExecutionState
import com.weback.robot.domain.model.CommandRejectionReason
import com.weback.robot.domain.model.ControlAvailability
import com.weback.robot.domain.model.RobotCommand
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.RobotState
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlAvailabilityTest {

    private data class AvailabilityCase(
        val label: String,
        val state: RobotState,
        val commandState: CommandExecutionState,
        val expected: ControlAvailability
    )

    @Test
    fun testControlAvailabilityTruthTableAcrossAllModesAndOverlays() {
        val cases = listOf(
            AvailabilityCase(
                label = "DOCK at 100%",
                state = RobotState(mode = RobotMode.DOCK, batteryPercentage = 100),
                commandState = CommandExecutionState.Idle,
                expected = ControlAvailability(canClean = true, canPause = false, canResume = false, canDock = false)
            ),
            AvailabilityCase(
                label = "DOCK at 15%",
                state = RobotState(mode = RobotMode.DOCK, batteryPercentage = 15),
                commandState = CommandExecutionState.Idle,
                expected = ControlAvailability(canClean = true, canPause = false, canResume = false, canDock = false)
            ),
            AvailabilityCase(
                label = "DOCK at 14% (low battery)",
                state = RobotState(mode = RobotMode.DOCK, batteryPercentage = 14),
                commandState = CommandExecutionState.Idle,
                expected = ControlAvailability(canClean = false, canPause = false, canResume = false, canDock = false)
            ),
            AvailabilityCase(
                label = "CHARGING at 50%",
                state = RobotState(mode = RobotMode.CHARGING, batteryPercentage = 50),
                commandState = CommandExecutionState.Idle,
                expected = ControlAvailability(canClean = true, canPause = false, canResume = false, canDock = false)
            ),
            AvailabilityCase(
                label = "CHARGING at 0%",
                state = RobotState(mode = RobotMode.CHARGING, batteryPercentage = 0),
                commandState = CommandExecutionState.Idle,
                expected = ControlAvailability(canClean = false, canPause = false, canResume = false, canDock = false)
            ),
            AvailabilityCase(
                label = "DORMANT at 80%",
                state = RobotState(mode = RobotMode.DORMANT, batteryPercentage = 80),
                commandState = CommandExecutionState.Idle,
                expected = ControlAvailability(canClean = true, canPause = false, canResume = false, canDock = false)
            ),
            AvailabilityCase(
                label = "IDLE at 80%",
                state = RobotState(mode = RobotMode.IDLE, batteryPercentage = 80),
                commandState = CommandExecutionState.Idle,
                expected = ControlAvailability(canClean = true, canPause = false, canResume = false, canDock = false)
            ),
            AvailabilityCase(
                label = "CLEAN at 80%",
                state = RobotState(mode = RobotMode.CLEAN, batteryPercentage = 80),
                commandState = CommandExecutionState.Idle,
                expected = ControlAvailability(canClean = false, canPause = true, canResume = false, canDock = true)
            ),
            AvailabilityCase(
                label = "CLEAN at 14% (PAUSE and DOCK still allowed)",
                state = RobotState(mode = RobotMode.CLEAN, batteryPercentage = 14),
                commandState = CommandExecutionState.Idle,
                expected = ControlAvailability(canClean = false, canPause = true, canResume = false, canDock = true)
            ),
            AvailabilityCase(
                label = "PAUSED at 80%",
                state = RobotState(mode = RobotMode.PAUSED, batteryPercentage = 80),
                commandState = CommandExecutionState.Idle,
                expected = ControlAvailability(canClean = false, canPause = false, canResume = true, canDock = true)
            ),
            AvailabilityCase(
                label = "PAUSED at 15%",
                state = RobotState(mode = RobotMode.PAUSED, batteryPercentage = 15),
                commandState = CommandExecutionState.Idle,
                expected = ControlAvailability(canClean = false, canPause = false, canResume = true, canDock = true)
            ),
            AvailabilityCase(
                label = "PAUSED at 14% (RESUME blocked, DOCK still allowed)",
                state = RobotState(mode = RobotMode.PAUSED, batteryPercentage = 14),
                commandState = CommandExecutionState.Idle,
                expected = ControlAvailability(canClean = false, canPause = false, canResume = false, canDock = true)
            ),
            AvailabilityCase(
                label = "RETURNING_DOCK disables all controls",
                state = RobotState(mode = RobotMode.RETURNING_DOCK, batteryPercentage = 50),
                commandState = CommandExecutionState.Idle,
                expected = ControlAvailability(canClean = false, canPause = false, canResume = false, canDock = false)
            ),
            AvailabilityCase(
                label = "FAULT disables all controls",
                state = RobotState(mode = RobotMode.FAULT, batteryPercentage = 100, errorCode = 9001),
                commandState = CommandExecutionState.Idle,
                expected = ControlAvailability(canClean = false, canPause = false, canResume = false, canDock = false)
            ),
            AvailabilityCase(
                label = "Nonzero errorCode disables all controls even in DOCK",
                state = RobotState(mode = RobotMode.DOCK, batteryPercentage = 100, errorCode = 42),
                commandState = CommandExecutionState.Idle,
                expected = ControlAvailability(canClean = false, canPause = false, canResume = false, canDock = false)
            ),
            AvailabilityCase(
                label = "Pending command disables all controls",
                state = RobotState(mode = RobotMode.CLEAN, batteryPercentage = 100),
                commandState = CommandExecutionState.Pending(RobotCommand.PAUSE, commandId = 1L, submittedAtMs = 10L),
                expected = ControlAvailability(canClean = false, canPause = false, canResume = false, canDock = false)
            ),
            AvailabilityCase(
                label = "Settled Rejected does NOT block valid controls",
                state = RobotState(mode = RobotMode.DOCK, batteryPercentage = 100),
                commandState = CommandExecutionState.Rejected(
                    RobotCommand.PAUSE,
                    commandId = 2L,
                    reason = CommandRejectionReason.INVALID_TRANSITION,
                    message = "invalid",
                    settledAtMs = 20L
                ),
                expected = ControlAvailability(canClean = true, canPause = false, canResume = false, canDock = false)
            )
        )

        for (case in cases) {
            assertEquals(
                "ControlAvailability mismatch for ${case.label}",
                case.expected,
                ControlAvailability.from(case.state, case.commandState)
            )
        }
    }
}
