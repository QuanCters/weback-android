package com.weback.robot.domain.control

import com.weback.robot.domain.model.CommandRejectionReason
import com.weback.robot.domain.model.RobotCommand
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.RobotState

/**
 * Pure outcome of evaluating a [RobotCommand] against committed [RobotState].
 */
sealed interface TransitionDecision {
    val isAccepted: Boolean
        get() = this is Accepted

    data class Accepted(val targetMode: RobotMode) : TransitionDecision

    data class Rejected(
        val reason: CommandRejectionReason,
        val message: String
    ) : TransitionDecision
}

/**
 * Pure finite-state transition evaluator enforcing deterministic guard precedence:
 * 1. FAULT_ACTIVE
 * 2. LOW_BATTERY (for CLEAN or RESUME below 15%)
 * 3. INVALID_TRANSITION
 */
object RobotStateMachine {
    const val MIN_CLEAN_BATTERY_PERCENT = 15

    fun evaluateTransition(current: RobotState, command: RobotCommand): TransitionDecision {
        if (current.isFault) {
            return TransitionDecision.Rejected(
                reason = CommandRejectionReason.FAULT_ACTIVE,
                message = "Fault active (mode=${current.mode}, errorCode=${current.errorCode}); command ${command.name} blocked."
            )
        }

        if ((command == RobotCommand.CLEAN || command == RobotCommand.RESUME) &&
            current.batteryPercentage < MIN_CLEAN_BATTERY_PERCENT
        ) {
            return TransitionDecision.Rejected(
                reason = CommandRejectionReason.LOW_BATTERY,
                message = "Battery ${current.batteryPercentage}% is below $MIN_CLEAN_BATTERY_PERCENT% required for ${command.name}."
            )
        }

        val targetMode = when (command) {
            RobotCommand.CLEAN -> when (current.mode) {
                RobotMode.DOCK, RobotMode.CHARGING, RobotMode.DORMANT, RobotMode.IDLE -> RobotMode.CLEAN
                else -> null
            }
            RobotCommand.PAUSE -> when (current.mode) {
                RobotMode.CLEAN -> RobotMode.PAUSED
                else -> null
            }
            RobotCommand.RESUME -> when (current.mode) {
                RobotMode.PAUSED -> RobotMode.CLEAN
                else -> null
            }
            RobotCommand.DOCK -> when (current.mode) {
                RobotMode.CLEAN, RobotMode.PAUSED -> RobotMode.RETURNING_DOCK
                else -> null
            }
        }

        return if (targetMode != null) {
            TransitionDecision.Accepted(targetMode)
        } else {
            TransitionDecision.Rejected(
                reason = CommandRejectionReason.INVALID_TRANSITION,
                message = "Command ${command.name} is invalid in mode ${current.mode}."
            )
        }
    }
}
