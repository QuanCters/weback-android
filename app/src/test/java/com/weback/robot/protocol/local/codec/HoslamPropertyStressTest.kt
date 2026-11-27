package com.weback.robot.protocol.local.codec

import com.weback.robot.domain.map.HoslamFrame
import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.LocalCodecError
import com.weback.robot.domain.map.RawHoslamFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * TEST-LOCAL-510 (AC-502.2, AC-503.1):
 * Deterministic seeded random/mutated byte-stream framing and decoder stress verification
 * under bounded working buffers.
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
            assertEquals("Session $session frame count mismatch", streamParts.size, actualFrames.size)
            for (idx in streamParts.indices) {
                assertArrayEquals("Session $session frame $idx raw bytes mismatch", streamParts[idx], actualFrames[idx].copyBytes())
            }
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

        assertTrue(
            "Expected both clean and failed trials in stress run (failed=$failureCount, clean=$cleanCount)",
            failureCount > 300 && cleanCount > 0
        )
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

    @Test
    fun testSeededValidMixedMapAuxTrajectoryStreamsDecodeDeterministicallyAcross150Sessions() {
        val prng = Random(0x5EED_5103L)
        val framer = HoslamStreamFramer()
        val decoder = HoslamFrameDecoder()

        for (session in 0 until 150) {
            framer.reset()
            val frameCount = prng.nextInt(1, 10)
            val expectedFrames = mutableListOf<ByteArray>()
            val expectedKinds = mutableListOf<HoslamFrameKind>()
            val expectedPixels = mutableListOf<ByteArray?>()
            val expectedSlots = mutableListOf<Int?>()

            for (f in 0 until frameCount) {
                val kind = HoslamFrameKind.entries[prng.nextInt(HoslamFrameKind.entries.size)]
                expectedKinds.add(kind)
                when (kind) {
                    HoslamFrameKind.MAP -> {
                        val w = prng.nextInt(1, 33)
                        val h = prng.nextInt(1, 33)
                        val pixels = ByteArray(w * h).also { prng.nextBytes(it) }
                        val meta = ByteArray(16).also { prng.nextBytes(it) }
                        expectedFrames.add(HoslamTestFixtures.buildMapFrameBytes(w.toLong(), h.toLong(), pixels, meta))
                        expectedPixels.add(pixels)
                        expectedSlots.add(null)
                    }
                    HoslamFrameKind.AUX -> {
                        val auxBody = ByteArray(4).also { prng.nextBytes(it) }
                        expectedFrames.add(HoslamTestFixtures.buildValidFrame(HoslamFrameKind.AUX, auxBody))
                        expectedPixels.add(null)
                        expectedSlots.add(null)
                    }
                    HoslamFrameKind.TRAJECTORY -> {
                        val slots = prng.nextInt(0, 33)
                        val trajBody = ByteArray(slots * 8).also { prng.nextBytes(it) }
                        expectedFrames.add(HoslamTestFixtures.buildValidFrame(HoslamFrameKind.TRAJECTORY, trajBody))
                        expectedPixels.add(null)
                        expectedSlots.add(slots)
                    }
                }
            }

            val stream = expectedFrames.fold(ByteArray(0)) { acc, next -> acc + next }
            val rawFrames = mutableListOf<RawHoslamFrame>()
            var pos = 0
            while (pos < stream.size) {
                val chunkLen = prng.nextInt(1, 64)
                val next = minOf(pos + chunkLen, stream.size)
                val res = framer.feed(stream, offset = pos, length = next - pos)
                assertTrue("Session $session unexpected framer failure: $res", res is HoslamFeedResult.Frames)
                rawFrames.addAll((res as HoslamFeedResult.Frames).frames)
                pos = next
            }
            assertEquals(HoslamFinishResult.EndOfStream(frameCount.toLong(), stream.size.toLong()), framer.finish())
            assertEquals(frameCount, rawFrames.size)

            for (i in rawFrames.indices) {
                val decRes = decoder.decode(rawFrames[i], frameGeneration = i.toLong() + 1L)
                assertTrue("Session $session frame $i decode failed: $decRes", decRes is HoslamDecodeResult.Decoded)
                val decoded = (decRes as HoslamDecodeResult.Decoded).frame
                assertEquals(expectedKinds[i], decoded.kind)
                when (decoded) {
                    is HoslamFrame.Map -> {
                        assertArrayEquals(expectedPixels[i], decoded.snapshot.copyPixels())
                        assertEquals(HoslamTestFixtures.sha256Hex(expectedPixels[i]!!), decoded.snapshot.rasterSha256)
                    }
                    is HoslamFrame.Aux -> assertEquals(4, decoded.opaquePayloadByteCount)
                    is HoslamFrame.Trajectory -> assertEquals(expectedSlots[i], decoded.opaqueSlotCount)
                }
            }
        }
    }

    @Test
    fun testSeededMalformedMapAuxTrajLayoutsFailDeterministicallyAcross300Trials() {
        val prng = Random(0x5EED_5104L)
        val framer = HoslamStreamFramer()
        val decoder = HoslamFrameDecoder()

        for (trial in 0 until 300) {
            framer.reset()
            val prefixAux = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.AUX, ByteArray(4) { 0x11 }) // 20 B
            val expectedOffset = 20L

            val (badFrameBytes, expectedError) = when (trial % 6) {
                0 -> {
                    // MAP total length < 40
                    val len = prng.nextInt(16, 40)
                    val body = ByteArray(len - 16).also { prng.nextBytes(it) }
                    HoslamTestFixtures.buildValidFrame(HoslamFrameKind.MAP, body) to
                        LocalCodecError.UnsupportedLayout(expectedOffset, HoslamFrameKind.MAP, "MAP_LENGTH_BELOW_40")
                }
                1 -> {
                    // Malformed W or H (0, >1024, or overflow-prone high bit)
                    val (w, h) = when (prng.nextInt(4)) {
                        0 -> 0L to prng.nextLong(1L, 100L)
                        1 -> prng.nextLong(1L, 100L) to 0L
                        2 -> prng.nextLong(1025L, 5000L) to prng.nextLong(1L, 100L)
                        else -> (0x8000_0000L + prng.nextLong(0L, 1000L)) to 16L
                    }
                    HoslamTestFixtures.buildMapFrameBytes(w, h, ByteArray(0), overrideTotalLength = 40L) to
                        LocalCodecError.InvalidDimensions(expectedOffset, w, h)
                }
                2 -> {
                    // Inconsistent length != aligned + 4 (min valid for 3x3 is 52)
                    val w = prng.nextInt(3, 17).toLong()
                    val h = prng.nextInt(3, 17).toLong()
                    val badLen = if (prng.nextBoolean()) 40L else 44L
                    HoslamTestFixtures.buildMapFrameBytes(w, h, ByteArray(badLen.toInt() - 40), overrideTotalLength = badLen) to
                        LocalCodecError.UnsupportedLayout(expectedOffset, HoslamFrameKind.MAP, "MAP_LENGTH_LAYOUT_MISMATCH")
                }
                3 -> {
                    // Nonzero padding mutation at random applicable padding index
                    val wChoices = listOf(1L, 2L, 3L, 5L, 6L, 7L)
                    val w = wChoices[prng.nextInt(wChoices.size)]
                    val end = 36 + w.toInt()
                    val aligned = (end + 3) and 3.inv()
                    val padLen = aligned - end
                    val pad = ByteArray(padLen)
                    val mutIdx = prng.nextInt(padLen)
                    pad[mutIdx] = prng.nextInt(1, 256).toByte()
                    HoslamTestFixtures.buildMapFrameBytes(w, 1L, ByteArray(w.toInt()), overridePadding = pad) to
                        LocalCodecError.InvalidPadding(expectedOffset)
                }
                4 -> {
                    // Invalid AUX length != 20
                    val bodyLen = listOf(0, 2, 8, 12)[prng.nextInt(4)]
                    HoslamTestFixtures.buildValidFrame(HoslamFrameKind.AUX, ByteArray(bodyLen)) to
                        LocalCodecError.UnsupportedLayout(expectedOffset, HoslamFrameKind.AUX, "AUX_LENGTH_NOT_20")
                }
                else -> {
                    // Misaligned TRAJECTORY length
                    val badRem = prng.nextInt(1, 8)
                    val slots = prng.nextInt(0, 4)
                    HoslamTestFixtures.buildValidFrame(HoslamFrameKind.TRAJECTORY, ByteArray(slots * 8 + badRem)) to
                        LocalCodecError.UnsupportedLayout(expectedOffset, HoslamFrameKind.TRAJECTORY, "TRAJ_LENGTH_NOT_PAIR_ALIGNED")
                }
            }

            val stream = prefixAux + badFrameBytes
            val feedRes = framer.feed(stream) as HoslamFeedResult.Frames
            assertEquals(HoslamFinishResult.EndOfStream(2L, stream.size.toLong()), framer.finish())
            assertEquals(2, feedRes.frames.size)

            val firstDecoded = decoder.decode(feedRes.frames[0])
            assertTrue("Trial $trial prefix AUX must succeed", firstDecoded is HoslamDecodeResult.Decoded)
            assertEquals(
                "Trial $trial exact error mismatch",
                HoslamDecodeResult.Failed(expectedError),
                decoder.decode(feedRes.frames[1])
            )
        }
    }

    @Test
    fun testMaximumValid1024x1024RasterDecodesWithinBoundedMemoryWithoutOom() {
        val framer = HoslamStreamFramer()
        val decoder = HoslamFrameDecoder()
        val pixels = ByteArray(1024 * 1024) { idx -> ((idx * 13) and 0xFF).toByte() }
        val expectedSha = HoslamTestFixtures.sha256Hex(pixels)
        val maxMapBytes = HoslamTestFixtures.buildMapFrameBytes(1024L, 1024L, pixels)

        repeat(4) { iter ->
            framer.reset()
            val feedRes = framer.feed(maxMapBytes) as HoslamFeedResult.Frames
            assertEquals(HoslamFinishResult.EndOfStream(1L, maxMapBytes.size.toLong()), framer.finish())
            assertEquals(0, framer.bufferedHeaderByteCount)
            assertEquals(0, framer.activeFrameBufferCapacity)

            val decRes = decoder.decode(feedRes.frames.single(), frameGeneration = iter.toLong() + 1L)
            assertTrue("Iteration $iter expected Decoded", decRes is HoslamDecodeResult.Decoded)
            val map = (decRes as HoslamDecodeResult.Decoded).frame as HoslamFrame.Map
            assertEquals(1024, map.snapshot.width)
            assertEquals(1024, map.snapshot.height)
            assertEquals(expectedSha, map.snapshot.rasterSha256)
            assertEquals(pixels[0].toInt() and 0xFF, map.snapshot.pixelAt(0, 0))
            assertEquals(pixels[1024 * 1024 - 1].toInt() and 0xFF, map.snapshot.pixelAt(1023, 1023))
        }
    }
}
