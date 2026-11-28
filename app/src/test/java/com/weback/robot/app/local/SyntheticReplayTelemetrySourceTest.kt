package com.weback.robot.app.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.protocol.local.LocalStreamFailureCode
import com.weback.robot.protocol.local.LocalTelemetryEvent
import com.weback.robot.protocol.local.LocalTelemetrySourceKind
import com.weback.robot.protocol.local.LocalTelemetryStreamSource
import com.weback.robot.protocol.local.SyntheticReplayTelemetrySource
import com.weback.robot.protocol.local.codec.HoslamTestFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SyntheticReplayTelemetrySourceTest {

    @Test
    fun streamInterfaceExposesZeroWriteOrCommandMethods() {
        val methods = LocalTelemetryStreamSource::class.java.declaredMethods.map { it.name }
        assertTrue(methods.none {
            it.contains("send", ignoreCase = true) ||
                it.contains("write", ignoreCase = true) ||
                it.contains("output", ignoreCase = true) ||
                it.contains("command", ignoreCase = true)
        })
        assertFalse(LocalTelemetryStreamSource::class.java.methods.any { it.name == "sendCommand" })
    }

    @Test
    fun packagedAssetStreamsExactValidMixedBytesWithDefensiveCopies() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = SyntheticReplayTelemetrySource.fromAssetManager(
            assetManager = context.assets,
            stepDelayMs = 10L,
            ioDispatcher = dispatcher
        )
        assertEquals(LocalTelemetrySourceKind.SYNTHETIC_REPLAY, source.sourceKind)

        val events = mutableListOf<LocalTelemetryEvent>()
        val collectJob = async {
            source.openStream().toList(events)
        }
        advanceTimeBy(1_000L)
        runCurrent()
        collectJob.await()

        assertTrue(events.firstOrNull() is LocalTelemetryEvent.Connected)
        assertTrue(events.lastOrNull() is LocalTelemetryEvent.EndOfStream)
        val chunks = events.filterIsInstance<LocalTelemetryEvent.BytesChunk>()
        assertTrue(chunks.isNotEmpty())

        val firstChunkCopy = chunks.first().copyBytes()
        if (firstChunkCopy.isNotEmpty()) {
            firstChunkCopy[0] = (firstChunkCopy[0].toInt() xor 0xFF).toByte()
        }
        val reassembled = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk.copyBytes() }
        val expected = HoslamTestFixtures.loadResourceBytes("valid_mixed.bin")
        assertArrayEquals(expected, reassembled)
        assertEquals(
            "631aa7fabc331da5b9245a193a4f00c0a242096e487db96dfca2707835a46fe5",
            HoslamTestFixtures.sha256Hex(reassembled)
        )
    }

    @Test
    fun pauseAndResumeGateMidStreamEmissionDeterministically() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val expected = HoslamTestFixtures.loadResourceBytes("valid_mixed.bin")
        val source = SyntheticReplayTelemetrySource(
            fixtureBytesProvider = { expected },
            expectedSha256Hex = SyntheticReplayTelemetrySource.DEFAULT_VALID_MIXED_SHA256,
            chunkSizeBytes = 44,
            stepDelayMs = 50L,
            ioDispatcher = dispatcher
        )

        val emitted = mutableListOf<LocalTelemetryEvent>()
        val collector = async {
            source.openStream().toList(emitted)
        }
        runCurrent()
        assertTrue("Expected Connected + first chunk before pause", emitted.size >= 2)
        val pausedAtCount = emitted.count { it is LocalTelemetryEvent.BytesChunk }
        assertTrue("Expected partial stream before pause", pausedAtCount < 5)

        source.pause()
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(
            "Paused replay must not advance chunks while paused",
            pausedAtCount,
            emitted.count { it is LocalTelemetryEvent.BytesChunk }
        )

        source.resume()
        advanceTimeBy(1_000L)
        runCurrent()
        collector.await()

        assertEquals(5, emitted.count { it is LocalTelemetryEvent.BytesChunk })
        assertTrue(emitted.last() is LocalTelemetryEvent.EndOfStream)
    }

    @Test
    fun shaMismatchOrMissingAssetEmitsTypedAssetLoadFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tampered = SyntheticReplayTelemetrySource(
            fixtureBytesProvider = { byteArrayOf(1, 2, 3, 4) },
            expectedSha256Hex = SyntheticReplayTelemetrySource.DEFAULT_VALID_MIXED_SHA256,
            ioDispatcher = dispatcher
        )
        val tamperedEvents = tampered.openStream().take(2).toList()
        assertEquals(
            listOf(LocalTelemetryEvent.StreamFailure(LocalStreamFailureCode.ASSET_LOAD_FAILED)),
            tamperedEvents
        )

        val nullAssets = SyntheticReplayTelemetrySource.fromAssetManager(
            assetManager = null,
            ioDispatcher = dispatcher
        )
        val missingEvents = nullAssets.openStream().take(2).toList()
        assertEquals(
            listOf(LocalTelemetryEvent.StreamFailure(LocalStreamFailureCode.ASSET_LOAD_FAILED)),
            missingEvents
        )
    }
}
