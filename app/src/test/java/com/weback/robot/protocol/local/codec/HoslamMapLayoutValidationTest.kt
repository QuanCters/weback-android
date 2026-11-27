package com.weback.robot.protocol.local.codec

import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.LocalCodecError
import com.weback.robot.domain.map.RawHoslamFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST-LOCAL-506 (AC-503.1): HOSLAM MAP type-specific layout validation,
 * unsigned dimension bounds, aligned+4 length checks, padding byte checks,
 * multi-fault error precedence, and exact frameStartOffset propagation.
 */
class HoslamMapLayoutValidationTest {

    private val decoder = HoslamFrameDecoder()

    @Test
    fun testMapTotalLengthBelow40EmitsUnsupportedLayoutAcrossAllShortEnvelopeSizes() {
        for (len in listOf(16, 17, 27, 28, 35, 36, 39)) {
            val body = ByteArray(len - 16) { idx ->
                // Fill offset 28..35 area (body index 16..23) with zero bytes where present
                // so a 39-byte frame proves MAP_LENGTH_BELOW_40 beats InvalidDimensions(0, 0).
                if (idx in 16..23) 0x00 else (idx + 1).toByte()
            }
            val rawFrame = HoslamTestFixtures.rawDecodeViaFramer(
                HoslamTestFixtures.buildValidFrame(HoslamFrameKind.MAP, body)
            )
            assertEquals(len, rawFrame.totalLength)

            val result = decoder.decode(rawFrame)
            assertEquals(
                "Length $len must fail with MAP_LENGTH_BELOW_40",
                HoslamDecodeResult.Failed(
                    LocalCodecError.UnsupportedLayout(
                        frameStartOffset = 0L,
                        kind = HoslamFrameKind.MAP,
                        reasonCode = "MAP_LENGTH_BELOW_40"
                    )
                ),
                result
            )
        }
    }

    @Test
    fun testZeroWidthFixtureAndZeroHeightDimensionsEmitInvalidDimensions() {
        val zeroWidthBytes = HoslamTestFixtures.loadResourceBytes("zero_width.bin")
        val zeroWidthRaw = HoslamTestFixtures.rawDecodeViaFramer(zeroWidthBytes)
        assertEquals(
            HoslamDecodeResult.Failed(LocalCodecError.InvalidDimensions(0L, 0L, 1L)),
            decoder.decode(zeroWidthRaw)
        )

        val zeroHeightRaw = HoslamTestFixtures.rawDecodeViaFramer(
            HoslamTestFixtures.buildMapFrameBytes(width = 1L, height = 0L, pixels = ByteArray(0))
        )
        assertEquals(
            HoslamDecodeResult.Failed(LocalCodecError.InvalidDimensions(0L, 1L, 0L)),
            decoder.decode(zeroHeightRaw)
        )

        val bothZeroRaw = HoslamTestFixtures.rawDecodeViaFramer(
            HoslamTestFixtures.buildMapFrameBytes(width = 0L, height = 0L, pixels = ByteArray(0))
        )
        assertEquals(
            HoslamDecodeResult.Failed(LocalCodecError.InvalidDimensions(0L, 0L, 0L)),
            decoder.decode(bothZeroRaw)
        )
    }

    @Test
    fun testDimensionsExceeding1024FailWithInvalidDimensions() {
        val cases = listOf(
            1025L to 1L,
            1L to 1025L,
            1025L to 1024L,
            1024L to 1025L,
            1025L to 1025L,
            2000L to 2000L
        )
        for ((w, h) in cases) {
            val raw = HoslamTestFixtures.rawDecodeViaFramer(
                HoslamTestFixtures.buildMapFrameBytes(
                    width = w,
                    height = h,
                    pixels = ByteArray(0),
                    overrideTotalLength = 40L
                )
            )
            assertEquals(
                "Dimension (${w}x${h}) must emit InvalidDimensions",
                HoslamDecodeResult.Failed(LocalCodecError.InvalidDimensions(0L, w, h)),
                decoder.decode(raw)
            )
        }
    }

