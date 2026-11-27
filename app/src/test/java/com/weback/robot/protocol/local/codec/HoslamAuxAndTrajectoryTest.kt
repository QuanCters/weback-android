package com.weback.robot.protocol.local.codec

import com.weback.robot.domain.map.HoslamFrame
import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.LocalCodecError
import com.weback.robot.domain.map.RasterSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TEST-LOCAL-508 (AC-504.1, AC-504.2): Auxiliary and Trajectory structural validation,
 * defensive 16-byte empty trajectory contract, arbitrary non-finite float bit-pattern safety,
 * and structural prohibition of float/pose/heading/calibration/battery semantics.
 */
class HoslamAuxAndTrajectoryTest {

    private val decoder = HoslamFrameDecoder()

    @Test
    fun testValid20ByteAuxFramesSucceedWithOpaqueByteCount4() {
        val validMixed = HoslamTestFixtures.loadResourceBytes("valid_mixed.bin")
        val (frames, _) = HoslamTestFixtures.decodeStreamFrames(validMixed)
        val auxRaw = frames[1]
        assertEquals(HoslamFrameKind.AUX, auxRaw.kind)
        assertEquals(44L, auxRaw.frameStartOffset)
        assertEquals(20, auxRaw.totalLength)

        val decoded = decoder.decode(auxRaw)
        assertEquals(
            HoslamDecodeResult.Decoded(
                HoslamFrame.Aux(
                    frameStartOffset = 44L,
                    totalLength = 20,
                    checksum = 943L,
                    opaquePayloadByteCount = 4
                )
            ),
            decoded
        )

        // Arbitrary opaque 4-byte patterns (including NaN/Inf bit patterns if interpreted as float32)
        val bitPatterns = listOf(
            byteArrayOf(0x00, 0x00, 0x00, 0x00),
            byteArrayOf(0x00, 0x00, 0xC0.toByte(), 0x7F), // 0x7FC00000 LE
            byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x7F), // 0x7F800000 LE
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        )
        for (body in bitPatterns) {
            val raw = HoslamTestFixtures.rawDecodeViaFramer(
                HoslamTestFixtures.buildValidFrame(HoslamFrameKind.AUX, body)
            )
            val res = decoder.decode(raw)
            assertTrue("Expected valid AUX decode, got $res", res is HoslamDecodeResult.Decoded)
            val aux = (res as HoslamDecodeResult.Decoded).frame as HoslamFrame.Aux
            assertEquals(4, aux.opaquePayloadByteCount)
            assertEquals(20, aux.totalLength)
        }
    }

    @Test
    fun testInvalidAuxLengthsFailWithUnsupportedLayout() {
        for (badBodyLen in listOf(0, 1, 3, 5, 8, 24)) {
            val totalLen = 16 + badBodyLen
            val raw = HoslamTestFixtures.rawDecodeViaFramer(
                HoslamTestFixtures.buildValidFrame(HoslamFrameKind.AUX, ByteArray(badBodyLen) { 0x22 })
            )
            assertEquals(totalLen, raw.totalLength)
            assertEquals(
                "AUX length $totalLen must fail with AUX_LENGTH_NOT_20",
                HoslamDecodeResult.Failed(
                    LocalCodecError.UnsupportedLayout(0L, HoslamFrameKind.AUX, "AUX_LENGTH_NOT_20")
                ),
                decoder.decode(raw)
            )
        }
    }

    @Test
    fun testDefensiveEmpty16ByteAndValidNonEmptyTrajectoryFramesEmitExactOpaqueSlotCount() {
        // Explicit defensive codec contract: 16-byte TRAJECTORY frame (N = 0 opaque 8-byte slots).
        // This is an explicit defensive codec constraint rather than proof of vendor firmware support.
        val emptyTrajRaw = HoslamTestFixtures.rawDecodeViaFramer(
            HoslamTestFixtures.buildValidFrame(HoslamFrameKind.TRAJECTORY, ByteArray(0))
        )
        assertEquals(16, emptyTrajRaw.totalLength)
        val emptyRes = decoder.decode(emptyTrajRaw)
        assertTrue("Defensive empty 16-byte TRAJECTORY must decode cleanly, got $emptyRes", emptyRes is HoslamDecodeResult.Decoded)
        val emptyTraj = (emptyRes as HoslamDecodeResult.Decoded).frame as HoslamFrame.Trajectory
        assertEquals(0, emptyTraj.opaqueSlotCount)
        assertEquals(0, emptyTraj.opaquePayloadByteCount)

        // valid_mixed.bin trajectory frame (offset 64L, length 24 -> 1 opaque slot)
        val (validMixedFrames, _) = HoslamTestFixtures.decodeStreamFrames(
            HoslamTestFixtures.loadResourceBytes("valid_mixed.bin")
        )
        val trajMixedRes = decoder.decode(validMixedFrames[2])
        assertTrue("Expected Decoded for valid_mixed trajectory, got $trajMixedRes", trajMixedRes is HoslamDecodeResult.Decoded)
        val trajMixed = trajMixedRes as HoslamDecodeResult.Decoded
        assertEquals(
            HoslamFrame.Trajectory(
                frameStartOffset = 64L,
                totalLength = 24,
                checksum = 1342L,
                opaqueSlotCount = 1
            ),
            trajMixed.frame
        )

        // Multi-slot trajectory frames (1, 2, 144 slots, and max 2 MiB frame 262_142 slots)
        for (slots in listOf(1, 2, 144, 262_142)) {
            val body = ByteArray(slots * 8) { idx -> (idx and 0xFF).toByte() }
            val raw = HoslamTestFixtures.rawDecodeViaFramer(
                HoslamTestFixtures.buildValidFrame(HoslamFrameKind.TRAJECTORY, body)
            )
            val res = decoder.decode(raw)
            assertTrue("Expected Decoded for $slots trajectory slots, got $res", res is HoslamDecodeResult.Decoded)
            val traj = (res as HoslamDecodeResult.Decoded).frame as HoslamFrame.Trajectory
            assertEquals(slots, traj.opaqueSlotCount)
            assertEquals(slots * 8, traj.opaquePayloadByteCount)
            assertEquals(16 + slots * 8, traj.totalLength)
        }
    }

    @Test
    fun testArbitraryOpaqueBitPatternsIncludingIeee754NaNAndInfinityDecodeWithoutInterpretationOrCrash() {
        // Construct 4 x 8-byte slots packed with quiet NaN, signaling NaN, +Inf, -Inf, -0.0, subnormals
        val nonFiniteSlotBytes = byteArrayOf(
            // Slot 0: 0x7FC00000 (qNaN), 0x7F800000 (+Inf)
            0x00, 0x00, 0xC0.toByte(), 0x7F, 0x00, 0x00, 0x80.toByte(), 0x7F,
            // Slot 1: 0xFFC00000 (-qNaN), 0xFF800000 (-Inf)
            0x00, 0x00, 0xC0.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x80.toByte(), 0xFF.toByte(),
            // Slot 2: 0x7F800001 (sNaN), 0x80000000 (-0.0)
            0x01, 0x00, 0x80.toByte(), 0x7F, 0x00, 0x00, 0x00, 0x80.toByte(),
            // Slot 3: 0xFFFFFFFF, 0x00000001
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x01, 0x00, 0x00, 0x00
        )
        val raw = HoslamTestFixtures.rawDecodeViaFramer(
            HoslamTestFixtures.buildValidFrame(HoslamFrameKind.TRAJECTORY, nonFiniteSlotBytes)
        )
        val res = decoder.decode(raw)
        assertTrue("Expected Decoded for arbitrary bit-pattern trajectory, got $res", res is HoslamDecodeResult.Decoded)
        val traj = (res as HoslamDecodeResult.Decoded).frame as HoslamFrame.Trajectory
        assertEquals(4, traj.opaqueSlotCount)
        assertEquals(32, traj.opaquePayloadByteCount)
        assertEquals(48, traj.totalLength)
    }

    @Test
    fun testMisalignedTrajectoryLengthsFailWithUnsupportedLayout() {
        for (badBodyLen in listOf(1, 2, 3, 4, 5, 6, 7, 9, 15)) {
            val totalLen = 16 + badBodyLen
            val raw = HoslamTestFixtures.rawDecodeViaFramer(
                HoslamTestFixtures.buildValidFrame(HoslamFrameKind.TRAJECTORY, ByteArray(badBodyLen) { 0x7F })
            )
            assertEquals(
                "Misaligned TRAJ length $totalLen must fail TRAJ_LENGTH_NOT_PAIR_ALIGNED",
                HoslamDecodeResult.Failed(
                    LocalCodecError.UnsupportedLayout(0L, HoslamFrameKind.TRAJECTORY, "TRAJ_LENGTH_NOT_PAIR_ALIGNED")
                ),
                decoder.decode(raw)
            )
        }
    }

    @Test
    fun testNoFloatCoordinateParsingOrUnverifiedPoseHeadingBatteryCalibrationModelsExist() {
        val domainClasses = listOf(
            HoslamFrame.Aux::class.java,
            HoslamFrame.Trajectory::class.java,
            HoslamFrame.Map::class.java,
            RasterSnapshot::class.java
        )
        val forbiddenFieldTokens = listOf(
            "pose",
            "heading",
            "angle",
            "yaw",
            "battery",
            "calibration",
            "coordinate",
            "coord",
            "float",
            "double",
            "xMeters",
            "yMeters",
            "freshness",
            "timestamp"
        )
        for (clazz in domainClasses) {
            val fieldNames = clazz.declaredFields.map { it.name }
            val methodNames = clazz.declaredMethods.map { it.name }
            for (field in clazz.declaredFields) {
                assertFalse(
                    "Prohibited float/double field type in ${clazz.simpleName}.${field.name}",
                    field.type == java.lang.Float.TYPE ||
                        field.type == java.lang.Double.TYPE ||
                        field.type == FloatArray::class.java ||
                        field.type == DoubleArray::class.java
                )
            }
            for (name in fieldNames + methodNames) {
                val lower = name.lowercase()
                for (tok in forbiddenFieldTokens) {
                    assertFalse(
                        "Prohibited semantic/float symbol '$name' in ${clazz.simpleName}",
                        lower.contains(tok.lowercase())
                    )
                }
            }
        }

        // Source scan across domain/map and protocol/local/codec ensuring zero float decoding calls
        val sourceDirs = listOf(
            listOf(File("src/main/java/com/weback/robot/domain/map"), File("app/src/main/java/com/weback/robot/domain/map")).first { it.isDirectory },
            listOf(File("src/main/java/com/weback/robot/protocol/local/codec"), File("app/src/main/java/com/weback/robot/protocol/local/codec")).first { it.isDirectory }
        )
        val prohibitedCodeTokens = listOf(
            "Float.fromBits",
            "Double.fromBits",
            "java.lang.Float",
            "java.lang.Double",
            "intBitsToFloat",
            "longBitsToDouble",
            "getFloat",
            "getDouble",
            "ByteBuffer"
        )
        val violations = HoslamTestFixtures.findProhibitedCodeTokens(sourceDirs, prohibitedCodeTokens)
        assertTrue("Prohibited float/ByteBuffer references in codec/domain: $violations", violations.isEmpty())
    }
}
