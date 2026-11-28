package com.weback.robot.protocol.ws

/**
 * WebSocket lifecycle parameters (`REG-09`, `UNK-AUTH-06`).
 * Note: [sendStallTimeoutMs] models observed outbound send-stall detection in `websocket/e.java:449-458`
 * and MUST NOT be assumed to be an unproven server heartbeat contract.
 */
data class ConnectionConfig(
    val reconnectDelayMs: Long = 4_000L,
    val sendStallTimeoutMs: Long = 20_000L,
    val maxReconnectAttempts: Int = 3
) {
    init {
        require(reconnectDelayMs > 0L) { "reconnectDelayMs must be > 0" }
        require(sendStallTimeoutMs > 0L) { "sendStallTimeoutMs must be > 0" }
        require(maxReconnectAttempts >= 0) { "maxReconnectAttempts must be >= 0" }
    }
}
