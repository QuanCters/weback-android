package com.weback.robot.protocol.local.codec

import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.LocalCodecError
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST-LOCAL-502 (AC-502.1): Magic prefixes, unsigned u32 LE decoding,
 * modular unsigned byte-sum checksum, frame-start offsets, and L02A vs L02B boundary.
 */
class HoslamFramerMagicChecksumTest {

    @Test
    fun testReadU32LeUsesUnsignedMaskingAcrossAllFourByteLanes() {
        assertEquals(0L, HoslamByteUtils.readU32Le(byteArrayOf(0x00, 0x00, 0x00, 0x00), 0))
        assertEquals(16L, HoslamByteUtils.readU32Le(byteArrayOf(0x10, 0x00, 0x00, 0x00), 0))
        assertEquals(2_097_152L, HoslamByteUtils.readU32Le(byteArrayOf(0x00, 0x00, 0x20, 0x00), 0))
        assertEquals(2_097_153L, HoslamByteUtils.readU32Le(byteArrayOf(0x01, 0x00, 0x20, 0x00), 0))
        assertEquals(0x80000010L, HoslamByteUtils.readU32Le(byteArrayOf(0x10, 0x00, 0x00, 0x80.toByte()), 0))
        assertEquals(0xFFFFFFFFL, HoslamByteUtils.readU32Le(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), 0))
        assertEquals(
            0x83828180L,
            HoslamByteUtils.readU32Le(
                byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x81.toByte(), 0x82.toByte(), 0x83.toByte()),
                2
            )
        )
    }

    @Test
    fun testSupportsAllThreeLiteralMagicHeadersWithExactChecksumAndOffsets() {
        // Minimal 16-byte MAP frame: "hoslmap\0" sum = 104+111+115+108+109+97+112+0 = 756, len = 16 -> total checksum = 772 (0x0304)
        val literalMap16 = byteArrayOf(
            0x68, 0x6F, 0x73, 0x6C, 0x6D, 0x61, 0x70, 0x00,
            0x10, 0x00, 0x00, 0x00,
            0x04, 0x03, 0x00, 0x00
        )
        // 20-byte AUX frame: "hoslcac\0" sum = 740, len = 20, body = [0x00, 0x00, 0x80, 0x3e] (0+0+128+62=190) -> checksum = 950? Wait: 740+20+190 = 950 (0x03B6); wait: fixture valid_mixed aux has body sum 190 -> 733? Let's use literal 740+20+190 = 950
        // Let's verify: 'h'(104)+'o'(111)+'s'(115)+'l'(108)+'c'(99)+'a'(97)+'c'(99)+0 = 733! 733 + 20 + 190 = 943 (0x03AF)!
        val literalAux20 = byteArrayOf(
            0x68, 0x6F, 0x73, 0x6C, 0x63, 0x61, 0x63, 0x00,
            0x14, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x80.toByte(), 0x3E,
            0xAF.toByte(), 0x03, 0x00, 0x00
        )
        // 24-byte TRAJECTORY frame: "hosltraj" sum = 104+111+115+108+116+114+97+106 = 871, len = 24, body = [0,0,0xC0,0x3F, 0,0,0,0xC0] (192+63+192 = 447) -> sum = 1342 (0x053E)
        val literalTraj24 = byteArrayOf(
            0x68, 0x6F, 0x73, 0x6C, 0x74, 0x72, 0x61, 0x6A,
            0x18, 0x00, 0x00, 0x00,
            0x00, 0x00, 0xC0.toByte(), 0x3F,
            0x00, 0x00, 0x00, 0xC0.toByte(),
            0x3E, 0x05, 0x00, 0x00
        )

        val stream = literalMap16 + literalAux20 + literalTraj24
        val framer = HoslamStreamFramer()
        val feedResult = framer.feed(stream)
        assertTrue("Expected Frames result, got $feedResult", feedResult is HoslamFeedResult.Frames)
        val frames = (feedResult as HoslamFeedResult.Frames).frames
        assertEquals(3, frames.size)

        assertEquals(HoslamFrameKind.MAP, frames[0].kind)
        assertEquals(0L, frames[0].frameStartOffset)
        assertEquals(16, frames[0].totalLength)
        assertEquals(772L, frames[0].checksum)
        assertArrayEquals(literalMap16, frames[0].copyBytes())

        assertEquals(HoslamFrameKind.AUX, frames[1].kind)
        assertEquals(16L, frames[1].frameStartOffset)
        assertEquals(20, frames[1].totalLength)
        assertEquals(943L, frames[1].checksum)
        assertArrayEquals(literalAux20, frames[1].copyBytes())

        assertEquals(HoslamFrameKind.TRAJECTORY, frames[2].kind)
        assertEquals(36L, frames[2].frameStartOffset)
        assertEquals(24, frames[2].totalLength)
        assertEquals(1342L, frames[2].checksum)
        assertArrayEquals(literalTraj24, frames[2].copyBytes())

        assertEquals(HoslamFinishResult.EndOfStream(framesDelivered = 3L, bytesConsumed = 60L), framer.finish())
    }

    @Test
    fun testHighBitUnsignedByteValuesInBodyAndLengthContributePositivelyToChecksum() {
        // Length 260 (0x04 0x01 0x00 0x00) and body filled with 0xFF/0x80/0xFE/0x81 bytes
        val body = ByteArray(244) { idx ->
            when (idx % 4) {
                0 -> 0xFF.toByte()
                1 -> 0x80.toByte()
                2 -> 0xFE.toByte()
                else -> 0x81.toByte()
            }
        }
        // Magic "hoslmap\0" sum = 756; length bytes [0x04, 0x01, 0x00, 0x00] sum = 5;
        // 61 * (255 + 128 + 254 + 129) = 61 * 766 = 46_726; total = 756 + 5 + 46_726 = 47_487
        val frameBytes = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.MAP, body)
        assertEquals(260, frameBytes.size)
        assertEquals(47_487L, HoslamTestFixtures.independentSumMod2Pow32(frameBytes, 256))

        val framer = HoslamStreamFramer()
        val feedResult = framer.feed(frameBytes)
        assertTrue(feedResult is HoslamFeedResult.Frames)
        val frame = (feedResult as HoslamFeedResult.Frames).frames.single()
        assertEquals(47_487L, frame.checksum)
        assertEquals(255, frame.bodyByteAt(0))
        assertEquals(128, frame.bodyByteAt(1))
        assertEquals(254, frame.bodyByteAt(2))
        assertEquals(129, frame.bodyByteAt(3))
        assertEquals(HoslamFinishResult.EndOfStream(1L, 260L), framer.finish())
    }

    @Test
    fun testExactMagicMatchingRejectsSingleByteOrCaseModifications() {
        val variants = listOf(
            byteArrayOf(0x48, 0x6F, 0x73, 0x6C, 0x6D, 0x61, 0x70, 0x00), // "Hoslmap\0"
            byteArrayOf(0x68, 0x6F, 0x73, 0x6C, 0x6D, 0x61, 0x70, 0x01), // "hoslmap\1"
            byteArrayOf(0x68, 0x6F, 0x73, 0x6C, 0x63, 0x61, 0x63, 0x20), // "hoslcac "
            byteArrayOf(0x68, 0x6F, 0x73, 0x6C, 0x74, 0x72, 0x61, 0x00)  // "hosltra\0"
        )
        for (badMagic in variants) {
            val frame = ByteArray(16)
            System.arraycopy(badMagic, 0, frame, 0, 8)
            HoslamTestFixtures.writeU32Le(frame, 8, 16L)
            HoslamTestFixtures.writeU32Le(frame, 12, HoslamTestFixtures.independentSumMod2Pow32(frame, 12))

            val framer = HoslamStreamFramer()
            val res = framer.feed(frame)
            assertEquals(
                HoslamFeedResult.Failed(emptyList(), LocalCodecError.InvalidMagic(0L)),
                res
            )
        }
    }

    @Test
    fun testAcceptsMaximumEnvelopeSize2MiBWithValidChecksum() {
        val maxTotalBytes = 2_097_152
        val maxFrame = ByteArray(maxTotalBytes)
        System.arraycopy(HoslamTestFixtures.MAGIC_TRAJ, 0, maxFrame, 0, 8)
        HoslamTestFixtures.writeU32Le(maxFrame, 8, maxTotalBytes.toLong())
        // Fill body deterministically with non-zero high-bit byte 0xA5 (165)
        for (i in 12 until maxTotalBytes - 4) {
            maxFrame[i] = 0xA5.toByte()
        }
        val expectedChecksum = 871L + (0x00L + 0x00L + 0x20L + 0x00L) + (maxTotalBytes - 16).toLong() * 165L
        HoslamTestFixtures.writeU32Le(maxFrame, maxTotalBytes - 4, expectedChecksum)

        val framer = HoslamStreamFramer()
        val feedResult = framer.feed(maxFrame)
        assertTrue("Expected max 2 MiB frame to pass L02A framing, got $feedResult", feedResult is HoslamFeedResult.Frames)
        val frame = (feedResult as HoslamFeedResult.Frames).frames.single()
        assertEquals(HoslamFrameKind.TRAJECTORY, frame.kind)
        assertEquals(0L, frame.frameStartOffset)
        assertEquals(maxTotalBytes, frame.totalLength)
        assertEquals(expectedChecksum, frame.checksum)
        assertEquals(HoslamFinishResult.EndOfStream(1L, maxTotalBytes.toLong()), framer.finish())
    }

    @Test
    fun testFramingAcceptsValidEnvelopeFixturesBadPaddingAndZeroWidthForL02BRejection() {
        val badPaddingBytes = HoslamTestFixtures.loadResourceBytes("bad_padding.bin")
        assertEquals(44, badPaddingBytes.size)
        val badPaddingFramer = HoslamStreamFramer()
        val badPaddingResult = badPaddingFramer.feed(badPaddingBytes)
        assertTrue(
            "bad_padding.bin has valid envelope/checksum; L02A must emit RawHoslamFrame and defer padding check to L02B",
            badPaddingResult is HoslamFeedResult.Frames
        )
        val badPaddingFrame = (badPaddingResult as HoslamFeedResult.Frames).frames.single()
        assertEquals(HoslamFrameKind.MAP, badPaddingFrame.kind)
        assertEquals(0L, badPaddingFrame.frameStartOffset)
        assertEquals(44, badPaddingFrame.totalLength)
        assertEquals(1298L, badPaddingFrame.checksum)
        assertEquals(HoslamFinishResult.EndOfStream(1L, 44L), badPaddingFramer.finish())

        val zeroWidthBytes = HoslamTestFixtures.loadResourceBytes("zero_width.bin")
        assertEquals(40, zeroWidthBytes.size)
        val zeroWidthFramer = HoslamStreamFramer()
        val zeroWidthResult = zeroWidthFramer.feed(zeroWidthBytes)
        assertTrue(
            "zero_width.bin has valid envelope/checksum; L02A must emit RawHoslamFrame and defer dimension check to L02B",
            zeroWidthResult is HoslamFeedResult.Frames
        )
        val zeroWidthFrame = (zeroWidthResult as HoslamFeedResult.Frames).frames.single()
        assertEquals(HoslamFrameKind.MAP, zeroWidthFrame.kind)
        assertEquals(0L, zeroWidthFrame.frameStartOffset)
        assertEquals(40, zeroWidthFrame.totalLength)
        assertEquals(907L, zeroWidthFrame.checksum)
        assertEquals(HoslamFinishResult.EndOfStream(1L, 40L), zeroWidthFramer.finish())
    }
}
