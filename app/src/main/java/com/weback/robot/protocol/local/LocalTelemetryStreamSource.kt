package com.weback.robot.protocol.local

import kotlinx.coroutines.flow.Flow

/**
 * Origin classification for local telemetry observation.
 */
enum class LocalTelemetrySourceKind(val displayLabel: String) {
    SYNTHETIC_REPLAY("SYNTHETIC REPLAY"),
    LIVE_LAN_READ_ONLY("LOCAL READ-ONLY")
}

/**
 * Typed stream failure categories exposing zero raw socket messages, host strings, or payloads.
 */
enum class LocalStreamFailureCode {
    ASSET_LOAD_FAILED,
    TRUST_REJECTED,
    CONNECT_TIMEOUT,
    IO_FAILURE
}

/**
 * Read-only local stream events delivered to [com.weback.robot.app.local.LocalMapRepository].
 */
sealed interface LocalTelemetryEvent {
    data class Connected(val peerVerified: Boolean = true) : LocalTelemetryEvent

    /**
     * Defensive byte slice copy isolating internal chunk arrays from downstream consumers.
     */
    class BytesChunk(rawBytes: ByteArray) : LocalTelemetryEvent {
        private val data: ByteArray = rawBytes.copyOf()

        val size: Int
            get() = data.size

        fun copyBytes(): ByteArray = data.copyOf()

        override fun equals(other: Any?): Boolean =
            this === other || (other is BytesChunk && data.contentEquals(other.data))

        override fun hashCode(): Int = data.contentHashCode()

        override fun toString(): String = "BytesChunk(size=${data.size})"
    }

    data object EndOfStream : LocalTelemetryEvent

    data class StreamFailure(val errorCode: LocalStreamFailureCode) : LocalTelemetryEvent
}

/**
 * Read-only HOSLAM telemetry byte stream boundary.
 * Structurally exposes zero outbound command, write, probe, or handshake-reply methods.
 */
interface LocalTelemetryStreamSource : AutoCloseable {
    val sourceKind: LocalTelemetrySourceKind
    fun openStream(): Flow<LocalTelemetryEvent>
}
