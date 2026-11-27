package com.weback.robot.protocol.local.codec

import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.LocalCodecError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST-LOCAL-504 (AC-502.2): Clean boundary EOF vs truncated EOF across every byte position,
 * terminal failure isolation, post-error suppression, and session reset.
 */
class HoslamFramerEofTest {

    @Test
    fun testEmptyStreamFollowedByFinishIsCleanEndOfStream() {
        val framer = HoslamStreamFramer()
        assertEquals(HoslamFinishResult.EndOfStream(0L, 0L), framer.finish())

        // Feeding an explicit empty chunk is also clean 0-frame EndOfStream
        val framerAfterEmptyChunk = HoslamStreamFramer()
        assertEquals(HoslamFeedResult.Frames(emptyList()), framerAfterEmptyChunk.feed(ByteArray(0)))
        assertEquals(HoslamFinishResult.EndOfStream(0L, 0L), framerAfterEmptyChunk.finish())
    }

    @Test
    fun testCleanBoundaryEndOfStreamIsIdempotent() {
        val validMixed = HoslamTestFixtures.loadResourceBytes("valid_mixed.bin")
        val framer = HoslamStreamFramer()
        val feedResult = framer.feed(validMixed)
        assertTrue(feedResult is HoslamFeedResult.Frames)
        assertEquals(6, (feedResult as HoslamFeedResult.Frames).frames.size)

        val expectedFinish = HoslamFinishResult.EndOfStream(6L, 220L)
        assertEquals(expectedFinish, framer.finish())
        assertEquals(expectedFinish, framer.finish())
    }

    @Test
    fun testTruncatedBinFixtureFailsWithTruncatedFrameAtOffset0And43BytesRead() {
        val truncatedBytes = HoslamTestFixtures.loadResourceBytes("truncated.bin")
        assertEquals(43, truncatedBytes.size)
        val framer = HoslamStreamFramer()

        assertEquals(HoslamFeedResult.Frames(emptyList()), framer.feed(truncatedBytes))
        val finishResult = framer.finish()
        assertEquals(
            HoslamFinishResult.Failed(LocalCodecError.TruncatedFrame(frameStartOffset = 0L, bytesReadInFrame = 43)),
            finishResult
        )
    }

    @Test
    fun testTruncationAtEveryRelevantByteOffsetAcrossValidMixedStream() {
        val validMixed = HoslamTestFixtures.loadResourceBytes("valid_mixed.bin")
        // Frame start boundaries inside valid_mixed.bin: [0, 44, 64, 88, 132, 176]
        val boundaries = intArrayOf(0, 44, 64, 88, 132, 176, 220)

        for (prefixLen in 1 until validMixed.size) {
            val prefix = validMixed.copyOfRange(0, prefixLen)
            var frameIndex = 0
            while (frameIndex + 1 < boundaries.size && prefixLen >= boundaries[frameIndex + 1]) {
                frameIndex++
            }
            val expectedPriorFrames = frameIndex
            val expectedStartOffset = boundaries[frameIndex].toLong()
            val expectedBytesRead = prefixLen - boundaries[frameIndex]

            // Feed in one chunk AND in 1-byte chunks to cover both header and body truncation paths
            for (useSingleByteChunks in listOf(false, true)) {
                val framer = HoslamStreamFramer()
                val emittedCount = if (useSingleByteChunks) {
                    var count = 0
                    for (b in prefix) {
                        val res = framer.feed(byteArrayOf(b))
                        assertTrue(res is HoslamFeedResult.Frames)
                        count += (res as HoslamFeedResult.Frames).frames.size
                    }
                    count
                } else {
                    val res = framer.feed(prefix) as HoslamFeedResult.Frames
                    res.frames.size
                }
                assertEquals("Prior frame count mismatch at prefixLen=$prefixLen", expectedPriorFrames, emittedCount)

                val finishResult = framer.finish()
                if (expectedBytesRead == 0) {
                    assertEquals(
                        "Clean frame boundary mismatch at prefixLen=$prefixLen (singleByte=$useSingleByteChunks)",
                        HoslamFinishResult.EndOfStream(
                            framesDelivered = expectedPriorFrames.toLong(),
                            bytesConsumed = prefixLen.toLong()
                        ),
                        finishResult
                    )
                } else {
                    assertTrue(expectedBytesRead in 1..43)
                    assertEquals(
                        "Truncation mismatch at prefixLen=$prefixLen (singleByte=$useSingleByteChunks)",
                        HoslamFinishResult.Failed(
                            LocalCodecError.TruncatedFrame(
                                frameStartOffset = expectedStartOffset,
                                bytesReadInFrame = expectedBytesRead
                            )
                        ),
                        finishResult
                    )
                }
            }
        }
    }

