package com.weback.robot.domain.model

/**
 * Committed robot telemetry snapshot with enforced battery and docking invariants.
 */
data class RobotState(
    val name: String = "WeBack Simulator Robot",
    val batteryPercentage: Int = 100,
    val mode: RobotMode = RobotMode.DOCK,
    val isDustbinClean: Boolean = true,
    val errorCode: Int = 0
) {
    init {
        require(batteryPercentage in 0..100) {
            "Battery percentage must be between 0 and 100, got $batteryPercentage"
        }
    }

    val isDocked: Boolean
        get() = mode == RobotMode.DOCK || mode == RobotMode.CHARGING

    val isFault: Boolean
        get() = mode == RobotMode.FAULT || errorCode != 0
}
