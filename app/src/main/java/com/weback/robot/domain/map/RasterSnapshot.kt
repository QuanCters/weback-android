package com.weback.robot.domain.map

import java.security.MessageDigest

/**
 * Immutable, defensively owned raw unsigned 8-bit grayscale HOSLAM raster snapshot.
 *
 * Pixels are stored in row-major order using top-left display origin (`y * width + x`)
 * without flipping, quantization, or physical metric/coordinate interpretation.
 * Metadata bytes (`12..27` from the `hoslmap\0` frame) remain strictly opaque.
 */
class RasterSnapshot(
    val width: Int,
    val height: Int,
    pixels: ByteArray,
    opaqueMetadata: ByteArray = ByteArray(OPAQUE_METADATA_BYTES),
    val frameGeneration: Long = 0L
) {
    companion object {
        const val MIN_DIMENSION: Int = 1
        const val MAX_DIMENSION: Int = 1024
        const val MAX_PIXEL_COUNT: Int = 1_048_576
        const val OPAQUE_METADATA_BYTES: Int = 16

        private val HEX_CHARS = "0123456789abcdef".toCharArray()

        private fun computeSha256Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(data)
            val chars = CharArray(digest.size * 2)
            for (i in digest.indices) {
                val b = digest[i].toInt() and 0xFF
                chars[i * 2] = HEX_CHARS[b ushr 4]
                chars[i * 2 + 1] = HEX_CHARS[b and 0x0F]
            }
            return String(chars)
        }
    }

    init {
        require(width in MIN_DIMENSION..MAX_DIMENSION) {
            "width must be in $MIN_DIMENSION..$MAX_DIMENSION, got $width"
        }
        require(height in MIN_DIMENSION..MAX_DIMENSION) {
            "height must be in $MIN_DIMENSION..$MAX_DIMENSION, got $height"
        }
        val expectedPixelCount = width.toLong() * height.toLong()
        require(expectedPixelCount <= MAX_PIXEL_COUNT.toLong()) {
            "width * height ($expectedPixelCount) exceeds $MAX_PIXEL_COUNT"
        }
        require(pixels.size.toLong() == expectedPixelCount) {
            "pixels length (${pixels.size}) must equal width * height ($expectedPixelCount)"
        }
        require(opaqueMetadata.size == OPAQUE_METADATA_BYTES) {
            "opaqueMetadata length must be $OPAQUE_METADATA_BYTES, got ${opaqueMetadata.size}"
        }
        require(frameGeneration >= 0L) {
            "frameGeneration must be non-negative, got $frameGeneration"
        }
    }

    private val pixels: ByteArray = pixels.copyOf()
    private val opaqueMetadata: ByteArray = opaqueMetadata.copyOf()

    val pixelCount: Int
        get() = pixels.size

    val rasterSha256: String = computeSha256Hex(this.pixels)

    /**
     * Returns an independent defensive copy of the row-major unsigned 8-bit grayscale raster.
     */
    fun copyPixels(): ByteArray = pixels.copyOf()

    /**
     * Returns an independent defensive copy of the 16 opaque metadata bytes (`hoslmap\0` offsets `12..27`).
     */
    fun copyOpaqueMetadata(): ByteArray = opaqueMetadata.copyOf()

    /**
     * Returns the unsigned byte (`0..255`) at 0-based opaque metadata [offset] (`0..15`).
     */
    fun opaqueMetadataByteAt(offset: Int): Int {
        if (offset !in opaqueMetadata.indices) {
            throw IndexOutOfBoundsException("Metadata offset $offset out of bounds for length ${opaqueMetadata.size}")
        }
        return opaqueMetadata[offset].toInt() and 0xFF
    }

    /**
     * Returns the unsigned 8-bit grayscale intensity (`0..255`) at column [x] and row [y]
     * in top-left display coordinates.
     */
    fun pixelAt(x: Int, y: Int): Int {
        if (x !in 0 until width) {
            throw IndexOutOfBoundsException("Pixel x=$x out of bounds for width $width")
        }
        if (y !in 0 until height) {
            throw IndexOutOfBoundsException("Pixel y=$y out of bounds for height $height")
        }
        return pixels[y * width + x].toInt() and 0xFF
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RasterSnapshot) return false
        return width == other.width &&
            height == other.height &&
            frameGeneration == other.frameGeneration &&
            opaqueMetadata.contentEquals(other.opaqueMetadata) &&
            pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + frameGeneration.hashCode()
        result = 31 * result + opaqueMetadata.contentHashCode()
        result = 31 * result + pixels.contentHashCode()
        return result
    }

    override fun toString(): String =
        "RasterSnapshot(width=$width, height=$height, frameGeneration=$frameGeneration, rasterSha256=$rasterSha256)"
}
