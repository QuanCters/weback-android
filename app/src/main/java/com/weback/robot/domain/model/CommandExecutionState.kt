package com.weback.robot.domain.model

/**
 * Explicit reasons why a control command was locally guarded or rejected by transport.
 */
enum class CommandRejectionReason {
    FAULT_ACTIVE,
    LOW_BATTERY,
    INVALID_TRANSITION,
    COMMAND_IN_FLIGHT,
    TRANSPORT_REJECTED,
    TIMEOUT,
    TRANSPORT_ERROR
}

/**
 * Immutable UI-observable command execution lifecycle.
 */
sealed interface CommandExecutionState {
    data object Idle : CommandExecutionState

    data class Pending(
        val command: RobotCommand,
        val commandId: Long,
        val submittedAtMs: Long
    ) : CommandExecutionState

    data class Acknowledged(
        val command: RobotCommand,
        val commandId: Long,
        val settledAtMs: Long
    ) : CommandExecutionState

    data class Rejected(
        val command: RobotCommand,
        val commandId: Long,
        val reason: CommandRejectionReason,
        val message: String,
        val settledAtMs: Long
    ) : CommandExecutionState
}
