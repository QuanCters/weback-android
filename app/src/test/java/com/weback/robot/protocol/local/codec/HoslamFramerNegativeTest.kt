package com.weback.robot.protocol.local.codec

import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.LocalCodecError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST-LOCAL-505 (AC-502.2): Negative envelope inputs, unsigned high-bit lengths
 * (`0x80000010`, `0xFFFFFFFF`), invalid checksums, and deterministic error precedence.
 */
class HoslamFramerNegativeTest {

    @Test
    fun testSyntheticNegativeFixturesProduceExactErrorsAtOffset0() {
        val unknownMagicFramer = HoslamStreamFramer()
        assertEquals(
            HoslamFeedResult.Failed(emptyList(), LocalCodecError.InvalidMagic(0L)),
            unknownMagicFramer.feed(HoslamTestFixtures.loadResourceBytes("unknown_magic.bin"))
        )

        val badChecksumFramer = HoslamStreamFramer()
        assertEquals(
            HoslamFeedResult.Failed(emptyList(), LocalCodecError.InvalidChecksum(0L)),
            badChecksumFramer.feed(HoslamTestFixtures.loadResourceBytes("bad_checksum.bin"))
        )

        val oversizedFramer = HoslamStreamFramer()
        assertEquals(
            HoslamFeedResult.Failed(emptyList(), LocalCodecError.FrameTooLarge(0L, 2_097_153L)),
            oversizedFramer.feed(HoslamTestFixtures.loadResourceBytes("oversized_header.bin"))
        )
    }

