package com.weback.robot.protocol.local.codec

import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.LocalCodecError
import com.weback.robot.domain.map.RawHoslamFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * TEST-LOCAL-510 (framing portion only, AC-502.2):
 * Deterministic seeded random/mutated byte-stream stress verification under bounded working buffers.
 */
class HoslamPropertyStressTest {

    @Test
    fun testSeededValidStreamReassemblyMatchesSingleChunkBaselineAcross200Sessions() {
        val prng = Random(0x5EED_5101L)
        val framerSingle = HoslamStreamFramer()
        val framerChunked = HoslamStreamFramer()

        for (session in 0 until 200) {
            val frameCount = prng.nextInt(1, 9)
            val streamParts = mutableListOf<ByteArray>()
            for (f in 0 until frameCount) {
                val kind = HoslamFrameKind.entries[prng.nextInt(HoslamFrameKind.entries.size)]
                val bodyLen = when (prng.nextInt(10)) {
                    0 -> 0 // minimum 16-byte frame
                    in 1..7 -> prng.nextInt(1, 256)
                    else -> prng.nextInt(256, 4096)
                }
                val body = ByteArray(bodyLen)
                prng.nextBytes(body)
                streamParts.add(HoslamTestFixtures.buildValidFrame(kind, body))
            }
            val fullStream = streamParts.fold(ByteArray(0)) { acc, next -> acc + next }

            framerSingle.reset()
            val singleRes = framerSingle.feed(fullStream)
            assertTrue("Session $session single-chunk failed: $singleRes", singleRes is HoslamFeedResult.Frames)
            val expectedFrames = (singleRes as HoslamFeedResult.Frames).frames
            assertEquals(
                "Session $session finish mismatch",
                HoslamFinishResult.EndOfStream(frameCount.toLong(), fullStream.size.toLong()),
                framerSingle.finish()
            )

            framerChunked.reset()
            val actualFrames = mutableListOf<RawHoslamFrame>()
            var pos = 0
            while (pos < fullStream.size) {
                val chunkLen = prng.nextInt(0, 128)
                val next = minOf(pos + chunkLen, fullStream.size)
                val chunk = fullStream.copyOfRange(pos, next)
                val res = framerChunked.feed(chunk)
                chunk.fill(0x7E)
                assertTrue(
                    "Session $session chunked failure at pos=$pos: $res",
                    res is HoslamFeedResult.Frames
                )
                actualFrames.addAll((res as HoslamFeedResult.Frames).frames)
                assertTrue(
                    "Session $session header buffer exceeded 11 pending bytes: ${framerChunked.bufferedHeaderByteCount}",
                    framerChunked.bufferedHeaderByteCount in 0 until HoslamStreamFramer.HEADER_BYTES
                )
                assertTrue(
                    "Session $session internal active buffer exceeded 2 MiB cap: ${framerChunked.activeFrameBufferCapacity}",
                    framerChunked.activeFrameBufferCapacity <= HoslamStreamFramer.MAX_FRAME_BYTES.toInt()
                )
                pos = next
            }

            assertEquals("Session $session frame sequence mismatch", expectedFrames, actualFrames)
            assertEquals(
                HoslamFinishResult.EndOfStream(frameCount.toLong(), fullStream.size.toLong()),
                framerChunked.finish()
            )
            assertEquals(0, framerChunked.bufferedHeaderByteCount)
            assertEquals(0, framerChunked.activeFrameBufferCapacity)
        }
    }

