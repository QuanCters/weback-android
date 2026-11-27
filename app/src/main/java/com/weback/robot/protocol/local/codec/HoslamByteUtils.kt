package com.weback.robot.protocol.local.codec

/**
 * Pure-JVM unsigned binary decoding and modular checksum utilities for HOSLAM frames.
 */
object HoslamByteUtils {

    private const val U32_MASK: Long = 0xFFFF_FFFFL

    /**
     * Decodes a 32-bit little-endian unsigned integer from [bytes] starting at [offset]
     * strictly using `(b.toLong() and 0xFFL)` shifts to prevent signed-byte sign extension.
     */
    internal fun readU32Le(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset <= bytes.size - 4) {
            "Insufficient bytes for u32 LE at offset $offset (size=${bytes.size})"
        }
        return (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }

    /**
     * Computes the unsigned byte sum over `bytes[0 until endExclusive]` modulo `2^32`.
     */
    internal fun computeAdditiveChecksum(bytes: ByteArray, endExclusive: Int): Long {
        require(endExclusive in 0..bytes.size) {
            "endExclusive $endExclusive out of bounds for size ${bytes.size}"
        }
        var sum = 0L
        for (i in 0 until endExclusive) {
            sum += bytes[i].toLong() and 0xFFL
        }
        return sum and U32_MASK
    }
}
