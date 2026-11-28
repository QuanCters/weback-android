package com.weback.robot.protocol.local

import android.content.res.AssetManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.security.MessageDigest

/**
 * Offline read-only synthetic HOSLAM fixture stream source (`SYNTHETIC REPLAY`).
 *
 * Reads strictly offline packaged synthetic fixture bytes without network sockets or automatic
 * fallback to simulator or cloud pilot transports. Supports explicit pause, resume, and restart.
 */
class SyntheticReplayTelemetrySource(
    private val fixtureBytesProvider: () -> ByteArray,
    private val expectedSha256Hex: String? = DEFAULT_VALID_MIXED_SHA256,
    private val chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE_BYTES,
    private val stepDelayMs: Long = DEFAULT_STEP_DELAY_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : LocalTelemetryStreamSource {

    init {
        require(chunkSizeBytes > 0) { "chunkSizeBytes must be positive, got $chunkSizeBytes" }
        require(stepDelayMs >= 0L) { "stepDelayMs must be non-negative, got $stepDelayMs" }
    }

    override val sourceKind: LocalTelemetrySourceKind = LocalTelemetrySourceKind.SYNTHETIC_REPLAY

    private val _paused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _paused.asStateFlow()

    fun pause() {
        _paused.value = true
    }

    fun resume() {
        _paused.value = false
    }

    fun resetPauseForReplay() {
        _paused.value = false
    }

    override fun close() {
        _paused.value = false
    }

    override fun openStream(): Flow<LocalTelemetryEvent> = flow {
        val rawBytes = try {
            fixtureBytesProvider()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: IOException) {
            emit(LocalTelemetryEvent.StreamFailure(LocalStreamFailureCode.ASSET_LOAD_FAILED))
            return@flow
        } catch (_: IllegalStateException) {
            emit(LocalTelemetryEvent.StreamFailure(LocalStreamFailureCode.ASSET_LOAD_FAILED))
            return@flow
        } catch (_: SecurityException) {
            emit(LocalTelemetryEvent.StreamFailure(LocalStreamFailureCode.ASSET_LOAD_FAILED))
            return@flow
        }

        if (expectedSha256Hex != null) {
            val actualDigest = sha256Hex(rawBytes)
            if (!actualDigest.equals(expectedSha256Hex, ignoreCase = true)) {
                emit(LocalTelemetryEvent.StreamFailure(LocalStreamFailureCode.ASSET_LOAD_FAILED))
                return@flow
            }
        }

        emit(LocalTelemetryEvent.Connected(peerVerified = true))

        var offset = 0
        var chunkIndex = 0
        while (offset < rawBytes.size) {
            _paused.first { !it }
            if (chunkIndex > 0 && stepDelayMs > 0L) {
                delay(stepDelayMs)
                _paused.first { !it }
            }
            val end = minOf(offset + chunkSizeBytes, rawBytes.size)
            emit(LocalTelemetryEvent.BytesChunk(rawBytes.copyOfRange(offset, end)))
            offset = end
            chunkIndex++
        }

        _paused.first { !it }
        emit(LocalTelemetryEvent.EndOfStream)
    }.flowOn(ioDispatcher)

    companion object {
        const val DEFAULT_ASSET_PATH: String = "synthetic_fixtures/valid_mixed.bin"
        const val DEFAULT_MANIFEST_PATH: String = "synthetic_fixtures/manifest.json"
        const val DEFAULT_VALID_MIXED_SHA256: String =
            "631aa7fabc331da5b9245a193a4f00c0a242096e487db96dfca2707835a46fe5"
        const val DEFAULT_CHUNK_SIZE_BYTES: Int = 44
        const val DEFAULT_STEP_DELAY_MS: Long = 40L

        fun fromAssetManager(
            assetManager: AssetManager?,
            assetPath: String = DEFAULT_ASSET_PATH,
            expectedSha256Hex: String? = DEFAULT_VALID_MIXED_SHA256,
            chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE_BYTES,
            stepDelayMs: Long = DEFAULT_STEP_DELAY_MS,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): SyntheticReplayTelemetrySource {
            return SyntheticReplayTelemetrySource(
                fixtureBytesProvider = {
                    val manager = assetManager
                        ?: throw IllegalStateException("Android AssetManager is unavailable")
                    manager.open(assetPath).use { input -> input.readBytes() }
                },
                expectedSha256Hex = expectedSha256Hex,
                chunkSizeBytes = chunkSizeBytes,
                stepDelayMs = stepDelayMs,
                ioDispatcher = ioDispatcher
            )
        }

        private fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
