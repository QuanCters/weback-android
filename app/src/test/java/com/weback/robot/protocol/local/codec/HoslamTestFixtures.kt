package com.weback.robot.protocol.local.codec

import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.RawHoslamFrame
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
}
