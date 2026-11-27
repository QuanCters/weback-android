package com.weback.robot.protocol.local.codec

import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.LocalCodecError
import com.weback.robot.domain.map.RawHoslamFrame

/**
 * Pure-JVM incremental HOSLAM binary envelope framer.
 *
 * Reassembles variable-chunked byte streams into checksum-verified [RawHoslamFrame] instances
 * using strictly bounded internal storage (one 12-byte header staging buffer and at most one
 * active frame buffer <= 2 MiB between [feed] calls) and fail-closed terminal error isolation.
 */
class HoslamStreamFramer {

    companion object {
        const val HEADER_BYTES: Int = 12
        const val CHECKSUM_BYTES: Int = 4
        const val MIN_FRAME_BYTES: Long = 16L
        const val MAX_FRAME_BYTES: Long = 2_097_152L

        private val MAGIC_MAP = byteArrayOf(0x68, 0x6F, 0x73, 0x6C, 0x6D, 0x61, 0x70, 0x00)
        private val MAGIC_AUX = byteArrayOf(0x68, 0x6F, 0x73, 0x6C, 0x63, 0x61, 0x63, 0x00)
        private val MAGIC_TRAJ = byteArrayOf(0x68, 0x6F, 0x73, 0x6C, 0x74, 0x72, 0x61, 0x6A)
    }

    private val headerBuffer = ByteArray(HEADER_BYTES)
    private var bufferedHeaderBytes: Int = 0

    private var activeFrameBuffer: ByteArray? = null
    private var activeFrameBytesRead: Int = 0
    private var activeFrameKind: HoslamFrameKind? = null

    private var bytesConsumed: Long = 0L
    private var currentFrameStartOffset: Long = 0L
    private var framesDelivered: Long = 0L
    private var terminalError: LocalCodecError? = null

    internal val bufferedHeaderByteCount: Int
        get() = bufferedHeaderBytes

    internal val activeFrameBufferCapacity: Int
        get() = activeFrameBuffer?.size ?: 0

    /**
     * Incrementally consumes `chunk[offset until offset + length]`, synchronously copying only
     * needed bytes without retaining a reference to [chunk].
     */
    fun feed(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size): HoslamFeedResult {
        require(offset >= 0 && length >= 0 && offset <= chunk.size && length <= chunk.size - offset) {
            "Invalid chunk slice: size=${chunk.size}, offset=$offset, length=$length"
        }

        val existingError = terminalError
        if (existingError != null) {
            return HoslamFeedResult.Failed(emptyList(), existingError)
        }
        if (length == 0) {
            return HoslamFeedResult.Frames(emptyList())
        }

        var completedFrames: MutableList<RawHoslamFrame>? = null
        var cursor = offset
        val end = offset + length

        while (cursor < end) {
            if (activeFrameBuffer == null) {
                if (bufferedHeaderBytes == 0) {
                    currentFrameStartOffset = bytesConsumed
                }
                cursor = consumeHeaderBytes(chunk, cursor, end)
                if (bufferedHeaderBytes == HEADER_BYTES) {
                    val headerError = validateAndPromoteHeader()
                    if (headerError != null) {
                        failWith(headerError)
                        return HoslamFeedResult.Failed(completedFrames?.toList() ?: emptyList(), headerError)
                    }
                }
            } else {
                cursor = consumeBodyBytes(chunk, cursor, end)
                val frameBuf = activeFrameBuffer
                if (frameBuf != null && activeFrameBytesRead == frameBuf.size) {
                    val failure = finalizeCompleteFrame(frameBuf) { frame ->
                        val list = completedFrames ?: mutableListOf<RawHoslamFrame>().also { completedFrames = it }
                        list.add(frame)
                    }
                    if (failure != null) {
                        failWith(failure)
                        return HoslamFeedResult.Failed(completedFrames?.toList() ?: emptyList(), failure)
                    }
                }
            }
        }

        return HoslamFeedResult.Frames(completedFrames?.toList() ?: emptyList())
    }

    /**
     * Signals EOF on the input stream. Returns [HoslamFinishResult.EndOfStream] only at a clean
     * frame boundary (including 0-byte streams) when no prior terminal failure has occurred.
     */
    fun finish(): HoslamFinishResult {
        val existingError = terminalError
        if (existingError != null) {
            return HoslamFinishResult.Failed(existingError)
        }

        val partialBody = activeFrameBuffer
        if (partialBody != null) {
            val err = LocalCodecError.TruncatedFrame(
                frameStartOffset = currentFrameStartOffset,
                bytesReadInFrame = activeFrameBytesRead
            )
            failWith(err)
            return HoslamFinishResult.Failed(err)
        }

        if (bufferedHeaderBytes > 0) {
            val err = LocalCodecError.TruncatedFrame(
                frameStartOffset = currentFrameStartOffset,
                bytesReadInFrame = bufferedHeaderBytes
            )
            failWith(err)
            return HoslamFinishResult.Failed(err)
        }

        return HoslamFinishResult.EndOfStream(
            framesDelivered = framesDelivered,
            bytesConsumed = bytesConsumed
        )
    }

