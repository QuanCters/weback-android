package com.weback.robot.domain.map

/**
 * Immutable, defensively owned raw HOSLAM envelope frame emitted after magic,
 * bounded length, and additive checksum verification.
 *
 * Input bytes are copied on construction and excluded from [toString].
 */
class RawHoslamFrame(
    val kind: HoslamFrameKind,
    val frameStartOffset: Long,
    rawFrameBytes: ByteArray
) {
    companion object {
        const val HEADER_BYTES: Int = 12
        const val CHECKSUM_BYTES: Int = 4
        const val MIN_FRAME_BYTES: Int = 16
        const val MAX_FRAME_BYTES: Int = 2_097_152

        private fun readTrailingU32Le(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xFFL) or
                ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }

    init {
        require(frameStartOffset >= 0L) {
            "frameStartOffset must be non-negative, got $frameStartOffset"
        }
        require(rawFrameBytes.size in MIN_FRAME_BYTES..MAX_FRAME_BYTES) {
            "rawFrameBytes length must be in $MIN_FRAME_BYTES..$MAX_FRAME_BYTES, got ${rawFrameBytes.size}"
        }
    }

    private val bytes: ByteArray = rawFrameBytes.copyOf()

    val totalLength: Int
        get() = bytes.size

    val bodyLength: Int
        get() = bytes.size - HEADER_BYTES - CHECKSUM_BYTES

    val checksum: Long = readTrailingU32Le(bytes, bytes.size - CHECKSUM_BYTES)

    /**
     * Returns an independent defensive copy of the full validated envelope frame bytes.
     */
    fun copyBytes(): ByteArray = bytes.copyOf()

    /**
     * Returns an independent defensive copy of the body slice ([12 .. totalLength - 5]).
     */
    fun copyBodyBytes(): ByteArray = bytes.copyOfRange(HEADER_BYTES, bytes.size - CHECKSUM_BYTES)

    /**
     * Returns the unsigned byte value (`0..255`) at 0-based frame [offset].
     */
    fun frameByteAt(offset: Int): Int {
        if (offset !in bytes.indices) {
            throw IndexOutOfBoundsException("Frame offset $offset out of bounds for length ${bytes.size}")
        }
        return bytes[offset].toInt() and 0xFF
    }

    /**
     * Returns the unsigned byte value (`0..255`) at 0-based body [offset] (frame offset `12 + offset`).
     */
    fun bodyByteAt(offset: Int): Int {
        if (offset !in 0 until bodyLength) {
            throw IndexOutOfBoundsException("Body offset $offset out of bounds for bodyLength $bodyLength")
        }
        return bytes[HEADER_BYTES + offset].toInt() and 0xFF
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawHoslamFrame) return false
        return kind == other.kind &&
            frameStartOffset == other.frameStartOffset &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + frameStartOffset.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    override fun toString(): String =
        "RawHoslamFrame(kind=$kind, frameStartOffset=$frameStartOffset, totalLength=${bytes.size}, checksum=$checksum)"
}
