package com.weback.robot.protocol.local.codec

import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.LocalCodecError
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
            LocalCodecError.InvalidChecksum(44L)
        )
        for (err in errors) {
            val errStr = err.toString()
            assertTrue("Error toString should contain offset 44: $errStr", errStr.contains("44"))
            assertFalse("Error toString leaked payload bytes: $errStr", errStr.contains("SECRET"))
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
    }
}
