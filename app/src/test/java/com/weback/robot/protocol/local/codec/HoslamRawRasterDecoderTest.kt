package com.weback.robot.protocol.local.codec

import com.weback.robot.domain.map.HoslamFrame
import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.RasterSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater

/**
 * TEST-LOCAL-507 (AC-503.2): Raw unsigned 8-bit grayscale decoding,
 * golden SHA-256 verification against manifest.json, row-major top-left orientation,
 * zero ZLIB/Inflater invocation, opaque metadata handling, defensive ownership,
 * bounds-safe pixelAt, content equality, and redacted toString.
 */
class HoslamRawRasterDecoderTest {

    private val decoder = HoslamFrameDecoder()

    @Test
    fun testValidMixedAllFourMapsMatchManifestGoldenPixelsAndSha256() {
        val validMixedBytes = HoslamTestFixtures.loadResourceBytes("valid_mixed.bin")
        val (rawFrames, finishResult) = HoslamTestFixtures.decodeStreamFrames(validMixedBytes)
        assertEquals(HoslamFinishResult.EndOfStream(6L, 220L), finishResult)
        assertEquals(6, rawFrames.size)

        val decoded = rawFrames.map { raw ->
            val res = decoder.decode(raw)
            assertTrue("Expected Decoded for $raw, got $res", res is HoslamDecodeResult.Decoded)
            (res as HoslamDecodeResult.Decoded).frame
        }

        val mapFrames = decoded.filterIsInstance<HoslamFrame.Map>()
        assertEquals(4, mapFrames.size)
        assertEquals(1, decoded.count { it is HoslamFrame.Aux })
        assertEquals(1, decoded.count { it is HoslamFrame.Trajectory })

        // Independent golden expectations copied verbatim from manifest.json
        data class GoldenMap(
            val offset: Long,
            val width: Int,
            val height: Int,
            val pixels: ByteArray,
            val expectedSha256: String
        )

        val expectedMaps = listOf(
            GoldenMap(
                offset = 0L,
                width = 2,
                height = 2,
                pixels = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte()),
                expectedSha256 = "89273d2f70b93285bb7ddb4bcee86a5347ca7159352e3cbdd20c23e9d1e507d3"
            ),
            GoldenMap(
                offset = 88L,
                width = 3,
                height = 1,
                pixels = byteArrayOf(0xFF.toByte(), 0x00, 0x80.toByte()),
                expectedSha256 = "ef192b7af54e943f206ab27075ec1805384c972c9959fc5820f1fa7d5268fcef"
            ),
            GoldenMap(
                offset = 132L,
                width = 2,
                height = 1,
                pixels = byteArrayOf(0x80.toByte(), 0x7F),
                expectedSha256 = "e65aceb89baab6ddba7f8ff28bdaf5da68026060445be6ac268c138d9a959b3f"
            ),
            GoldenMap(
                offset = 176L,
                width = 1,
                height = 1,
                pixels = byteArrayOf(0xFF.toByte()),
                expectedSha256 = "a8100ae6aa1940d0b663bb31cd466142ebbdbd5187131b92d93818987832eb89"
            )
        )

        for (i in expectedMaps.indices) {
            val expected = expectedMaps[i]
            val actual = mapFrames[i]
            assertEquals("Map $i frameStartOffset", expected.offset, actual.frameStartOffset)
            assertEquals("Map $i width", expected.width, actual.snapshot.width)
            assertEquals("Map $i height", expected.height, actual.snapshot.height)
            assertArrayEquals("Map $i raw pixels", expected.pixels, actual.snapshot.copyPixels())
            assertEquals("Map $i rasterSha256", expected.expectedSha256, actual.snapshot.rasterSha256)
            assertEquals(
                "Map $i independent SHA-256",
                expected.expectedSha256,
                HoslamTestFixtures.sha256Hex(actual.snapshot.copyPixels())
            )
        }

