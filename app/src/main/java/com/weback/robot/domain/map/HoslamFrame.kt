package com.weback.robot.domain.map

/**
 * Decoded, layout-validated HOSLAM frame domain models.
 *
 * Auxiliary (`hoslcac\0`) and trajectory (`hosltraj`) frames expose bounded structural
 * byte/slot counts only without float parsing or unverified semantic bindings.
 */
sealed interface HoslamFrame {
    val kind: HoslamFrameKind
    val frameStartOffset: Long
    val totalLength: Int
    val checksum: Long

    data class Map(
        override val frameStartOffset: Long,
        override val totalLength: Int,
        override val checksum: Long,
        val snapshot: RasterSnapshot
    ) : HoslamFrame {
        override val kind: HoslamFrameKind
            get() = HoslamFrameKind.MAP

        init {
            require(frameStartOffset >= 0L) {
                "frameStartOffset must be non-negative, got $frameStartOffset"
            }
            val end = 36L + snapshot.width.toLong() * snapshot.height.toLong()
            val aligned = (end + 3L) and 3L.inv()
            val expectedTotalLength = aligned + RawHoslamFrame.CHECKSUM_BYTES.toLong()
            require(totalLength.toLong() == expectedTotalLength) {
                "Map totalLength ($totalLength) must equal aligned+4 ($expectedTotalLength)"
            }
        }
    }

    data class Aux(
        override val frameStartOffset: Long,
        override val totalLength: Int,
        override val checksum: Long,
        val opaquePayloadByteCount: Int
    ) : HoslamFrame {
        override val kind: HoslamFrameKind
            get() = HoslamFrameKind.AUX

        init {
            require(frameStartOffset >= 0L) {
                "frameStartOffset must be non-negative, got $frameStartOffset"
            }
            require(totalLength == AUX_FRAME_TOTAL_BYTES) {
                "Aux frame totalLength must be $AUX_FRAME_TOTAL_BYTES, got $totalLength"
            }
            require(opaquePayloadByteCount == AUX_OPAQUE_PAYLOAD_BYTES) {
                "Aux opaquePayloadByteCount must be $AUX_OPAQUE_PAYLOAD_BYTES, got $opaquePayloadByteCount"
            }
        }

        companion object {
            const val AUX_FRAME_TOTAL_BYTES: Int = 20
            const val AUX_OPAQUE_PAYLOAD_BYTES: Int = 4
        }
    }

    data class Trajectory(
        override val frameStartOffset: Long,
        override val totalLength: Int,
        override val checksum: Long,
        val opaqueSlotCount: Int
    ) : HoslamFrame {
        override val kind: HoslamFrameKind
            get() = HoslamFrameKind.TRAJECTORY

        val opaquePayloadByteCount: Int
            get() = opaqueSlotCount * TRAJECTORY_SLOT_BYTES

        init {
            require(frameStartOffset >= 0L) {
                "frameStartOffset must be non-negative, got $frameStartOffset"
            }
            require(totalLength in RawHoslamFrame.MIN_FRAME_BYTES..RawHoslamFrame.MAX_FRAME_BYTES) {
                "Trajectory totalLength must be in ${RawHoslamFrame.MIN_FRAME_BYTES}..${RawHoslamFrame.MAX_FRAME_BYTES}, got $totalLength"
            }
            require(opaqueSlotCount >= 0) {
                "opaqueSlotCount must be non-negative, got $opaqueSlotCount"
            }
            val expectedTotalLength =
                RawHoslamFrame.MIN_FRAME_BYTES.toLong() + opaqueSlotCount.toLong() * TRAJECTORY_SLOT_BYTES.toLong()
            require(totalLength.toLong() == expectedTotalLength) {
                "Trajectory totalLength ($totalLength) incompatible with opaqueSlotCount ($opaqueSlotCount)"
            }
        }

        companion object {
            const val TRAJECTORY_SLOT_BYTES: Int = 8
        }
    }
}
