package com.weback.robot.protocol

import com.weback.robot.domain.model.CommandResult
import com.weback.robot.domain.model.RobotCommand
import com.weback.robot.domain.model.RobotState
import kotlinx.coroutines.flow.StateFlow

/**
 * Clean-room abstraction for robot state observation and command dispatch.
 */
interface RobotTransport {
    val robotState: StateFlow<RobotState>
    suspend fun sendCommand(command: RobotCommand): CommandResult
}
