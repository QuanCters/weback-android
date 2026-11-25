package com.weback.robot.protocol

import com.weback.robot.domain.model.RobotState
import kotlinx.coroutines.flow.StateFlow

interface RobotTransport {
    val robotState: StateFlow<RobotState>
    suspend fun sendCommand(command: String)
}
