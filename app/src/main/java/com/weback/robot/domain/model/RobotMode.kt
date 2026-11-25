package com.weback.robot.domain.model

/**
 * Operational states of the robot in the domain and offline simulator.
 */
enum class RobotMode {
    DOCK,
    CLEAN,
    PAUSED,
    RETURNING_DOCK,
    CHARGING,
    DORMANT,
    IDLE,
    FAULT
}
