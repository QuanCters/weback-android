package com.weback.robot.protocol

/**
 * Synthetic timing and progression configuration for [SimulatorRobotTransport].
 * These defaults govern the clean-room offline simulator only, not hardware timings.
 */
data class SimulatorConfig(
    val ackDelayMs: Long = 250L,
    val returnToDockDurationMs: Long = 2_000L,
    val cleanBatteryDrainIntervalMs: Long = 5_000L,
    val cleanBatteryDrainStep: Int = 1,
    val chargeIntervalMs: Long = 1_000L,
    val chargeStepPercent: Int = 5,
    val lowBatteryThreshold: Int = 15
) {
    init {
        require(ackDelayMs >= 0L) { "ackDelayMs must be >= 0, got $ackDelayMs" }
        require(returnToDockDurationMs >= 0L) { "returnToDockDurationMs must be >= 0, got $returnToDockDurationMs" }
        require(cleanBatteryDrainIntervalMs > 0L) { "cleanBatteryDrainIntervalMs must be > 0, got $cleanBatteryDrainIntervalMs" }
        require(cleanBatteryDrainStep > 0) { "cleanBatteryDrainStep must be > 0, got $cleanBatteryDrainStep" }
        require(chargeIntervalMs > 0L) { "chargeIntervalMs must be > 0, got $chargeIntervalMs" }
        require(chargeStepPercent > 0) { "chargeStepPercent must be > 0, got $chargeStepPercent" }
        require(lowBatteryThreshold in 1..100) { "lowBatteryThreshold must be in 1..100, got $lowBatteryThreshold" }
    }
}
