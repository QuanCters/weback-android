package com.weback.robot.protocol.local.codec

import com.weback.robot.domain.map.HoslamFrame
import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.LocalCodecError
import com.weback.robot.domain.map.RasterSnapshot
import com.weback.robot.domain.map.RawHoslamFrame

/**
 * Pure-JVM type-specific HOSLAM frame layout validator and raw grayscale raster decoder.
 *
 * **Caller Precondition:** Every input [RawHoslamFrame] MUST have passed envelope validation
 * (8-byte magic prefix, bounded u32 LE length in `16..2_097_152`, and trailing u32 LE additive
 * checksum) via [HoslamStreamFramer]. Calling the public [RawHoslamFrame] constructor directly
 * does NOT prove checksum validity; this decoder intentionally does not duplicate envelope checks.
 *
 * Decodes `hoslmap\0` payloads strictly as uncompressed raw unsigned 8-bit grayscale bytes
 * starting at frame offset `36` (without compression/decompression), and validates `hoslcac\0`
 * and `hosltraj` payloads structurally on octet length without float or coordinate parsing.
 */
class HoslamFrameDecoder {

    companion object {
        const val MAP_MIN_FRAME_BYTES: Int = 40
        const val MAP_METADATA_OFFSET: Int = 12
        const val MAP_METADATA_BYTES: Int = 16
        const val MAP_WIDTH_OFFSET: Int = 28
        const val MAP_HEIGHT_OFFSET: Int = 32
        const val MAP_RASTER_OFFSET: Int = 36

        const val MIN_DIMENSION: Long = 1L
        const val MAX_DIMENSION: Long = 1024L
        const val MAX_PIXEL_COUNT: Long = 1_048_576L

        const val AUX_REQUIRED_FRAME_BYTES: Int = 20
        const val TRAJ_SLOT_BYTES: Int = 8

        const val REASON_MAP_LENGTH_BELOW_40: String = "MAP_LENGTH_BELOW_40"
        const val REASON_MAP_LENGTH_LAYOUT_MISMATCH: String = "MAP_LENGTH_LAYOUT_MISMATCH"
        const val REASON_AUX_LENGTH_NOT_20: String = "AUX_LENGTH_NOT_20"
        const val REASON_TRAJ_LENGTH_NOT_PAIR_ALIGNED: String = "TRAJ_LENGTH_NOT_PAIR_ALIGNED"
    }

    /**
     * Validates the type-specific layout of an envelope-verified [rawFrame] and decodes it
     * into a typed [HoslamFrame] (with optional caller-assigned non-negative [frameGeneration]).
     */
    fun decode(rawFrame: RawHoslamFrame, frameGeneration: Long = 0L): HoslamDecodeResult {
        require(frameGeneration >= 0L) {
            "frameGeneration must be non-negative, got $frameGeneration"
        }
        return when (rawFrame.kind) {
            HoslamFrameKind.MAP -> decodeMap(rawFrame, frameGeneration)
            HoslamFrameKind.AUX -> decodeAux(rawFrame)
            HoslamFrameKind.TRAJECTORY -> decodeTrajectory(rawFrame)
        }
    }