    @Test
    fun testDeclaredLengthsBelow16EmitInvalidLengthWhile16Succeeds() {
        for (len in listOf(0L, 1L, 11L, 12L, 15L)) {
            val header = HoslamTestFixtures.buildHeaderOnly(HoslamTestFixtures.MAGIC_MAP, len)
            val framer = HoslamStreamFramer()
            assertEquals(
                "Expected InvalidLength for length $len",
                HoslamFeedResult.Failed(emptyList(), LocalCodecError.InvalidLength(0L, len)),
                framer.feed(header)
            )
        }

        // Exact lower boundary 16 bytes succeeds
        val minValid = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.TRAJECTORY, ByteArray(0))
        val minFramer = HoslamStreamFramer()
        val okRes = minFramer.feed(minValid)
        assertTrue(okRes is HoslamFeedResult.Frames)
        assertEquals(16, (okRes as HoslamFeedResult.Frames).frames.single().totalLength)
    }

    @Test
    fun testUnsignedHighBitAndAboveCapLengthsEmitStrictlyFrameTooLarge() {
        val oversizedCases = listOf(
            2_097_153L,          // MAX + 1
            0x80000000L,         // 2_147_483_648L (signed Int.MIN_VALUE if misused)
            0x80000010L,         // 2_147_483_664L (explicit spec oracle)
            0xFFFFFFFFL          // 4_294_967_295L (-1 in signed Int32)
        )
        for (declared in oversizedCases) {
            val header = HoslamTestFixtures.buildHeaderOnly(HoslamTestFixtures.MAGIC_MAP, declared)
            val framer = HoslamStreamFramer()
            val result = framer.feed(header)
            assertEquals(
                "High-bit/oversized length $declared must emit strictly FrameTooLarge (never InvalidLength)",
                HoslamFeedResult.Failed(emptyList(), LocalCodecError.FrameTooLarge(0L, declared)),
                result
            )
        }

        // Also test split 12-byte header with 0x80000010 after a 44-byte valid frame
        val validPrefix = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.MAP, ByteArray(28))
        val splitHighBitHeader = HoslamTestFixtures.buildHeaderOnly(HoslamTestFixtures.MAGIC_AUX, 0x80000010L)
        val splitFramer = HoslamStreamFramer()
        val res1 = splitFramer.feed(validPrefix + splitHighBitHeader.copyOfRange(0, 5))
        assertTrue(res1 is HoslamFeedResult.Frames)
        assertEquals(1, (res1 as HoslamFeedResult.Frames).frames.size)

        val res2 = splitFramer.feed(splitHighBitHeader.copyOfRange(5, 12))
        assertEquals(
            HoslamFeedResult.Failed(emptyList(), LocalCodecError.FrameTooLarge(44L, 0x80000010L)),
            res2
        )
    }

    @Test
    fun testInvalidChecksumFromBodyMutationOrChecksumWordMutation() {
        val validFrame = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.AUX, byteArrayOf(10, 20, 30, 40))

        val bodyMutated = validFrame.copyOf()
        bodyMutated[13] = (bodyMutated[13].toInt() xor 0x01).toByte()
        assertEquals(
            HoslamFeedResult.Failed(emptyList(), LocalCodecError.InvalidChecksum(0L)),
            HoslamStreamFramer().feed(bodyMutated)
        )

        val checksumMutated = validFrame.copyOf()
        checksumMutated[checksumMutated.size - 1] = 0x80.toByte() // High bit in stored checksum word
        assertEquals(
            HoslamFeedResult.Failed(emptyList(), LocalCodecError.InvalidChecksum(0L)),
            HoslamStreamFramer().feed(checksumMutated)
        )

        // Second frame at offset 20 has invalid checksum -> reports frameStartOffset = 20L
        val combined = validFrame + bodyMutated
        val res = HoslamStreamFramer().feed(combined) as HoslamFeedResult.Failed
        assertEquals(1, res.validFramesBeforeFailure.size)
        assertEquals(0L, res.validFramesBeforeFailure[0].frameStartOffset)
        assertEquals(LocalCodecError.InvalidChecksum(20L), res.error)
    }

    @Test
    fun testDeterministicErrorPrecedenceWithMultipleSimultaneousDefects() {
        val badMagic = byteArrayOf(0x62, 0x61, 0x64, 0x6D, 0x61, 0x67, 0x69, 0x63)

        // 1. Bad magic + length < 16 + bad checksum -> InvalidMagic wins
        val badMagicSmallLen = ByteArray(16)
        System.arraycopy(badMagic, 0, badMagicSmallLen, 0, 8)
        HoslamTestFixtures.writeU32Le(badMagicSmallLen, 8, 4L)
        HoslamTestFixtures.writeU32Le(badMagicSmallLen, 12, 0xDEADBEEFL)
        assertEquals(
            HoslamFeedResult.Failed(emptyList(), LocalCodecError.InvalidMagic(0L)),
            HoslamStreamFramer().feed(badMagicSmallLen)
        )

        // 2. Bad magic + length 0xFFFFFFFF -> InvalidMagic wins
        val badMagicMaxU32 = HoslamTestFixtures.buildHeaderOnly(badMagic, 0xFFFFFFFFL)
        assertEquals(
            HoslamFeedResult.Failed(emptyList(), LocalCodecError.InvalidMagic(0L)),
            HoslamStreamFramer().feed(badMagicMaxU32)
        )

        // 3. Valid magic + length 12 + bad checksum -> InvalidLength wins before body/checksum
        val validMagicTooShort = ByteArray(16)
        System.arraycopy(HoslamTestFixtures.MAGIC_MAP, 0, validMagicTooShort, 0, 8)
        HoslamTestFixtures.writeU32Le(validMagicTooShort, 8, 12L)
        HoslamTestFixtures.writeU32Le(validMagicTooShort, 12, 0x12345678L)
        assertEquals(
            HoslamFeedResult.Failed(emptyList(), LocalCodecError.InvalidLength(0L, 12L)),
            HoslamStreamFramer().feed(validMagicTooShort)
        )

        // 4. Valid magic + length 2_097_153 + bad checksum bytes -> FrameTooLarge wins before body/checksum
        val validMagicTooLarge = ByteArray(20)
        System.arraycopy(HoslamTestFixtures.MAGIC_TRAJ, 0, validMagicTooLarge, 0, 8)
        HoslamTestFixtures.writeU32Le(validMagicTooLarge, 8, 2_097_153L)
        assertEquals(
            HoslamFeedResult.Failed(emptyList(), LocalCodecError.FrameTooLarge(0L, 2_097_153L)),
            HoslamStreamFramer().feed(validMagicTooLarge)
        )

        // 5. Valid header (length 44) + truncated body (43 bytes) with bad partial checksum bytes -> TruncatedFrame wins
        val badTruncated = HoslamTestFixtures.loadResourceBytes("bad_checksum.bin").copyOfRange(0, 43)
        val truncFramer = HoslamStreamFramer()
        assertEquals(HoslamFeedResult.Frames(emptyList()), truncFramer.feed(badTruncated))
        assertEquals(
            HoslamFinishResult.Failed(LocalCodecError.TruncatedFrame(0L, 43)),
            truncFramer.finish()
        )
    }
}