    @Test
    fun testUnsignedHighBitAndOverflowProneDimensionsEmitInvalidDimensionsWithExactLongValues() {
        val overflowCases = listOf(
            0x8000_0000L to 1L,
            1L to 0x8000_0000L,
            0xFFFF_FFFFL to 1L,
            1L to 0xFFFF_FFFFL,
            0x1000_0000L to 16L,       // 0x10000000 * 16 wraps to 0 in Int32
            0x1000_0001L to 16L,       // 0x10000001 * 16 wraps to 16 in Int32
            65_536L to 65_536L,        // 65536 * 65536 wraps to 0 in Int32
            0xFFFF_FFFFL to 0xFFFF_FFFFL
        )
        for ((w, h) in overflowCases) {
            val raw = HoslamTestFixtures.rawDecodeViaFramer(
                HoslamTestFixtures.buildMapFrameBytes(
                    width = w,
                    height = h,
                    pixels = ByteArray(0),
                    overrideTotalLength = 40L
                )
            )
            assertEquals(
                "High-bit/overflow dimension (${w}x${h}) must emit exact Long InvalidDimensions",
                HoslamDecodeResult.Failed(LocalCodecError.InvalidDimensions(0L, w, h)),
                decoder.decode(raw)
            )
        }
    }

    @Test
    fun testShortAndLongRasterLayoutsWithValidEnvelopeEmitLayoutMismatch() {
        // Claim 4x4 (needs end=52, aligned=52, total=56), supply valid envelope length 40 and 52
        for (shortLen in listOf(40L, 44L, 52L)) {
            val shortRaw = HoslamTestFixtures.rawDecodeViaFramer(
                HoslamTestFixtures.buildMapFrameBytes(
                    width = 4L,
                    height = 4L,
                    pixels = ByteArray(maxOf(0, shortLen.toInt() - 40)),
                    overrideTotalLength = shortLen
                )
            )
            assertEquals(
                "Short layout length $shortLen for 4x4 must fail MAP_LENGTH_LAYOUT_MISMATCH",
                HoslamDecodeResult.Failed(
                    LocalCodecError.UnsupportedLayout(0L, HoslamFrameKind.MAP, "MAP_LENGTH_LAYOUT_MISMATCH")
                ),
                decoder.decode(shortRaw)
            )
        }

        // Claim 2x2 (needs end=40, aligned=40, total=44), supply valid envelope length 48 and 52
        for (longLen in listOf(48L, 52L)) {
            val extraBytes = ByteArray(longLen.toInt() - 40) { 0x55 }
            val longRaw = HoslamTestFixtures.rawDecodeViaFramer(
                HoslamTestFixtures.buildMapFrameBytes(
                    width = 2L,
                    height = 2L,
                    pixels = ByteArray(4) { 0x11 },
                    overridePadding = extraBytes,
                    overrideTotalLength = longLen
                )
            )
            assertEquals(
                "Long layout length $longLen for 2x2 must fail MAP_LENGTH_LAYOUT_MISMATCH",
                HoslamDecodeResult.Failed(
                    LocalCodecError.UnsupportedLayout(0L, HoslamFrameKind.MAP, "MAP_LENGTH_LAYOUT_MISMATCH")
                ),
                decoder.decode(longRaw)
            )
        }

        // Unaligned end+4 mismatch: 1x1 has end=37, aligned=40, expected=44; supplying 41 (=end+4) must fail
        val unalignedRaw = HoslamTestFixtures.rawDecodeViaFramer(
            HoslamTestFixtures.buildMapFrameBytes(
                width = 1L,
                height = 1L,
                pixels = byteArrayOf(0x42),
                overridePadding = ByteArray(0),
                overrideTotalLength = 41L
            )
        )
        assertEquals(
            "Unaligned end+4 length 41 for 1x1 must fail MAP_LENGTH_LAYOUT_MISMATCH",
            HoslamDecodeResult.Failed(
                LocalCodecError.UnsupportedLayout(0L, HoslamFrameKind.MAP, "MAP_LENGTH_LAYOUT_MISMATCH")
            ),
            decoder.decode(unalignedRaw)
        )
    }

