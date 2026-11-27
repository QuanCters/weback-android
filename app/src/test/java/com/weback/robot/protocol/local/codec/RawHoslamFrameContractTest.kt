package com.weback.robot.protocol.local.codec

import com.weback.robot.domain.map.HoslamFrame
import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.LocalCodecError
import com.weback.robot.domain.map.RasterSnapshot
import com.weback.robot.domain.map.RawHoslamFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Defensive ownership, immutability, content equality, and redacted toString contracts
 * for [RawHoslamFrame] and [LocalCodecError].
 */
class RawHoslamFrameContractTest {

    @Test
    fun testConstructorDefensivelyCopiesInputArray() {
        val original = HoslamTestFixtures.buildValidFrame(
            HoslamFrameKind.MAP,
            byteArrayOf(0x11, 0x22, 0x80.toByte(), 0xFF.toByte())
        )
        val snapshot = original.copyOf()
        val frame = RawHoslamFrame(HoslamFrameKind.MAP, 128L, original)

        // Mutate constructor input array immediately
        original.fill(0x00)

        assertArrayEquals(snapshot, frame.copyBytes())
        assertEquals(0x11, frame.bodyByteAt(0))
        assertEquals(0x22, frame.bodyByteAt(1))
        assertEquals(0x80, frame.bodyByteAt(2))
        assertEquals(0xFF, frame.bodyByteAt(3))
        assertEquals(
            HoslamTestFixtures.independentSumMod2Pow32(snapshot, snapshot.size - 4),
            frame.checksum
        )
    }

    @Test
    fun testMutatingReturnedCopiesDoesNotAlterFrameState() {
        val bytes = HoslamTestFixtures.buildValidFrame(
            HoslamFrameKind.AUX,
            byteArrayOf(0x41, 0x42, 0x43, 0x44)
        )
        val frame = RawHoslamFrame(HoslamFrameKind.AUX, 0L, bytes)

        val firstCopy = frame.copyBytes()
        firstCopy.fill(0x7F)
        val bodyCopy = frame.copyBodyBytes()
        bodyCopy.fill(0x00)

        assertArrayEquals(bytes, frame.copyBytes())
        assertArrayEquals(byteArrayOf(0x41, 0x42, 0x43, 0x44), frame.copyBodyBytes())
        assertEquals(0x41, frame.bodyByteAt(0))
    }

