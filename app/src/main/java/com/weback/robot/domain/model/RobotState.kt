package com.weback.robot.domain.model

data class RobotState(
    val name: String = "WeBack Simulator Robot",
    val batteryPercentage: Int = 100,
    val mode: RobotMode = RobotMode.DOCK,
    val isDustbinClean: Boolean = true,
    val errorCode: Int = 0
) {
    val isDocked: Boolean
        get() = mode == RobotMode.DOCK
}