        // Verify exact unsigned values on map 0 (2x2: [0x00, 0x7F; 0x80, 0xFF])
        val m0 = mapFrames[0].snapshot
        assertEquals(0, m0.pixelAt(0, 0))
        assertEquals(127, m0.pixelAt(1, 0))
        assertEquals(128, m0.pixelAt(0, 1))
        assertEquals(255, m0.pixelAt(1, 1))
    }

    @Test
    fun testAsymmetricRasterDecodesInExactRowMajorTopLeftOrderWithoutFlipOrNormalization() {
        // 3x2 asymmetric raster:
        // Row y=0 (top):    [0x00, 0x7F, 0x80]
        // Row y=1 (bottom): [0xFF, 0x10, 0xFE]
        val pixels = byteArrayOf(
            0x00, 0x7F, 0x80.toByte(),
            0xFF.toByte(), 0x10, 0xFE.toByte()
        )
        val metadata = ByteArray(16) { idx -> (0xA0 + idx).toByte() }
        val raw = HoslamTestFixtures.rawDecodeViaFramer(
            HoslamTestFixtures.buildMapFrameBytes(
                width = 3L,
                height = 2L,
                pixels = pixels,
                metadata = metadata
            )
        )
        val decodedRes = decoder.decode(raw, frameGeneration = 7L)
        assertTrue("Expected Decoded, got $decodedRes", decodedRes is HoslamDecodeResult.Decoded)
        val mapFrame = (decodedRes as HoslamDecodeResult.Decoded).frame as HoslamFrame.Map
        val snapshot = mapFrame.snapshot

        assertEquals(3, snapshot.width)
        assertEquals(2, snapshot.height)
        assertEquals(7L, snapshot.frameGeneration)
        assertArrayEquals(pixels, snapshot.copyPixels())
        assertArrayEquals(metadata, snapshot.copyOpaqueMetadata())

        // Row 0 (top-left origin): (0,0)=0, (1,0)=127, (2,0)=128
        assertEquals(0, snapshot.pixelAt(0, 0))
        assertEquals(127, snapshot.pixelAt(1, 0))
        assertEquals(128, snapshot.pixelAt(2, 0))
        // Row 1: (0,1)=255, (1,1)=16, (2,1)=254
        assertEquals(255, snapshot.pixelAt(0, 1))
        assertEquals(16, snapshot.pixelAt(1, 1))
        assertEquals(254, snapshot.pixelAt(2, 1))

        // Verify top-left RGBA=(v,v,v,255) presentation mapping produces exact channel bytes
        val expectedRgbaTopRow = listOf(
            listOf(0, 0, 0, 255),
            listOf(127, 127, 127, 255),
            listOf(128, 128, 128, 255)
        )
        for (x in 0 until 3) {
            val v = snapshot.pixelAt(x, 0)
            assertEquals(expectedRgbaTopRow[x], listOf(v, v, v, 255))
        }
    }

    @Test
    fun testValidZlibCompressedBytesInPayloadAreReturnedAsRawPixelsWithoutInflaterDecompression() {
        // Repetitive 64-byte plaintext compresses to ~12 ZLIB bytes (< plaintext size), so even a
        // conditional Inflater fallback checking inflatedCount > 0 or == rawSlice.size would alter pixels.
        val plaintext = ByteArray(64) { 'A'.code.toByte() }
        val deflater = Deflater()
        deflater.setInput(plaintext)
        deflater.finish()
        val deflatedOut = ByteArrayOutputStream()
        val buf = ByteArray(128)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            deflatedOut.write(buf, 0, count)
        }
        deflater.end()
        val zlibBytes = deflatedOut.toByteArray()
        assertTrue("Expected ZLIB stream shorter than 64-byte plaintext, got ${zlibBytes.size}", zlibBytes.size < plaintext.size)
        // ZLIB stream header starts with 0x78
        assertEquals(0x78, zlibBytes[0].toInt() and 0xFF)

        val raw = HoslamTestFixtures.rawDecodeViaFramer(
            HoslamTestFixtures.buildMapFrameBytes(
                width = zlibBytes.size.toLong(),
                height = 1L,
                pixels = zlibBytes
            )
        )
        val decodedRes = decoder.decode(raw)
        assertTrue("Expected Decoded, got $decodedRes", decodedRes is HoslamDecodeResult.Decoded)
        val mapFrame = (decodedRes as HoslamDecodeResult.Decoded).frame as HoslamFrame.Map
        assertEquals(zlibBytes.size, mapFrame.snapshot.width)
        assertEquals(1, mapFrame.snapshot.height)
        assertArrayEquals(
            "Decoder must treat ZLIB-shaped bytes strictly as raw u8 grayscale without inflating",
            zlibBytes,
            mapFrame.snapshot.copyPixels()
        )
    }

    @Test
    fun testCodecAndMapDomainSourcesContainZeroZlibOrCompressionReferences() {
        val searchDirs = listOf(
            listOf(File("src/main/java/com/weback/robot/domain/map"), File("app/src/main/java/com/weback/robot/domain/map")).first { it.isDirectory },
            listOf(File("src/main/java/com/weback/robot/protocol/local/codec"), File("app/src/main/java/com/weback/robot/protocol/local/codec")).first { it.isDirectory }
        )
        val prohibited = listOf(
            "java.util.zip",
            "Inflater",
            "InflaterInputStream",
            "GZIPInputStream",
            "ZipInputStream",
            "Deflater",
            "LZ4"
        )
        val violations = HoslamTestFixtures.findProhibitedCodeTokens(searchDirs, prohibited)
        assertTrue("Prohibited compression/ZLIB references found: $violations", violations.isEmpty())
    }

    @Test
    fun testRasterSnapshotDefensiveInputAndOutputMutationIsolationAndBoundsSafePixelAt() {
        val inputPixels = byteArrayOf(0x01, 0x7F, 0x80.toByte(), 0xFF.toByte(), 0x42, 0x99.toByte())
        val expectedPixels = inputPixels.copyOf()
        val inputMeta = ByteArray(16) { (it + 1).toByte() }
        val expectedMeta = inputMeta.copyOf()

        val snapshot = RasterSnapshot(
            width = 3,
            height = 2,
            pixels = inputPixels,
            opaqueMetadata = inputMeta,
            frameGeneration = 5L
        )
        // Mutate constructor inputs immediately
        inputPixels.fill(0x00)
        inputMeta.fill(0x00)

        assertArrayEquals(expectedPixels, snapshot.copyPixels())
        assertArrayEquals(expectedMeta, snapshot.copyOpaqueMetadata())
        assertEquals(0x80, snapshot.pixelAt(2, 0))
        assertEquals(0xFF, snapshot.pixelAt(0, 1))
        assertEquals(1, snapshot.opaqueMetadataByteAt(0))
        assertEquals(16, snapshot.opaqueMetadataByteAt(15))

        // Mutate returned copies immediately
        snapshot.copyPixels().fill(0x5A)
        snapshot.copyOpaqueMetadata().fill(0x5A)

        assertArrayEquals(expectedPixels, snapshot.copyPixels())
        assertArrayEquals(expectedMeta, snapshot.copyOpaqueMetadata())

        // Bounds-safe pixelAt (including negative-x wraparound attempt x=-2, y=1)
        val badCoords = listOf(
            -1 to 0,
            3 to 0,
            0 to -1,
            0 to 2,
            -2 to 1,
            Int.MIN_VALUE to 0,
            0 to Int.MAX_VALUE,
            Int.MAX_VALUE to Int.MAX_VALUE
        )
        for ((bx, by) in badCoords) {
            var threw = false
            try {
                snapshot.pixelAt(bx, by)
            } catch (_: IndexOutOfBoundsException) {
                threw = true
            }
            assertTrue("Expected IndexOutOfBoundsException for pixelAt($bx, $by)", threw)
        }

        for (badMetaIdx in listOf(-1, 16)) {
            var threw = false
            try {
                snapshot.opaqueMetadataByteAt(badMetaIdx)
            } catch (_: IndexOutOfBoundsException) {
                threw = true
            }
            assertTrue("Expected IndexOutOfBoundsException for opaqueMetadataByteAt($badMetaIdx)", threw)
        }
    }

    @Test
    fun testRasterSnapshotEqualityHashCodeAndRedactedToString() {
        val secretAscii = "SECRET_HOUSEHOLD_PIXELS_"
        val pixelsA = secretAscii.encodeToByteArray() // 24 bytes -> 6x4 or 4x6
        assertEquals(24, pixelsA.size)
        val metaA = "PRIVATE_META_123".encodeToByteArray()
        assertEquals(16, metaA.size)

        val snapA1 = RasterSnapshot(6, 4, pixelsA, metaA, frameGeneration = 3L)
        val snapA2 = RasterSnapshot(6, 4, pixelsA.copyOf(), metaA.copyOf(), frameGeneration = 3L)
        val snapDiffShape = RasterSnapshot(4, 6, pixelsA.copyOf(), metaA.copyOf(), frameGeneration = 3L)
        val snapDiffGen = RasterSnapshot(6, 4, pixelsA.copyOf(), metaA.copyOf(), frameGeneration = 4L)
        val snapDiffMeta = RasterSnapshot(6, 4, pixelsA.copyOf(), ByteArray(16), frameGeneration = 3L)
        val diffPixels = pixelsA.copyOf().also { it[23] = (it[23].toInt() xor 0xFF).toByte() }
        val snapDiffPixels = RasterSnapshot(6, 4, diffPixels, metaA.copyOf(), frameGeneration = 3L)

        assertEquals(snapA1, snapA2)
        assertEquals(snapA1.hashCode(), snapA2.hashCode())
        assertEquals(snapA1.rasterSha256, snapA2.rasterSha256)
        assertNotEquals(snapA1, snapDiffShape)
        assertNotEquals(snapA1, snapDiffGen)
        assertNotEquals(snapA1, snapDiffMeta)
        assertNotEquals(snapA1, snapDiffPixels)

        val rendered = snapA1.toString()
        assertFalse("toString leaked pixel ASCII: $rendered", rendered.contains("SECRET_HOUSEHOLD"))
        assertFalse("toString leaked metadata ASCII: $rendered", rendered.contains("PRIVATE_META"))
        assertTrue(rendered.contains("width=6"))
        assertTrue(rendered.contains("height=4"))
        assertTrue(rendered.contains("frameGeneration=3"))
        assertTrue(rendered.contains(snapA1.rasterSha256))
    }

    @Test
    fun testRasterSnapshotAndDecoderRejectInvalidConstructorArguments() {
        val validPixels = ByteArray(4)
        val badDims = listOf(0 to 1, 1 to 0, 1025 to 1, 1 to 1025, -1 to 2)
        for ((w, h) in badDims) {
            var threw = false
            try {
                RasterSnapshot(w, h, validPixels)
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("Expected IllegalArgumentException for RasterSnapshot($w, $h)", threw)
        }

        val badCases = listOf(
            { RasterSnapshot(2, 2, ByteArray(3)) },
            { RasterSnapshot(2, 2, ByteArray(5)) },
            { RasterSnapshot(2, 2, ByteArray(4), opaqueMetadata = ByteArray(15)) },
            { RasterSnapshot(2, 2, ByteArray(4), frameGeneration = -1L) }
        )
        for (call in badCases) {
            var threw = false
            try {
                call()
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("Expected IllegalArgumentException for invalid RasterSnapshot input", threw)
        }

        val validAux = HoslamTestFixtures.rawDecodeViaFramer(
            HoslamTestFixtures.buildValidFrame(HoslamFrameKind.AUX, byteArrayOf(1, 2, 3, 4))
        )
        var negGenThrew = false
        try {
            decoder.decode(validAux, frameGeneration = -1L)
        } catch (_: IllegalArgumentException) {
            negGenThrew = true
        }
        assertTrue("Expected IllegalArgumentException for negative frameGeneration", negGenThrew)
    }
}