    @Test
    fun testAllFourValidPaddingLengthsDecodeCleanly() {
        // 4x1 -> end=40, aligned=40, pad=0
        // 7x1 -> end=43, aligned=44, pad=1
        // 6x1 -> end=42, aligned=44, pad=2
        // 5x1 -> end=41, aligned=44, pad=3
        val cases = listOf(
            4L to 1L,
            7L to 1L,
            6L to 1L,
            5L to 1L
        )
        for ((w, h) in cases) {
            val pixels = ByteArray((w * h).toInt()) { idx -> (idx + 0x80).toByte() }
            val raw = HoslamTestFixtures.rawDecodeViaFramer(
                HoslamTestFixtures.buildMapFrameBytes(width = w, height = h, pixels = pixels)
            )
            val decoded = decoder.decode(raw)
            assertTrue("Expected valid decode for ${w}x${h} (pad=${(4 - ((36 + w * h) % 4)) % 4}), got $decoded", decoded is HoslamDecodeResult.Decoded)
            val mapFrame = (decoded as HoslamDecodeResult.Decoded).frame as com.weback.robot.domain.map.HoslamFrame.Map
            assertEquals(w.toInt(), mapFrame.snapshot.width)
            assertEquals(h.toInt(), mapFrame.snapshot.height)
        }
    }

    @Test
    fun testBadPaddingFixtureAndNonzeroPaddingAtEveryApplicablePositionEmitInvalidPadding() {
        val badPaddingRaw = HoslamTestFixtures.rawDecodeViaFramer(
            HoslamTestFixtures.loadResourceBytes("bad_padding.bin")
        )
        assertEquals(
            HoslamDecodeResult.Failed(LocalCodecError.InvalidPadding(0L)),
            decoder.decode(badPaddingRaw)
        )

        // Pad length 3 (1x1 -> end=37, aligned=40): mutate each pad byte 0, 1, 2 independently
        for (padIndex in 0 until 3) {
            val pad = ByteArray(3)
            pad[padIndex] = when (padIndex) {
                0 -> 0x01
                1 -> 0x80.toByte()
                else -> 0xFF.toByte()
            }
            val raw = HoslamTestFixtures.rawDecodeViaFramer(
                HoslamTestFixtures.buildMapFrameBytes(
                    width = 1L,
                    height = 1L,
                    pixels = byteArrayOf(0x42),
                    overridePadding = pad
                )
            )
            assertEquals(
                "Nonzero pad at index $padIndex for padLen=3 must emit InvalidPadding",
                HoslamDecodeResult.Failed(LocalCodecError.InvalidPadding(0L)),
                decoder.decode(raw)
            )
        }

        // Pad length 2 (2x1 -> end=38, aligned=40): mutate pad indices 0, 1
        for (padIndex in 0 until 2) {
            val pad = ByteArray(2)
            pad[padIndex] = 0x02
            val raw = HoslamTestFixtures.rawDecodeViaFramer(
                HoslamTestFixtures.buildMapFrameBytes(
                    width = 2L,
                    height = 1L,
                    pixels = byteArrayOf(0x10, 0x20),
                    overridePadding = pad
                )
            )
            assertEquals(
                "Nonzero pad at index $padIndex for padLen=2 must emit InvalidPadding",
                HoslamDecodeResult.Failed(LocalCodecError.InvalidPadding(0L)),
                decoder.decode(raw)
            )
        }

        // Pad length 1 (3x1 -> end=39, aligned=40): mutate pad index 0
        val rawPad1 = HoslamTestFixtures.rawDecodeViaFramer(
            HoslamTestFixtures.buildMapFrameBytes(
                width = 3L,
                height = 1L,
                pixels = byteArrayOf(0x10, 0x20, 0x30),
                overridePadding = byteArrayOf(0x80.toByte())
            )
        )
        assertEquals(
            HoslamDecodeResult.Failed(LocalCodecError.InvalidPadding(0L)),
            decoder.decode(rawPad1)
        )
    }

