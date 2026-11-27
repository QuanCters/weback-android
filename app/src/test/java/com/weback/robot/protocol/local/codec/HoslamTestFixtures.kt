package com.weback.robot.protocol.local.codec

import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.RawHoslamFrame
import java.io.File
import java.security.MessageDigest

/**
 * Independent test-only fixture builder and verifier for HOSLAM framing tests.
 * Never delegates to production codec utilities so test oracles remain independent.
 */
internal object HoslamTestFixtures {

    val MAGIC_MAP: ByteArray = byteArrayOf(0x68, 0x6F, 0x73, 0x6C, 0x6D, 0x61, 0x70, 0x00)
    val MAGIC_AUX: ByteArray = byteArrayOf(0x68, 0x6F, 0x73, 0x6C, 0x63, 0x61, 0x63, 0x00)
    val MAGIC_TRAJ: ByteArray = byteArrayOf(0x68, 0x6F, 0x73, 0x6C, 0x74, 0x72, 0x61, 0x6A)

    fun magicFor(kind: HoslamFrameKind): ByteArray = when (kind) {
        HoslamFrameKind.MAP -> MAGIC_MAP.copyOf()
        HoslamFrameKind.AUX -> MAGIC_AUX.copyOf()
        HoslamFrameKind.TRAJECTORY -> MAGIC_TRAJ.copyOf()
    }