    @Test
    fun testSeededMutatedAndAdversarialStreamsFailClosedWithBoundedInternalBuffers() {
        val validMixed = HoslamTestFixtures.loadResourceBytes("valid_mixed.bin")
        val prng = Random(0x5EED_5102L)
        val framer = HoslamStreamFramer()

        var failureCount = 0
        var cleanCount = 0

        for (trial in 0 until 400) {
            framer.reset()
            val mutated = when (trial % 4) {
                0 -> {
                    // Arbitrary random byte stream
                    val len = prng.nextInt(0, 512)
                    ByteArray(len).also { prng.nextBytes(it) }
                }
                1 -> {
                    // Mutated valid_mixed.bin (1 to 4 random byte mutations)
                    val copy = validMixed.copyOf()
                    val mutCount = prng.nextInt(1, 5)
                    repeat(mutCount) {
                        val idx = prng.nextInt(copy.size)
                        copy[idx] = (copy[idx].toInt() xor prng.nextInt(1, 256)).toByte()
                    }
                    copy
                }
                2 -> {
                    // Valid magic + arbitrary 32-bit length (including high-bit) + small tail
                    val kind = HoslamFrameKind.entries[prng.nextInt(HoslamFrameKind.entries.size)]
                    val randomU32 = prng.nextLong(0L, 0x1_0000_0000L)
                    val header = HoslamTestFixtures.buildHeaderOnly(HoslamTestFixtures.magicFor(kind), randomU32)
                    val tail = ByteArray(prng.nextInt(0, 64)).also { prng.nextBytes(it) }
                    header + tail
                }
                else -> {
                    // Valid prefix frame followed by truncated/mutated suffix
                    val prefix = validMixed.copyOfRange(0, 44)
                    val suffixLen = prng.nextInt(1, validMixed.size - 44)
                    val suffix = validMixed.copyOfRange(44, 44 + suffixLen)
                    if (suffix.isNotEmpty() && prng.nextBoolean()) {
                        val idx = prng.nextInt(suffix.size)
                        suffix[idx] = (suffix[idx].toInt() xor 0xFF).toByte()
                    }
                    prefix + suffix
                }
            }

            var pos = 0
            var observedError: LocalCodecError? = null
            val validPrefixOffsets = mutableListOf<Long>()
            while (pos < mutated.size) {
                val step = prng.nextInt(1, 37)
                val next = minOf(pos + step, mutated.size)
                val chunk = mutated.copyOfRange(pos, next)
                when (val res = framer.feed(chunk)) {
                    is HoslamFeedResult.Frames -> {
                        for (frame in res.frames) {
                            validPrefixOffsets.add(frame.frameStartOffset)
                        }
                        assertTrue(
                            "Trial $trial active buffer exceeded 2 MiB: ${framer.activeFrameBufferCapacity}",
                            framer.activeFrameBufferCapacity <= HoslamStreamFramer.MAX_FRAME_BYTES.toInt()
                        )
                    }
                    is HoslamFeedResult.Failed -> {
                        for (frame in res.validFramesBeforeFailure) {
                            validPrefixOffsets.add(frame.frameStartOffset)
                        }
                        observedError = res.error
                        // Once failed, internal buffers must be cleared immediately
                        assertEquals("Trial $trial buffer not cleared on error", 0, framer.bufferedHeaderByteCount)
                        assertEquals("Trial $trial frame buffer not cleared on error", 0, framer.activeFrameBufferCapacity)
                        break
                    }
                }
                pos = next
            }

            // Frame start offsets of any emitted valid frames must be strictly increasing
            for (i in 1 until validPrefixOffsets.size) {
                assertTrue(
                    "Trial $trial non-monotonic offset: $validPrefixOffsets",
                    validPrefixOffsets[i] > validPrefixOffsets[i - 1]
                )
            }

            val finishRes = framer.finish()
            if (observedError != null) {
                failureCount++
                assertEquals(
                    "Trial $trial finish must mirror terminal error $observedError",
                    HoslamFinishResult.Failed(observedError),
                    finishRes
                )
                assertEquals(
                    "Trial $trial subsequent feed must remain failed",
                    HoslamFeedResult.Failed(emptyList(), observedError),
                    framer.feed(validMixed)
                )
            } else {
                when (finishRes) {
                    is HoslamFinishResult.EndOfStream -> cleanCount++
                    is HoslamFinishResult.Failed -> {
                        failureCount++
                        assertTrue(
                            "Trial $trial non-feed finish error must be TruncatedFrame, got ${finishRes.error}",
                            finishRes.error is LocalCodecError.TruncatedFrame
                        )
                    }
                }
            }
            assertEquals(0, framer.bufferedHeaderByteCount)
            assertEquals(0, framer.activeFrameBufferCapacity)
        }

        assertTrue("Expected both clean and failed trials in stress run (failed=$failureCount, clean=$cleanCount)", failureCount > 300)
    }

    @Test
    fun testRepeatedMaxSizeFramesRespectSingleActiveBufferLifecycle() {
        val framer = HoslamStreamFramer()
        val maxFrame = HoslamTestFixtures.buildValidFrame(
            HoslamFrameKind.MAP,
            ByteArray(HoslamStreamFramer.MAX_FRAME_BYTES.toInt() - 16) { 0x55 }
        )

        repeat(4) { iteration ->
            val half = maxFrame.size / 2
            val res1 = framer.feed(maxFrame, offset = 0, length = half)
            assertEquals(HoslamFeedResult.Frames(emptyList()), res1)
            assertEquals(HoslamStreamFramer.MAX_FRAME_BYTES.toInt(), framer.activeFrameBufferCapacity)

            val res2 = framer.feed(maxFrame, offset = half, length = maxFrame.size - half)
            assertTrue("Iteration $iteration expected completion", res2 is HoslamFeedResult.Frames)
            val frame = (res2 as HoslamFeedResult.Frames).frames.single()
            assertEquals(iteration.toLong() * maxFrame.size, frame.frameStartOffset)
            assertEquals(0, framer.activeFrameBufferCapacity)
        }
        assertEquals(HoslamFinishResult.EndOfStream(4L, 4L * maxFrame.size), framer.finish())
    }
}