    @Test
    fun testValidFrameFollowedByTruncatedFrameEmitsPriorFrameThenFailsAtFinish() {
        val validFrame = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.AUX, byteArrayOf(1, 2, 3, 4))
        val secondPartial = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.MAP, ByteArray(4)).copyOfRange(0, 15)
        val combined = validFrame + secondPartial

        val framer = HoslamStreamFramer()
        val feedResult = framer.feed(combined)
        assertTrue(feedResult is HoslamFeedResult.Frames)
        val frames = (feedResult as HoslamFeedResult.Frames).frames
        assertEquals(1, frames.size)
        assertEquals(0L, frames[0].frameStartOffset)

        val finishResult = framer.finish()
        assertEquals(
            HoslamFinishResult.Failed(LocalCodecError.TruncatedFrame(frameStartOffset = 20L, bytesReadInFrame = 15)),
            finishResult
        )
    }

    @Test
    fun testValidFrameFollowedByMalformedFramePreservesValidPrefixAndSuppressesTrailingFrames() {
        val validFirst = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.MAP, ByteArray(4)) // 20 bytes
        val unknownMagicSecond = HoslamTestFixtures.loadResourceBytes("unknown_magic.bin") // 16 bytes
        val validTrailingThird = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.AUX, byteArrayOf(9, 8, 7, 6))
        val combined = validFirst + unknownMagicSecond + validTrailingThird

        val framer = HoslamStreamFramer()
        val failedResult = framer.feed(combined)
        assertTrue(failedResult is HoslamFeedResult.Failed)
        val failed = failedResult as HoslamFeedResult.Failed
        assertEquals(1, failed.validFramesBeforeFailure.size)
        assertEquals(0L, failed.validFramesBeforeFailure[0].frameStartOffset)
        assertEquals(HoslamFrameKind.MAP, failed.validFramesBeforeFailure[0].kind)
        assertEquals(LocalCodecError.InvalidMagic(frameStartOffset = 20L), failed.error)

        // Terminal failure isolation: no resync on valid subsequent bytes and finish() stays Failed
        assertEquals(HoslamFeedResult.Failed(emptyList(), LocalCodecError.InvalidMagic(20L)), framer.feed(validTrailingThird))
        assertEquals(HoslamFeedResult.Failed(emptyList(), LocalCodecError.InvalidMagic(20L)), framer.feed(ByteArray(0)))
        assertEquals(HoslamFinishResult.Failed(LocalCodecError.InvalidMagic(20L)), framer.finish())
        assertEquals(0, framer.bufferedHeaderByteCount)
        assertEquals(0, framer.activeFrameBufferCapacity)
    }

    @Test
    fun testTerminalFailureIsolationAcrossAllErrorKindsAndCleanResetRecovery() {
        val validMixed = HoslamTestFixtures.loadResourceBytes("valid_mixed.bin")
        val invalidInputs = listOf(
            HoslamTestFixtures.loadResourceBytes("unknown_magic.bin") to LocalCodecError.InvalidMagic(0L),
            HoslamTestFixtures.buildHeaderOnly(HoslamTestFixtures.MAGIC_MAP, 15L) to LocalCodecError.InvalidLength(0L, 15L),
            HoslamTestFixtures.loadResourceBytes("oversized_header.bin") to LocalCodecError.FrameTooLarge(0L, 2_097_153L),
            HoslamTestFixtures.loadResourceBytes("bad_checksum.bin") to LocalCodecError.InvalidChecksum(0L)
        )

        for ((badBytes, expectedError) in invalidInputs) {
            val framer = HoslamStreamFramer()
            val firstFeed = framer.feed(badBytes)
            assertEquals(HoslamFeedResult.Failed(emptyList(), expectedError), firstFeed)

            // Subsequent feed with valid frames MUST NOT resync or emit frames
            assertEquals(HoslamFeedResult.Failed(emptyList(), expectedError), framer.feed(validMixed))
            assertEquals(HoslamFinishResult.Failed(expectedError), framer.finish())

            // Explicit reset() restores clean session at offset 0L
            framer.reset()
            val recoveryFeed = framer.feed(validMixed)
            assertTrue("Expected recovery after reset for $expectedError", recoveryFeed is HoslamFeedResult.Frames)
            val recoveredFrames = (recoveryFeed as HoslamFeedResult.Frames).frames
            assertEquals(6, recoveredFrames.size)
            assertEquals(0L, recoveredFrames.first().frameStartOffset)
            assertEquals(HoslamFinishResult.EndOfStream(6L, 220L), framer.finish())
        }

        // Also test reset() after TruncatedFrame at finish() and after mid-frame partial bytes
        val truncFramer = HoslamStreamFramer()
        truncFramer.feed(HoslamTestFixtures.loadResourceBytes("truncated.bin"))
        val truncFinish = truncFramer.finish()
        assertEquals(HoslamFinishResult.Failed(LocalCodecError.TruncatedFrame(0L, 43)), truncFinish)
        assertEquals(HoslamFeedResult.Failed(emptyList(), LocalCodecError.TruncatedFrame(0L, 43)), truncFramer.feed(validMixed))

        truncFramer.reset()
        val afterReset = truncFramer.feed(validMixed) as HoslamFeedResult.Frames
        assertEquals(6, afterReset.frames.size)
        assertEquals(HoslamFinishResult.EndOfStream(6L, 220L), truncFramer.finish())
    }
}