    /**
     * Clears all internal staging/frame buffers, byte offsets, counts, and terminal error state
     * so a fresh session starts at offset `0L`.
     */
    fun reset() {
        clearStagingBuffers()
        bytesConsumed = 0L
        currentFrameStartOffset = 0L
        framesDelivered = 0L
        terminalError = null
    }

    private fun consumeHeaderBytes(chunk: ByteArray, cursor: Int, end: Int): Int {
        val needed = HEADER_BYTES - bufferedHeaderBytes
        val toCopy = minOf(needed, end - cursor)
        System.arraycopy(chunk, cursor, headerBuffer, bufferedHeaderBytes, toCopy)
        bufferedHeaderBytes += toCopy
        bytesConsumed += toCopy.toLong()
        return cursor + toCopy
    }

    private fun validateAndPromoteHeader(): LocalCodecError? {
        val kind = matchMagic(headerBuffer)
            ?: return LocalCodecError.InvalidMagic(currentFrameStartOffset)

        val declaredLength = HoslamByteUtils.readU32Le(headerBuffer, 8)
        if (declaredLength < MIN_FRAME_BYTES) {
            return LocalCodecError.InvalidLength(currentFrameStartOffset, declaredLength)
        }
        if (declaredLength > MAX_FRAME_BYTES) {
            return LocalCodecError.FrameTooLarge(currentFrameStartOffset, declaredLength)
        }

        // Safe Int conversion and bounded allocation only after Long length range is verified.
        val totalLength = declaredLength.toInt()
        val frameBuf = ByteArray(totalLength)
        System.arraycopy(headerBuffer, 0, frameBuf, 0, HEADER_BYTES)
        activeFrameBuffer = frameBuf
        activeFrameBytesRead = HEADER_BYTES
        activeFrameKind = kind
        bufferedHeaderBytes = 0
        return null
    }

    private fun consumeBodyBytes(chunk: ByteArray, cursor: Int, end: Int): Int {
        val frameBuf = activeFrameBuffer ?: return cursor
        val remaining = frameBuf.size - activeFrameBytesRead
        val toCopy = minOf(remaining, end - cursor)
        System.arraycopy(chunk, cursor, frameBuf, activeFrameBytesRead, toCopy)
        activeFrameBytesRead += toCopy
        bytesConsumed += toCopy.toLong()
        return cursor + toCopy
    }

    private inline fun finalizeCompleteFrame(
        frameBuf: ByteArray,
        onFrame: (RawHoslamFrame) -> Unit
    ): LocalCodecError? {
        val checksumOffset = frameBuf.size - CHECKSUM_BYTES
        val expectedChecksum = HoslamByteUtils.readU32Le(frameBuf, checksumOffset)
        val computedChecksum = HoslamByteUtils.computeAdditiveChecksum(frameBuf, checksumOffset)
        if (computedChecksum != expectedChecksum) {
            return LocalCodecError.InvalidChecksum(currentFrameStartOffset)
        }

        val kind = activeFrameKind ?: return LocalCodecError.InvalidMagic(currentFrameStartOffset)
        val validatedFrame = RawHoslamFrame(
            kind = kind,
            frameStartOffset = currentFrameStartOffset,
            rawFrameBytes = frameBuf
        )
        onFrame(validatedFrame)
        framesDelivered += 1L
        activeFrameBuffer = null
        activeFrameBytesRead = 0
        activeFrameKind = null
        currentFrameStartOffset = bytesConsumed
        return null
    }

    private fun failWith(error: LocalCodecError) {
        terminalError = error
        clearStagingBuffers()
    }

    private fun clearStagingBuffers() {
        headerBuffer.fill(0)
        bufferedHeaderBytes = 0
        activeFrameBuffer = null
        activeFrameBytesRead = 0
        activeFrameKind = null
    }

    private fun matchMagic(header: ByteArray): HoslamFrameKind? = when {
        matchesPrefix8(header, MAGIC_MAP) -> HoslamFrameKind.MAP
        matchesPrefix8(header, MAGIC_AUX) -> HoslamFrameKind.AUX
        matchesPrefix8(header, MAGIC_TRAJ) -> HoslamFrameKind.TRAJECTORY
        else -> null
    }

    private fun matchesPrefix8(candidate: ByteArray, expected: ByteArray): Boolean {
        for (i in 0 until 8) {
            if (candidate[i] != expected[i]) return false
        }
        return true
    }
}
