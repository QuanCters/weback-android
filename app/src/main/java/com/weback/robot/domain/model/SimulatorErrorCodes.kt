package com.weback.robot.domain.model

/**
 * Synthetic error codes used strictly by the clean-room offline simulator.
 * These are NOT observed physical hardware firmware error codes.
 */
object SimulatorErrorCodes {
    const val NONE = 0
    const val SYNTHETIC_BATTERY_DEPLETED = 9001
    const val SYNTHETIC_INJECTED_FAULT = 9002
}
