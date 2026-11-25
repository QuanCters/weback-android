package com.weback.robot.domain.model

/**
 * Structured outcome returned by [com.weback.robot.protocol.RobotTransport.sendCommand].
 */
sealed interface CommandResult {
    data class Acknowledged(
        val resultingState: RobotState,
        val settledAtMs: Long = 0L
    ) : CommandResult

    data class Rejected(
        val reason: CommandRejectionReason,
        val message: String,
        val settledAtMs: Long = 0L
    ) : CommandResult
}
