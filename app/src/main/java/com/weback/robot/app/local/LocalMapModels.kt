package com.weback.robot.app.local

import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.LocalCodecError
import com.weback.robot.domain.map.RasterSnapshot
import com.weback.robot.protocol.local.LocalStreamFailureCode
import com.weback.robot.protocol.local.LocalTelemetrySourceKind

/**
 * Canonical connection & freshness FSM states (`design.md` §3.1).
 */
enum class LocalMapConnectionState {
    IDLE,
    CONNECTING,
    WAITING_FOR_MAP,
    STREAMING,
    STALE,
    DISCONNECTED,
    ERROR
}

/**
 * Typed disconnect/error/stop reasons for local HOSLAM sessions.
 */
enum class LocalMapTerminalReason {
    NONE,
    USER_STOPPED,
    EOF_BEFORE_FIRST_MAP,
    REMOTE_EOF,
    SESSION_LIMIT_REACHED,
    BACKGROUNDED,
    CONFIG_CHANGE_RECONNECT_REQUIRED,
    CODEC_ERROR,
    STREAM_IO_ERROR,
    FIRST_MAP_TIMEOUT,
    IN_FRAME_TIMEOUT,
    ASSET_LOAD_FAILED
}

/**
 * Closed enumeration matching every [LocalCodecError] subtype without arbitrary error strings.
 */
enum class LocalCodecErrorCode {
    NONE,
    INVALID_MAGIC,
    INVALID_LENGTH,
    FRAME_TOO_LARGE,
    TRUNCATED_FRAME,
    INVALID_CHECKSUM,
    UNSUPPORTED_LAYOUT,
    INVALID_DIMENSIONS,
    INVALID_PADDING
}

fun LocalCodecError.toCode(): LocalCodecErrorCode = when (this) {
    is LocalCodecError.InvalidMagic -> LocalCodecErrorCode.INVALID_MAGIC
    is LocalCodecError.InvalidLength -> LocalCodecErrorCode.INVALID_LENGTH
    is LocalCodecError.FrameTooLarge -> LocalCodecErrorCode.FRAME_TOO_LARGE
    is LocalCodecError.TruncatedFrame -> LocalCodecErrorCode.TRUNCATED_FRAME
    is LocalCodecError.InvalidChecksum -> LocalCodecErrorCode.INVALID_CHECKSUM
    is LocalCodecError.UnsupportedLayout -> LocalCodecErrorCode.UNSUPPORTED_LAYOUT
    is LocalCodecError.InvalidDimensions -> LocalCodecErrorCode.INVALID_DIMENSIONS
    is LocalCodecError.InvalidPadding -> LocalCodecErrorCode.INVALID_PADDING
}

/**
 * Playback substate scoped exclusively to offline synthetic fixture replay (`AC-505.1`).
 */
enum class SyntheticReplayPlaybackState {
    IDLE,
    PLAYING,
    PAUSED,
    COMPLETED,
    FAILED
}

/**
 * Allowlisted diagnostic categories (`design.md` §4).
 */
enum class LocalDiagnosticCategory {
    SESSION_START,
    SOURCE_CONNECTED,
    MAP_DECODED,
    AUX_COUNTED,
    TRAJ_COUNTED,
    STALE_ENTERED,
    STALE_RECOVERED,
    CODEC_ERROR,
    STREAM_ERROR,
    SESSION_DISCONNECTED,
    SESSION_STOPPED,
    REPLAY_PAUSED,
    REPLAY_RESUMED,
    REPLAY_RESTARTED
}

/**
 * Construction-time privacy-preserving local diagnostic event (`design.md` §4, `AC-508.2`).
 * Contains strictly closed enums and bounded integer/long metrics — zero free-form Strings,
 * zero Throwables, zero LAN IPs, zero SSIDs, and zero raw frame/raster bytes.
 */
data class LocalDiagnosticEvent(
    val category: LocalDiagnosticCategory,
    val connectionState: LocalMapConnectionState,
    val codecErrorCode: LocalCodecErrorCode = LocalCodecErrorCode.NONE,
    val streamErrorCode: LocalStreamFailureCode? = null,
    val terminalReason: LocalMapTerminalReason = LocalMapTerminalReason.NONE,
    val frameKind: HoslamFrameKind? = null,
    val frameStartOffset: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val mapCount: Long = 0L,
    val auxCount: Long = 0L,
    val trajCount: Long = 0L,
    val generationId: Long = 0L,
    val elapsedSessionMs: Long = 0L
)

/**
 * Immutable local map viewer & repository snapshot.
 */
data class LocalMapRepositoryState(
    val sourceKind: LocalTelemetrySourceKind = LocalTelemetrySourceKind.SYNTHETIC_REPLAY,
    val connectionState: LocalMapConnectionState = LocalMapConnectionState.IDLE,
    val terminalReason: LocalMapTerminalReason = LocalMapTerminalReason.NONE,
    val codecError: LocalCodecError? = null,
    val codecErrorCode: LocalCodecErrorCode = LocalCodecErrorCode.NONE,
    val replayState: SyntheticReplayPlaybackState = SyntheticReplayPlaybackState.IDLE,
    val currentSnapshot: RasterSnapshot? = null,
    val frameGeneration: Long = 0L,
    val sessionGeneration: Long = 0L,
    val mapCount: Long = 0L,
    val auxCount: Long = 0L,
    val trajectoryCount: Long = 0L,
    val lastValidMapElapsedMs: Long? = null,
    val mapAgeMs: Long = 0L,
    val mapViewAccepted: Boolean = false
) {
    val isLive: Boolean
        get() = sourceKind == LocalTelemetrySourceKind.LIVE_LAN_READ_ONLY &&
            connectionState == LocalMapConnectionState.STREAMING &&
            replayState != SyntheticReplayPlaybackState.PAUSED

    val isStale: Boolean
        get() = connectionState == LocalMapConnectionState.STALE ||
            (currentSnapshot != null &&
                (connectionState == LocalMapConnectionState.DISCONNECTED ||
                    connectionState == LocalMapConnectionState.ERROR))
}