    private fun decodeMap(rawFrame: RawHoslamFrame, frameGeneration: Long): HoslamDecodeResult {
        if (rawFrame.totalLength < MAP_MIN_FRAME_BYTES) {
            return HoslamDecodeResult.Failed(
                LocalCodecError.UnsupportedLayout(
                    frameStartOffset = rawFrame.frameStartOffset,
                    kind = HoslamFrameKind.MAP,
                    reasonCode = REASON_MAP_LENGTH_BELOW_40
                )
            )
        }

        val dimBytes = ByteArray(8) { idx -> rawFrame.frameByteAt(MAP_WIDTH_OFFSET + idx).toByte() }
        val width = HoslamByteUtils.readU32Le(dimBytes, 0)
        val height = HoslamByteUtils.readU32Le(dimBytes, 4)

        if (width !in MIN_DIMENSION..MAX_DIMENSION || height !in MIN_DIMENSION..MAX_DIMENSION) {
            return HoslamDecodeResult.Failed(
                LocalCodecError.InvalidDimensions(
                    frameStartOffset = rawFrame.frameStartOffset,
                    width = width,
                    height = height
                )
            )
        }

        val pixelCount = width * height
        if (pixelCount > MAX_PIXEL_COUNT) {
            return HoslamDecodeResult.Failed(
                LocalCodecError.InvalidDimensions(
                    frameStartOffset = rawFrame.frameStartOffset,
                    width = width,
                    height = height
                )
            )
        }

        val end = MAP_RASTER_OFFSET.toLong() + pixelCount
        val aligned = (end + 3L) and 3L.inv()
        if (rawFrame.totalLength.toLong() != aligned + RawHoslamFrame.CHECKSUM_BYTES.toLong()) {
            return HoslamDecodeResult.Failed(
                LocalCodecError.UnsupportedLayout(
                    frameStartOffset = rawFrame.frameStartOffset,
                    kind = HoslamFrameKind.MAP,
                    reasonCode = REASON_MAP_LENGTH_LAYOUT_MISMATCH
                )
            )
        }

        val endInt = end.toInt()
        val alignedInt = aligned.toInt()
        for (padOffset in endInt until alignedInt) {
            if (rawFrame.frameByteAt(padOffset) != 0) {
                return HoslamDecodeResult.Failed(
                    LocalCodecError.InvalidPadding(rawFrame.frameStartOffset)
                )
            }
        }

        val frameBytes = rawFrame.copyBytes()
        val opaqueMetadata = frameBytes.copyOfRange(MAP_METADATA_OFFSET, MAP_METADATA_OFFSET + MAP_METADATA_BYTES)
        val pixels = frameBytes.copyOfRange(MAP_RASTER_OFFSET, endInt)

        val snapshot = RasterSnapshot(
            width = width.toInt(),
            height = height.toInt(),
            pixels = pixels,
            opaqueMetadata = opaqueMetadata,
            frameGeneration = frameGeneration
        )
        return HoslamDecodeResult.Decoded(
            HoslamFrame.Map(
                frameStartOffset = rawFrame.frameStartOffset,
                totalLength = rawFrame.totalLength,
                checksum = rawFrame.checksum,
                snapshot = snapshot
            )
        )
    }

    private fun decodeAux(rawFrame: RawHoslamFrame): HoslamDecodeResult {
        if (rawFrame.totalLength != AUX_REQUIRED_FRAME_BYTES) {
            return HoslamDecodeResult.Failed(
                LocalCodecError.UnsupportedLayout(
                    frameStartOffset = rawFrame.frameStartOffset,
                    kind = HoslamFrameKind.AUX,
                    reasonCode = REASON_AUX_LENGTH_NOT_20
                )
            )
        }
        return HoslamDecodeResult.Decoded(
            HoslamFrame.Aux(
                frameStartOffset = rawFrame.frameStartOffset,
                totalLength = rawFrame.totalLength,
                checksum = rawFrame.checksum,
                opaquePayloadByteCount = HoslamFrame.Aux.AUX_OPAQUE_PAYLOAD_BYTES
            )
        )
    }

    private fun decodeTrajectory(rawFrame: RawHoslamFrame): HoslamDecodeResult {
        val payloadBytes = rawFrame.totalLength - RawHoslamFrame.MIN_FRAME_BYTES
        if (rawFrame.totalLength < RawHoslamFrame.MIN_FRAME_BYTES || payloadBytes % TRAJ_SLOT_BYTES != 0) {
            return HoslamDecodeResult.Failed(
                LocalCodecError.UnsupportedLayout(
                    frameStartOffset = rawFrame.frameStartOffset,
                    kind = HoslamFrameKind.TRAJECTORY,
                    reasonCode = REASON_TRAJ_LENGTH_NOT_PAIR_ALIGNED
                )
            )
        }
        return HoslamDecodeResult.Decoded(
            HoslamFrame.Trajectory(
                frameStartOffset = rawFrame.frameStartOffset,
                totalLength = rawFrame.totalLength,
                checksum = rawFrame.checksum,
                opaqueSlotCount = payloadBytes / TRAJ_SLOT_BYTES
            )
        )
    }
}
