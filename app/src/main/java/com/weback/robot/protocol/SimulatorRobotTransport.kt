package com.weback.robot.protocol

import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.RobotState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SimulatorRobotTransport(
    initialState: RobotState = RobotState(
        name = "WeBack Simulator Robot",
        batteryPercentage = 100,
        mode = RobotMode.DOCK,
        isDustbinClean = true,
        errorCode = 0
    )
) : RobotTransport {
    private val _robotState = MutableStateFlow(initialState)
    override val robotState: StateFlow<RobotState> = _robotState.asStateFlow()

    override suspend fun sendCommand(command: String) {
        // Offline simulator mode: no physical or remote command dispatch
    }
}