    @Test
    fun testContentEqualityAndHashCode() {
        val bytesA = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.TRAJECTORY, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val bytesB = bytesA.copyOf()
        val bytesDifferentBody = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.TRAJECTORY, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 9))

        val frameA = RawHoslamFrame(HoslamFrameKind.TRAJECTORY, 64L, bytesA)
        val frameB = RawHoslamFrame(HoslamFrameKind.TRAJECTORY, 64L, bytesB)
        val frameDifferentOffset = RawHoslamFrame(HoslamFrameKind.TRAJECTORY, 128L, bytesB)
        val frameDifferentKind = RawHoslamFrame(HoslamFrameKind.MAP, 64L, bytesB)
        val frameDifferentContent = RawHoslamFrame(HoslamFrameKind.TRAJECTORY, 64L, bytesDifferentBody)

        assertEquals(frameA, frameB)
        assertEquals(frameA.hashCode(), frameB.hashCode())
        assertNotEquals(frameA, frameDifferentOffset)
        assertNotEquals(frameA, frameDifferentKind)
        assertNotEquals(frameA, frameDifferentContent)
    }

    @Test
    fun testToStringRedactsPayloadAndNeverLeaksBinaryContents() {
        val sentinelText = "SECRET_FLOORPLAN_XYZ"
        val sentinelBytes = sentinelText.encodeToByteArray() + byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val raw = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.MAP, sentinelBytes)
        val frame = RawHoslamFrame(HoslamFrameKind.MAP, 44L, raw)

        val rendered = frame.toString()
        assertFalse("toString leaked ASCII payload: $rendered", rendered.contains("SECRET_FLOORPLAN_XYZ"))
        assertFalse("toString leaked hex payload: $rendered", rendered.lowercase().contains("deadbeef"))
        assertFalse("toString leaked magic ascii: $rendered", rendered.contains("hoslmap"))
        assertTrue(rendered.contains("MAP"))
        assertTrue(rendered.contains("44"))
        assertTrue(rendered.contains(raw.size.toString()))
        assertTrue(rendered.length <= 200)

        val errors = listOf(
            LocalCodecError.InvalidMagic(44L),
            LocalCodecError.InvalidLength(44L, 12L),
            LocalCodecError.FrameTooLarge(44L, 0x80000010L),
            LocalCodecError.TruncatedFrame(44L, 19),
            LocalCodecError.InvalidChecksum(44L),
            LocalCodecError.UnsupportedLayout(44L, HoslamFrameKind.MAP, "MAP_LENGTH_LAYOUT_MISMATCH"),
            LocalCodecError.InvalidDimensions(44L, 0L, 1L),
            LocalCodecError.InvalidPadding(44L)
        )
        for (err in errors) {
            val errStr = err.toString()
            assertTrue("Error toString should contain offset 44: $errStr", errStr.contains("44"))
            assertFalse("Error toString leaked payload bytes: $errStr", errStr.contains("SECRET"))
        }

        val mapSnapshot = RasterSnapshot(2, 2, sentinelText.encodeToByteArray().copyOfRange(0, 4))
        val mapFrame = HoslamFrame.Map(44L, 44, 0L, mapSnapshot)
        assertFalse("HoslamFrame.Map.toString leaked pixel ASCII", mapFrame.toString().contains("SECR"))
        assertTrue(mapFrame.toString().contains(mapSnapshot.rasterSha256))
    }

    @Test
    fun testHoslamFrameInitRejectsInvalidLengthsAndSlotCountOverflows() {
        val validSnapshot = RasterSnapshot(1, 1, byteArrayOf(0x42))
        val invalidFrames = listOf(
            { HoslamFrame.Map(-1L, 44, 0L, validSnapshot) },
            { HoslamFrame.Map(0L, 40, 0L, validSnapshot) },
            { HoslamFrame.Aux(-1L, 20, 0L, 4) },
            { HoslamFrame.Aux(0L, 24, 0L, 4) },
            { HoslamFrame.Aux(0L, 20, 0L, 8) },
            { HoslamFrame.Trajectory(-1L, 16, 0L, 0) },
            { HoslamFrame.Trajectory(0L, 16, 0L, -1) },
            { HoslamFrame.Trajectory(0L, 16, 0L, 0x2000_0000) }, // Int32 * 8 overflow must still fail
            { HoslamFrame.Trajectory(0L, RawHoslamFrame.MAX_FRAME_BYTES + 8, 0L, 262_143) }
        )
        for (call in invalidFrames) {
            var threw = false
            try {
                call()
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("Expected IllegalArgumentException for invalid HoslamFrame construction", threw)
        }
    }

    @Test
    fun testOutOfBoundsByteAccessThrowsIndexOutOfBoundsException() {
        val raw = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.AUX, byteArrayOf(10, 20, 30, 40))
        val frame = RawHoslamFrame(HoslamFrameKind.AUX, 0L, raw)

        for (badIndex in listOf(-1, 4)) {
            var threw = false
            try {
                frame.bodyByteAt(badIndex)
            } catch (_: IndexOutOfBoundsException) {
                threw = true
            }
            assertTrue("Expected IndexOutOfBoundsException for body index $badIndex", threw)
        }

        for (badFrameIndex in listOf(-1, raw.size)) {
            var threw = false
            try {
                frame.frameByteAt(badFrameIndex)
            } catch (_: IndexOutOfBoundsException) {
                threw = true
            }
            assertTrue("Expected IndexOutOfBoundsException for frame index $badFrameIndex", threw)
        }
    }
}
