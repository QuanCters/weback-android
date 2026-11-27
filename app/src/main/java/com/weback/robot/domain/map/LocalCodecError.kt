package com.weback.robot.domain.map

/**
 * Typed envelope and framing failure values emitted by the HOSLAM stream framer.
 * Error values report 0-based session-relative byte offsets and carry zero raw payloads.
 */
sealed interface LocalCodecError {
    val frameStartOffset: Long

    data class InvalidMagic(
        override val frameStartOffset: Long
    ) : LocalCodecError

    data class InvalidLength(
        override val frameStartOffset: Long,
        val declaredLength: Long
    ) : LocalCodecError

    data class FrameTooLarge(
        override val frameStartOffset: Long,
        val declaredLength: Long
    ) : LocalCodecError

    data class TruncatedFrame(
        override val frameStartOffset: Long,
        val bytesReadInFrame: Int
    ) : LocalCodecError

    data class InvalidChecksum(
        override val frameStartOffset: Long
    ) : LocalCodecError
}
