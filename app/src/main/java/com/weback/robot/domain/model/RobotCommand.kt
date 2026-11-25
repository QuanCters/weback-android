package com.weback.robot.domain.model

/**
 * Typed robot control commands mapped to RE-observed WebSocket wire tokens.
 */
enum class RobotCommand(val wireStatus: String) {
    CLEAN(wireStatus = "Clean"),
    PAUSE(wireStatus = "Pause"),
    RESUME(wireStatus = "Clean"),
    DOCK(wireStatus = "GoHome")
}
