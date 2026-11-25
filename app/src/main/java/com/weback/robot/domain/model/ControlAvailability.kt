package com.weback.robot.domain.model

import com.weback.robot.domain.control.RobotStateMachine

/**
 * Pure UI control enablement derived strictly from committed telemetry and command state.
 */
data class ControlAvailability(
    val canClean: Boolean,
    val canPause: Boolean,
    val canResume: Boolean,
    val canDock: Boolean
) {
    companion object {
        val ALL_DISABLED = ControlAvailability(
            canClean = false,
            canPause = false,
            canResume = false,
            canDock = false
        )

        fun from(
            robotState: RobotState,
            commandState: CommandExecutionState = CommandExecutionState.Idle
        ): ControlAvailability {
            if (commandState is CommandExecutionState.Pending || robotState.isFault) {
                return ALL_DISABLED
            }
            return ControlAvailability(
                canClean = RobotStateMachine.evaluateTransition(robotState, RobotCommand.CLEAN).isAccepted,
                canPause = RobotStateMachine.evaluateTransition(robotState, RobotCommand.PAUSE).isAccepted,
                canResume = RobotStateMachine.evaluateTransition(robotState, RobotCommand.RESUME).isAccepted,
                canDock = RobotStateMachine.evaluateTransition(robotState, RobotCommand.DOCK).isAccepted
            )
        }
    }
}
