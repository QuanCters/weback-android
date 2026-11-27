package com.weback.robot.domain.map

/**
 * Typed envelope, framing, and layout validation failure values emitted by the
 * HOSLAM stream framer and frame decoder.
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

    data class UnsupportedLayout(
        override val frameStartOffset: Long,
        val kind: HoslamFrameKind,
        val reasonCode: String
    ) : LocalCodecError

    data class InvalidDimensions(
        override val frameStartOffset: Long,
        val width: Long,
        val height: Long
    ) : LocalCodecError

    data class InvalidPadding(
        override val frameStartOffset: Long
    ) : LocalCodecError
}