    @Test
    fun testMultiFaultLayoutPrecedenceAndExactFrameStartOffsetPropagation() {
        // 1. Precedence: length=39 + W=0, H=0 -> MAP_LENGTH_BELOW_40 beats InvalidDimensions
        val len39WithZeroDims = ByteArray(23) // body of 39-byte frame (total = 12 + 23 + 4 = 39)
        val raw39 = HoslamTestFixtures.buildEnvelopeRawFrame(HoslamFrameKind.MAP, len39WithZeroDims, frameStartOffset = 44L)
        assertEquals(
            HoslamDecodeResult.Failed(LocalCodecError.UnsupportedLayout(44L, HoslamFrameKind.MAP, "MAP_LENGTH_BELOW_40")),
            decoder.decode(raw39)
        )

        // 2. Precedence: W=1025, H=1 + length=40 -> InvalidDimensions beats MAP_LENGTH_LAYOUT_MISMATCH
        val rawDimAndMismatch = HoslamTestFixtures.rawDecodeViaFramer(
            HoslamTestFixtures.buildMapFrameBytes(
                width = 1025L,
                height = 1L,
                pixels = ByteArray(0),
                overrideTotalLength = 40L
            )
        )
        assertEquals(
            HoslamDecodeResult.Failed(LocalCodecError.InvalidDimensions(0L, 1025L, 1L)),
            decoder.decode(rawDimAndMismatch)
        )

        // 3. Precedence: W=2, H=2 (expected 44) + length=48 + nonzero padding byte -> MAP_LENGTH_LAYOUT_MISMATCH beats InvalidPadding
        val rawMismatchAndPad = HoslamTestFixtures.rawDecodeViaFramer(
            HoslamTestFixtures.buildMapFrameBytes(
                width = 2L,
                height = 2L,
                pixels = ByteArray(4) { 0x33 },
                overridePadding = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
                overrideTotalLength = 48L
            )
        )
        assertEquals(
            HoslamDecodeResult.Failed(
                LocalCodecError.UnsupportedLayout(0L, HoslamFrameKind.MAP, "MAP_LENGTH_LAYOUT_MISMATCH")
            ),
            decoder.decode(rawMismatchAndPad)
        )

        // 4. Exact non-zero offset propagation through real framer stream & high Long offset
        val prefixAux = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.AUX, byteArrayOf(1, 2, 3, 4)) // 20 B
        val badPadBytes = HoslamTestFixtures.loadResourceBytes("bad_padding.bin") // 44 B
        val zeroWBytes = HoslamTestFixtures.loadResourceBytes("zero_width.bin") // 40 B
        val (frames, _) = HoslamTestFixtures.decodeStreamFrames(prefixAux + badPadBytes + zeroWBytes)
        assertEquals(3, frames.size)
        assertEquals(
            HoslamDecodeResult.Failed(LocalCodecError.InvalidPadding(20L)),
            decoder.decode(frames[1])
        )
        assertEquals(
            HoslamDecodeResult.Failed(LocalCodecError.InvalidDimensions(64L, 0L, 1L)),
            decoder.decode(frames[2])
        )

        // High 64-bit session offset propagation (> Int.MAX_VALUE)
        val highOffset = 4_294_967_296L
        val highOffsetRaw = RawHoslamFrame(HoslamFrameKind.MAP, highOffset, badPadBytes)
        assertEquals(
            HoslamDecodeResult.Failed(LocalCodecError.InvalidPadding(highOffset)),
            decoder.decode(highOffsetRaw)
        )
    }

    @Test
    fun testMaxValid1024x1024MapDimensionsPassLayoutValidation() {
        val maxPixels = ByteArray(1024 * 1024) { idx -> (idx and 0xFF).toByte() }
        val maxMapBytes = HoslamTestFixtures.buildMapFrameBytes(
            width = 1024L,
            height = 1024L,
            pixels = maxPixels
        )
        assertEquals(1_048_616, maxMapBytes.size)
        val raw = HoslamTestFixtures.rawDecodeViaFramer(maxMapBytes)
        val res = decoder.decode(raw)
        assertTrue("1024x1024 valid map should decode cleanly, got $res", res is HoslamDecodeResult.Decoded)
        val snapshot = ((res as HoslamDecodeResult.Decoded).frame as com.weback.robot.domain.map.HoslamFrame.Map).snapshot
        assertEquals(1024, snapshot.width)
        assertEquals(1024, snapshot.height)
        assertEquals(0, snapshot.pixelAt(0, 0))
        assertEquals(255, snapshot.pixelAt(1023, 1023))
    }
}
