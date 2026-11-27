package com.weback.robot.protocol.local.codec

import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.RawHoslamFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * TEST-LOCAL-503 (AC-502.1): Fragmentation, coalescing, empty chunks,
 * slice offset/length handling, and caller buffer mutation isolation.
 */
class HoslamFramerChunkingTest {

    private val expectedValidMixedKinds = listOf(
        HoslamFrameKind.MAP,
        HoslamFrameKind.AUX,
        HoslamFrameKind.TRAJECTORY,
        HoslamFrameKind.MAP,
        HoslamFrameKind.MAP,
        HoslamFrameKind.MAP
    )
    private val expectedValidMixedOffsets = listOf(0L, 44L, 64L, 88L, 132L, 176L)
    private val expectedValidMixedLengths = listOf(44, 20, 24, 44, 44, 44)
    private val expectedValidMixedChecksums = listOf(1424L, 943L, 1342L, 1297L, 1168L, 1167L)

    @Test
    fun testImportedSyntheticFixturesMatchAuthoritativeHashesAndSizes() {
        val expectedManifest = mapOf(
            "valid_mixed.bin" to (220 to "631aa7fabc331da5b9245a193a4f00c0a242096e487db96dfca2707835a46fe5"),
            "bad_checksum.bin" to (44 to "47c0c4c613ddf1ac444c8fee89234fcec7d6236efd95c64c60cd7e3a791f5c82"),
            "bad_padding.bin" to (44 to "197a8fb2ccc787dfa6ae9018cb060ab399ecab8c277c4bc507384dd8eb6d74d7"),
            "truncated.bin" to (43 to "73bfe975102a62cac5e14fb2012e0176d36de4d08560bf3923760faa8e701743"),
            "oversized_header.bin" to (12 to "9a27de4e74dca8188e505429a0abaf0e67bf8e46a5c8f91ce6de88ea3c4b9890"),
            "unknown_magic.bin" to (16 to "afa798ad53b0e767064ac78da97455039e5f98db0c2777eb518c7bb4e95e6431"),
            "zero_width.bin" to (40 to "40996e4c94c3a2d4f25cbdb24bc0f3b677b8bd5cee666329d602b5e749dc842c")
        )
        for ((name, meta) in expectedManifest) {
            val bytes = HoslamTestFixtures.loadResourceBytes(name)
            assertEquals("Byte count mismatch for $name", meta.first, bytes.size)
            assertEquals("SHA-256 mismatch for $name", meta.second, HoslamTestFixtures.sha256Hex(bytes))
        }
        val manifestText = HoslamTestFixtures.loadResourceBytes("manifest.json").decodeToString()
        assertTrue(manifestText.contains("\"valid_expected_counts\""))
        assertTrue(manifestText.contains("631aa7fabc331da5b9245a193a4f00c0a242096e487db96dfca2707835a46fe5"))
    }

    @Test
    fun testCoalescedValidMixedSingleChunkMatchesIndependentManifestExpectations() {
        val validMixed = HoslamTestFixtures.loadResourceBytes("valid_mixed.bin")
        val framer = HoslamStreamFramer()
        val result = framer.feed(validMixed)
        assertTrue(result is HoslamFeedResult.Frames)
        val frames = (result as HoslamFeedResult.Frames).frames
        assertValidMixedSequence(validMixed, frames)
        assertEquals(HoslamFinishResult.EndOfStream(6L, 220L), framer.finish())
        assertEquals(0, framer.bufferedHeaderByteCount)
        assertEquals(0, framer.activeFrameBufferCapacity)
    }

