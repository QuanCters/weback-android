package com.weback.robot.domain.pilot

import com.weback.robot.domain.model.CommandRejectionReason
import com.weback.robot.domain.model.CommandResult
import com.weback.robot.domain.model.RobotCommand

/**
 * Domain guard blocking all live physical robot actuation (`send_to_device`) during FCT-004
 * and treating any unexpected command attempt toward a live pilot session as a pilot-abort event (`SEC-402`).
 */
class ReadOnlyPilotGuard(
    private val isPilotReadOnlyActive: () -> Boolean,
    private val onUnexpectedCommandAttempt: (RobotCommand, PilotAbortReason) -> Unit
) {
    fun guardLiveCommand(command: RobotCommand): CommandResult {
        if (isPilotReadOnlyActive()) {
            onUnexpectedCommandAttempt(command, PilotAbortReason.UnexpectedOutboundCommandAttempt)
        }
        return CommandResult.Rejected(
            reason = CommandRejectionReason.TRANSPORT_REJECTED,
            message = "Read-only FCT-004 pilot prohibits physical robot command ${command.name}"
        )
    }
}
