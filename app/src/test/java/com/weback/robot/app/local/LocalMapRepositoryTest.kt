package com.weback.robot.app.local

import com.weback.robot.core.MonotonicClock
import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.LocalCodecError
import com.weback.robot.protocol.local.LocalTelemetryEvent
import com.weback.robot.protocol.local.LocalTelemetrySourceKind
import com.weback.robot.protocol.local.LocalTelemetryStreamSource
import com.weback.robot.protocol.local.SyntheticReplayTelemetrySource
import com.weback.robot.protocol.local.codec.HoslamTestFixtures
import com.weback.robot.testutils.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalMapRepositoryTest {

    private class MutableClock(var nowMs: Long = 1_000L) : MonotonicClock {
        override fun elapsedMs(): Long = nowMs
    }

    private class ControlledStreamSource(
        override val sourceKind: LocalTelemetrySourceKind = LocalTelemetrySourceKind.LIVE_LAN_READ_ONLY
    ) : LocalTelemetryStreamSource {
        val events = MutableSharedFlow<LocalTelemetryEvent>(extraBufferCapacity = 64)
        var closeCount: Int = 0
            private set

        override fun openStream(): Flow<LocalTelemetryEvent> = events

        override fun close() {
            closeCount++
        }
    }

    @Test
    fun syntheticValidMixedReplayDeliversFourMapsOneAuxOneTrajAndCleanEof() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)
        val clock = MutableClock(1_000L)
        val bytes = HoslamTestFixtures.loadResourceBytes("valid_mixed.bin")
        val source = SyntheticReplayTelemetrySource(
            fixtureBytesProvider = { bytes },
            expectedSha256Hex = SyntheticReplayTelemetrySource.DEFAULT_VALID_MIXED_SHA256,
            chunkSizeBytes = 44,
            stepDelayMs = 20L,
            ioDispatcher = dispatcher
        )
        val repo = LocalMapRepository(
            streamSource = source,
            dispatcherProvider = dispatchers,
            clock = clock
        )
        try {
            repo.startSession()
            advanceTimeBy(500L)
            runCurrent()

            val state = repo.state.value
            assertEquals(LocalTelemetrySourceKind.SYNTHETIC_REPLAY, state.sourceKind)
            assertEquals(LocalMapConnectionState.DISCONNECTED, state.connectionState)
            assertEquals(LocalMapTerminalReason.REMOTE_EOF, state.terminalReason)
            assertEquals(SyntheticReplayPlaybackState.COMPLETED, state.replayState)
            assertTrue(state.mapViewAccepted)
            assertEquals(4L, state.mapCount)
            assertEquals(1L, state.auxCount)
            assertEquals(1L, state.trajectoryCount)
            assertNotNull(state.currentSnapshot)
            assertEquals(1, state.currentSnapshot!!.width)
            assertEquals(1, state.currentSnapshot!!.height)
            assertEquals(
                "a8100ae6aa1940d0b663bb31cd466142ebbdbd5187131b92d93818987832eb89",
                state.currentSnapshot!!.rasterSha256
            )
            assertFalse("Disconnected EOF map must not be marked live", state.isLive)
            assertTrue("Disconnected EOF retained map must be marked stale", state.isStale)
        } finally {
            repo.close()
        }
    }

    @Test
    fun zeroMapCleanEofRejectsMapViewAcceptanceEvenWithAuxAndTrajectoryFrames() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)
        val auxFrame = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.AUX, ByteArray(4) { 0x11 })
        val trajFrame = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.TRAJECTORY, ByteArray(8) { 0x22 })
        val zeroMapStream = auxFrame + trajFrame

        val source = SyntheticReplayTelemetrySource(
            fixtureBytesProvider = { zeroMapStream },
            expectedSha256Hex = null,
            stepDelayMs = 0L,
            ioDispatcher = dispatcher
        )
        val repo = LocalMapRepository(
            streamSource = source,
            dispatcherProvider = dispatchers,
            clock = MutableClock(100L)
        )
        try {
            repo.startSession()
            runCurrent()

            val state = repo.state.value
            assertEquals(LocalMapConnectionState.DISCONNECTED, state.connectionState)
            assertEquals(LocalMapTerminalReason.EOF_BEFORE_FIRST_MAP, state.terminalReason)
            assertNull(state.currentSnapshot)
            assertEquals(0L, state.mapCount)
            assertEquals(1L, state.auxCount)
            assertEquals(1L, state.trajectoryCount)
            assertFalse("Zero-map stream MUST fail map-view acceptance even on clean EndOfStream", state.mapViewAccepted)
        } finally {
            repo.close()
        }
    }

    @Test
    fun auxAndTrajectoryNeverResetMapFreshnessOrRecoverFromStale_killsM11b() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)
        val clock = MutableClock(1_000L)
        val source = ControlledStreamSource()
        val repo = LocalMapRepository(
            streamSource = source,
            dispatcherProvider = dispatchers,
            clock = clock
        )

        val map1 = buildSyntheticMapFrame(width = 2, height = 2, fill = 0x40)
        val map2 = buildSyntheticMapFrame(width = 2, height = 2, fill = 0x90)
        val aux = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.AUX, ByteArray(4) { 0x05 })
        val traj = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.TRAJECTORY, ByteArray(16) { 0x07 })

        try {
            repo.startSession()
            runCurrent()
            source.events.emit(LocalTelemetryEvent.Connected())
            runCurrent()

            // Deliver valid MAP at t = 1_000 ms
            source.events.emit(LocalTelemetryEvent.BytesChunk(map1))
            runCurrent()
            assertEquals(LocalMapConnectionState.STREAMING, repo.state.value.connectionState)
            assertEquals(1_000L, repo.state.value.lastValidMapElapsedMs)
            assertEquals(0L, repo.state.value.mapAgeMs)

            // Deliver AUX at t = 5_000 ms -> auxCount increments; freshness MUST NOT reset
            clock.nowMs = 5_000L
            advanceTimeBy(4_000L)
            source.events.emit(LocalTelemetryEvent.BytesChunk(aux))
            runCurrent()
            assertEquals(1L, repo.state.value.auxCount)
            assertEquals(1_000L, repo.state.value.lastValidMapElapsedMs)
            assertEquals(4_000L, repo.state.value.mapAgeMs)
            assertEquals(LocalMapConnectionState.STREAMING, repo.state.value.connectionState)

            // Deliver TRAJECTORY at t = 9_000 ms -> trajectoryCount increments; freshness MUST NOT reset
            clock.nowMs = 9_000L
            advanceTimeBy(4_000L)
            source.events.emit(LocalTelemetryEvent.BytesChunk(traj))
            runCurrent()
            assertEquals(1L, repo.state.value.trajectoryCount)
            assertEquals(1_000L, repo.state.value.lastValidMapElapsedMs)
            assertEquals(8_000L, repo.state.value.mapAgeMs)

            // Advance past 10_000 ms map age boundary (no new MAP; even though AUX/TRAJ came at 5s/9s)
            clock.nowMs = 11_001L
            advanceTimeBy(2_001L)
            runCurrent()
            assertEquals(
                "Map older than 10s must transition to STALE despite recent AUX/TRAJ frames",
                LocalMapConnectionState.STALE,
                repo.state.value.connectionState
            )
            assertEquals(10_001L, repo.state.value.mapAgeMs)

            // Subsequent AUX and TRAJ while STALE MUST keep state STALE
            clock.nowMs = 12_500L
            advanceTimeBy(1_499L)
            source.events.emit(LocalTelemetryEvent.BytesChunk(aux))
            source.events.emit(LocalTelemetryEvent.BytesChunk(traj))
            runCurrent()
            assertEquals(LocalMapConnectionState.STALE, repo.state.value.connectionState)
            assertEquals(2L, repo.state.value.auxCount)
            assertEquals(2L, repo.state.value.trajectoryCount)
            assertEquals(1_000L, repo.state.value.lastValidMapElapsedMs)

            // Valid MAP recovers STALE -> STREAMING and resets mapAgeMs to 0
            clock.nowMs = 13_000L
            advanceTimeBy(500L)
            source.events.emit(LocalTelemetryEvent.BytesChunk(map2))
            runCurrent()
            assertEquals(LocalMapConnectionState.STREAMING, repo.state.value.connectionState)
            assertEquals(2L, repo.state.value.mapCount)
            assertEquals(13_000L, repo.state.value.lastValidMapElapsedMs)
            assertEquals(0L, repo.state.value.mapAgeMs)
        } finally {
            repo.close()
        }
    }

    @Test
    fun malformedStreamFailsWithoutPartialMapCorruptionAndEnforcesInFrameDeadline() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)
        val badChecksum = HoslamTestFixtures.loadResourceBytes("bad_checksum.bin")
        val badSource = SyntheticReplayTelemetrySource(
            fixtureBytesProvider = { badChecksum },
            expectedSha256Hex = null,
            stepDelayMs = 0L,
            ioDispatcher = dispatcher
        )
        val badRepo = LocalMapRepository(badSource, dispatchers, MutableClock(100L))
        try {
            badRepo.startSession()
            runCurrent()
            val state = badRepo.state.value
            assertEquals(LocalMapConnectionState.ERROR, state.connectionState)
            assertEquals(LocalMapTerminalReason.CODEC_ERROR, state.terminalReason)
            assertTrue(state.codecError is LocalCodecError.InvalidChecksum)
            assertNull(state.currentSnapshot)
            assertFalse(state.mapViewAccepted)
        } finally {
            badRepo.close()
        }

        // Absolute 15s in-frame deadline must not extend under partial-byte trickle
        val trickleClock = MonotonicClock { 1_000L + testScheduler.currentTime }
        val controlled = ControlledStreamSource()
        val trickleRepo = LocalMapRepository(controlled, dispatchers, trickleClock)
        val validMap = buildSyntheticMapFrame(width = 2, height = 2, fill = 0x55)
        try {
            trickleRepo.startSession()
            runCurrent()
            controlled.events.emit(LocalTelemetryEvent.Connected())
            runCurrent()

            for (step in 0 until 8) {
                controlled.events.emit(LocalTelemetryEvent.BytesChunk(byteArrayOf(validMap[step])))
                runCurrent()
                if (step < 7) {
                    advanceTimeBy(2_000L)
                } else {
                    advanceTimeBy(1_000L)
                }
                runCurrent()
            }
            assertEquals(LocalMapConnectionState.ERROR, trickleRepo.state.value.connectionState)
            assertEquals(LocalMapTerminalReason.IN_FRAME_TIMEOUT, trickleRepo.state.value.terminalReason)
            assertNull(trickleRepo.state.value.currentSnapshot)
        } finally {
            trickleRepo.close()
        }
    }

    @Test
    fun syntheticReplaySurvivesConfigChangeAndStopsLiveSourceCleanlyOnConfigChange() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)
        val validBytes = HoslamTestFixtures.loadResourceBytes("valid_mixed.bin")
        val synthSource = SyntheticReplayTelemetrySource(
            fixtureBytesProvider = { validBytes },
            expectedSha256Hex = SyntheticReplayTelemetrySource.DEFAULT_VALID_MIXED_SHA256,
            stepDelayMs = 20L,
            ioDispatcher = dispatcher
        )
        val synthRepo = LocalMapRepository(synthSource, dispatchers, MutableClock(1_000L))
        try {
            synthRepo.startSession()
            advanceTimeBy(500L)
            runCurrent()
            val snapshotBefore = synthRepo.state.value.currentSnapshot
            assertNotNull(snapshotBefore)
            synthRepo.onConfigurationChanged()
            assertEquals(LocalMapConnectionState.DISCONNECTED, synthRepo.state.value.connectionState)
            assertEquals(snapshotBefore, synthRepo.state.value.currentSnapshot)
        } finally {
            synthRepo.close()
        }

        val liveSource = ControlledStreamSource(LocalTelemetrySourceKind.LIVE_LAN_READ_ONLY)
        val liveRepo = LocalMapRepository(liveSource, dispatchers, MutableClock(1_000L))
        try {
            liveRepo.startSession()
            runCurrent()
            liveSource.events.emit(LocalTelemetryEvent.Connected())
            liveSource.events.emit(LocalTelemetryEvent.BytesChunk(buildSyntheticMapFrame(2, 2, 0x33)))
            runCurrent()
            assertEquals(LocalMapConnectionState.STREAMING, liveRepo.state.value.connectionState)

            liveRepo.onConfigurationChanged()
            runCurrent()
            assertEquals(LocalMapConnectionState.DISCONNECTED, liveRepo.state.value.connectionState)
            assertEquals(
                LocalMapTerminalReason.CONFIG_CHANGE_RECONNECT_REQUIRED,
                liveRepo.state.value.terminalReason
            )
            assertNull(liveRepo.state.value.currentSnapshot)
            assertEquals(1, liveSource.closeCount)
        } finally {
            liveRepo.close()
        }
    }

    private fun buildSyntheticMapFrame(width: Int, height: Int, fill: Int): ByteArray {
        val pixelCount = width * height
        val rawEnd = 24 + pixelCount // body offset: 16 opaque + 4 W + 4 H + W*H pixels
        val bodyAligned = (rawEnd + 3) and 3.inv()
        val body = ByteArray(bodyAligned)
        HoslamTestFixtures.writeU32Le(body, 16, width.toLong())
        HoslamTestFixtures.writeU32Le(body, 20, height.toLong())
        for (i in 0 until pixelCount) {
            body[24 + i] = fill.toByte()
        }
        return HoslamTestFixtures.buildValidFrame(HoslamFrameKind.MAP, body)
    }
}