    fun writeU32Le(target: ByteArray, offset: Int, value: Long) {
        target[offset] = (value and 0xFFL).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFFL).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFFL).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFFL).toByte()
    }

    fun independentSumMod2Pow32(bytes: ByteArray, endExclusive: Int = bytes.size): Long {
        var total = 0L
        for (i in 0 until endExclusive) {
            total += bytes[i].toInt() and 0xFF
        }
        return total and 0xFFFF_FFFFL
    }

    fun buildValidFrame(
        kind: HoslamFrameKind,
        body: ByteArray
    ): ByteArray {
        val totalLength = 12 + body.size + 4
        val frame = ByteArray(totalLength)
        val magic = magicFor(kind)
        System.arraycopy(magic, 0, frame, 0, 8)
        writeU32Le(frame, 8, totalLength.toLong())
        System.arraycopy(body, 0, frame, 12, body.size)
        val checksum = independentSumMod2Pow32(frame, totalLength - 4)
        writeU32Le(frame, totalLength - 4, checksum)
        return frame
    }

    fun buildHeaderOnly(magic: ByteArray, declaredLength: Long): ByteArray {
        require(magic.size == 8) { "Magic must be 8 bytes" }
        val header = ByteArray(12)
        System.arraycopy(magic, 0, header, 0, 8)
        writeU32Le(header, 8, declaredLength)
        return header
    }

    fun loadResourceBytes(fileName: String): ByteArray {
        val stream = HoslamTestFixtures::class.java.classLoader
            ?.getResourceAsStream("hoslam/$fileName")
            ?: error("Missing test resource hoslam/$fileName")
        return stream.use { it.readBytes() }
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    fun feedAll(framer: HoslamStreamFramer, vararg chunks: ByteArray): Pair<List<RawHoslamFrame>, HoslamFeedResult.Failed?> {
        val emitted = mutableListOf<RawHoslamFrame>()
        for (chunk in chunks) {
            when (val res = framer.feed(chunk)) {
                is HoslamFeedResult.Frames -> emitted.addAll(res.frames)
                is HoslamFeedResult.Failed -> {
                    emitted.addAll(res.validFramesBeforeFailure)
                    return emitted to res
                }
            }
        }
        return emitted to null
    }

    fun decodeStreamFrames(vararg chunks: ByteArray): Pair<List<com.weback.robot.domain.map.RawHoslamFrame>, HoslamFinishResult> {
        val framer = HoslamStreamFramer()
        val (frames, failure) = feedAll(framer, *chunks)
        if (failure != null) {
            error("Unexpected framer failure during test setup: ${failure.error}")
        }
        val finish = framer.finish()
        if (finish is HoslamFinishResult.Failed) {
            error("Unexpected framer EOF failure during test setup: ${finish.error}")
        }
        return frames to finish
    }

    fun buildMapFrameBytes(
        width: Long,
        height: Long,
        pixels: ByteArray,
        metadata: ByteArray = ByteArray(16),
        overridePadding: ByteArray? = null,
        overrideTotalLength: Long? = null
    ): ByteArray {
        require(metadata.size == 16) { "Metadata must be 16 bytes" }
        val pixelLen = pixels.size
        val end = 36 + pixelLen
        val aligned = (end + 3) and 3.inv()
        val padLen = aligned - end
        val actualPad = overridePadding ?: ByteArray(padLen)
        val totalLength = overrideTotalLength?.toInt() ?: (aligned + 4)
        require(totalLength >= 16) { "Envelope test helper requires totalLength >= 16, got $totalLength" }

        val frame = ByteArray(totalLength)
        System.arraycopy(MAGIC_MAP, 0, frame, 0, 8)
        writeU32Le(frame, 8, totalLength.toLong())
        if (totalLength > 16) {
            val payloadSpace = totalLength - 16
            val rawBody = ByteArray(24 + pixelLen + actualPad.size)
            System.arraycopy(metadata, 0, rawBody, 0, 16)
            writeU32Le(rawBody, 16, width)
            writeU32Le(rawBody, 20, height)
            System.arraycopy(pixels, 0, rawBody, 24, pixelLen)
            System.arraycopy(actualPad, 0, rawBody, 24 + pixelLen, actualPad.size)
            val copyCount = minOf(payloadSpace, rawBody.size)
            System.arraycopy(rawBody, 0, frame, 12, copyCount)
        }
        val checksum = independentSumMod2Pow32(frame, totalLength - 4)
        writeU32Le(frame, totalLength - 4, checksum)
        return frame
    }

    fun buildEnvelopeRawFrame(
        kind: HoslamFrameKind,
        body: ByteArray,
        frameStartOffset: Long = 0L
    ): RawHoslamFrame {
        val bytes = buildValidFrame(kind, body)
        return RawHoslamFrame(kind, frameStartOffset, bytes)
    }

    fun rawDecodeViaFramer(frameBytes: ByteArray): RawHoslamFrame {
        val framer = HoslamStreamFramer()
        val feedRes = framer.feed(frameBytes)
        if (feedRes !is HoslamFeedResult.Frames || feedRes.frames.size != 1) {
            error("Expected single envelope-valid RawHoslamFrame, got $feedRes")
        }
        val finRes = framer.finish()
        if (finRes !is HoslamFinishResult.EndOfStream) {
            error("Expected clean EndOfStream, got $finRes")
        }
        return feedRes.frames.single()
    }

    fun findProhibitedCodeTokens(searchDirs: List<File>, prohibitedTokens: List<String>): List<String> {
        val violations = mutableListOf<String>()
        for (dir in searchDirs) {
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                var inBlockComment = false
                file.readLines().forEachIndexed { idx, line ->
                    val codeOnly = StringBuilder()
                    var i = 0
                    while (i < line.length) {
                        if (inBlockComment) {
                            if (i + 1 < line.length && line[i] == '*' && line[i + 1] == '/') {
                                inBlockComment = false
                                i += 2
                            } else {
                                i++
                            }
                        } else if (i + 1 < line.length && line[i] == '/' && line[i + 1] == '*') {
                            inBlockComment = true
                            i += 2
                        } else if (i + 1 < line.length && line[i] == '/' && line[i + 1] == '/') {
                            break
                        } else {
                            codeOnly.append(line[i])
                            i++
                        }
                    }
                    val trimmed = codeOnly.toString().trim()
                    if (trimmed.isEmpty()) return@forEachIndexed
                    for (token in prohibitedTokens) {
                        if (trimmed.contains(token)) {
                            violations.add("${file.name}:${idx + 1}: $trimmed")
                        }
                    }
                }
            }
        }
        return violations
    }
}