    @Test
    fun testEverySplitPositionAcrossValidMixedAndSmallLiteralFrames() {
        val validMixed = HoslamTestFixtures.loadResourceBytes("valid_mixed.bin")
        val baselineFrames = assertChunkingMatches(validMixed, listOf(validMixed))
        assertValidMixedSequence(validMixed, baselineFrames)

        for (splitAt in 1 until validMixed.size) {
            val left = validMixed.copyOfRange(0, splitAt)
            val right = validMixed.copyOfRange(splitAt, validMixed.size)
            val splitFrames = assertChunkingMatches(validMixed, listOf(left, right))
            assertEquals("Mismatch at split index $splitAt", baselineFrames, splitFrames)
        }

        val minFrame16 = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.MAP, ByteArray(0))
        val auxFrame20 = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.AUX, byteArrayOf(1, 2, 3, 4))
        for (singleFrame in listOf(minFrame16, auxFrame20)) {
            for (splitAt in 1 until singleFrame.size) {
                val framer = HoslamStreamFramer()
                val (frames, failure) = HoslamTestFixtures.feedAll(
                    framer,
                    singleFrame.copyOfRange(0, splitAt),
                    singleFrame.copyOfRange(splitAt, singleFrame.size)
                )
                assertNull("Unexpected failure at split $splitAt: $failure", failure)
                assertEquals(1, frames.size)
                assertArrayEquals(singleFrame, frames.single().copyBytes())
                assertEquals(HoslamFinishResult.EndOfStream(1L, singleFrame.size.toLong()), framer.finish())
            }
        }
    }

    @Test
    fun testFixedChunkSizes1Byte7Bytes12BytesProduceIdenticalValidMixedFrames() {
        val validMixed = HoslamTestFixtures.loadResourceBytes("valid_mixed.bin")
        for (chunkSize in listOf(1, 7, 12)) {
            val chunks = (0 until validMixed.size step chunkSize).map { start ->
                validMixed.copyOfRange(start, minOf(start + chunkSize, validMixed.size))
            }
            val frames = assertChunkingMatches(validMixed, chunks)
            assertValidMixedSequence(validMixed, frames)
        }
    }

    @Test
    fun testDeterministicSeededRandomChunkPartitionsProduceIdenticalFrames() {
        val validMixed = HoslamTestFixtures.loadResourceBytes("valid_mixed.bin")
        val seeds = listOf(1337L, 42_000L, 9_876_543L)
        for (seed in seeds) {
            val prng = Random(seed)
            val chunks = mutableListOf<ByteArray>()
            var pos = 0
            while (pos < validMixed.size) {
                // Include occasional 0-byte chunks alongside 1..32 byte fragments
                val size = prng.nextInt(0, 33)
                val next = minOf(pos + size, validMixed.size)
                chunks.add(validMixed.copyOfRange(pos, next))
                pos = next
            }
            val frames = assertChunkingMatches(validMixed, chunks)
            assertValidMixedSequence(validMixed, frames)
        }
    }

    @Test
    fun testEmptyChunksAndSliceOffsetsDoNotCorruptStream() {
        val validMixed = HoslamTestFixtures.loadResourceBytes("valid_mixed.bin")
        val padded = ByteArray(10 + validMixed.size + 10) { 0xEE.toByte() }
        System.arraycopy(validMixed, 0, padded, 10, validMixed.size)

        val framer = HoslamStreamFramer()
        assertEquals(HoslamFeedResult.Frames(emptyList()), framer.feed(ByteArray(0)))
        assertEquals(HoslamFeedResult.Frames(emptyList()), framer.feed(padded, offset = 10, length = 0))

        // Split slice inside padded array: first 15 bytes, empty chunk, next 50 bytes, empty chunk, remainder
        val collected = mutableListOf<RawHoslamFrame>()
        val r1 = framer.feed(padded, offset = 10, length = 15) as HoslamFeedResult.Frames
        collected.addAll(r1.frames)
        assertEquals(0, collected.size)

        assertEquals(HoslamFeedResult.Frames(emptyList()), framer.feed(padded, offset = 25, length = 0))

        val r2 = framer.feed(padded, offset = 25, length = 50) as HoslamFeedResult.Frames
        collected.addAll(r2.frames)
        assertEquals(2, collected.size) // MAP(44) + AUX(20) completed within first 65 bytes

        val r3 = framer.feed(padded, offset = 75, length = validMixed.size - 65) as HoslamFeedResult.Frames
        collected.addAll(r3.frames)
        assertValidMixedSequence(validMixed, collected)
        assertEquals(HoslamFinishResult.EndOfStream(6L, 220L), framer.finish())
    }

    @Test
    fun testCallerChunkArrayMutationAfterFeedDoesNotAffectFraming() {
        val validMixed = HoslamTestFixtures.loadResourceBytes("valid_mixed.bin")
        val framer = HoslamStreamFramer()
        val emitted = mutableListOf<RawHoslamFrame>()

        // Feed in 9-byte reusable scratch buffer, overwriting immediately after each feed() returns
        val reusableChunk = ByteArray(9)
        var pos = 0
        while (pos < validMixed.size) {
            val len = minOf(9, validMixed.size - pos)
            System.arraycopy(validMixed, pos, reusableChunk, 0, len)
            val result = framer.feed(reusableChunk, offset = 0, length = len)
            // Mutate caller-owned buffer immediately after feed() returns
            reusableChunk.fill(0x5A)
            assertTrue(result is HoslamFeedResult.Frames)
            emitted.addAll((result as HoslamFeedResult.Frames).frames)
            pos += len
        }

        assertValidMixedSequence(validMixed, emitted)
        assertEquals(HoslamFinishResult.EndOfStream(6L, 220L), framer.finish())
    }

    @Test
    fun testInvalidFeedSliceBoundsFailWithIllegalArgumentException() {
        val framer = HoslamStreamFramer()
        val buf = ByteArray(8)
        val invalidCalls = listOf(
            { framer.feed(buf, offset = -1, length = 1) },
            { framer.feed(buf, offset = 0, length = -1) },
            { framer.feed(buf, offset = 5, length = 4) },
            { framer.feed(buf, offset = Int.MAX_VALUE, length = 1) }
        )
        for (call in invalidCalls) {
            var threw = false
            try {
                call()
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("Expected IllegalArgumentException for out-of-range slice", threw)
        }
    }

    private fun assertChunkingMatches(fullBytes: ByteArray, chunks: List<ByteArray>): List<RawHoslamFrame> {
        val framer = HoslamStreamFramer()
        val (frames, failure) = HoslamTestFixtures.feedAll(framer, *chunks.toTypedArray())
        assertNull("Unexpected failure during chunked feed: $failure", failure)
        assertEquals(
            HoslamFinishResult.EndOfStream(expectedValidMixedKinds.size.toLong(), fullBytes.size.toLong()),
            framer.finish()
        )
        return frames
    }

    private fun assertValidMixedSequence(rawStream: ByteArray, frames: List<RawHoslamFrame>) {
        assertEquals(6, frames.size)
        for (i in frames.indices) {
            val f = frames[i]
            val start = expectedValidMixedOffsets[i].toInt()
            val len = expectedValidMixedLengths[i]
            assertEquals("Kind mismatch at frame $i", expectedValidMixedKinds[i], f.kind)
            assertEquals("Offset mismatch at frame $i", expectedValidMixedOffsets[i], f.frameStartOffset)
            assertEquals("Length mismatch at frame $i", len, f.totalLength)
            assertEquals("Checksum mismatch at frame $i", expectedValidMixedChecksums[i], f.checksum)
            assertArrayEquals(
                "Bytes mismatch at frame $i",
                rawStream.copyOfRange(start, start + len),
                f.copyBytes()
            )
        }
    }
}
