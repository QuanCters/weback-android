package com.weback.robot.ui.map

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.app.local.LocalMapConnectionState
import com.weback.robot.app.local.LocalMapRepository
import com.weback.robot.core.MonotonicClock
import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.RasterSnapshot
import com.weback.robot.protocol.local.LocalTelemetrySourceKind
import com.weback.robot.protocol.local.SyntheticReplayTelemetrySource
import com.weback.robot.protocol.local.codec.HoslamTestFixtures
import com.weback.robot.testutils.TestDispatcherProvider
import com.weback.robot.ui.theme.WeBackTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LocalReplayViewerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun grayscaleSnapshotMapsArgbUnsignedBytesCorrectly() {
        val pixels = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())
        val snapshot = RasterSnapshot(
            width = 2,
            height = 2,
            pixels = pixels,
            opaqueMetadata = ByteArray(16),
            frameGeneration = 1L
        )
        val expectedArgb = intArrayOf(
            0xFF000000.toInt(),
            0xFF7F7F7F.toInt(),
            0xFF808080.toInt(),
            0xFFFFFFFF.toInt()
        )
        assertArrayEquals(expectedArgb, snapshot.toArgbPixels())
    }

    @Test
    fun syntheticAssetReplaySupportsPlayPauseRestartAndVisualPanZoomReset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = SyntheticReplayTelemetrySource.fromAssetManager(
            assetManager = context.assets,
            chunkSizeBytes = 44,
            stepDelayMs = 80L
        )
        val repository = LocalMapRepository(streamSource = source)
        val viewModel = MapViewModel(repository = repository)
        try {
            composeRule.setContent {
                WeBackTheme(dynamicColor = false) {
                    MapScreen(
                        viewModel = viewModel,
                        onNavigateBack = {}
                    )
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("local_map_source_badge").assertTextContains("SYNTHETIC REPLAY", substring = true)

            // Play packaged valid_mixed.bin -> pause mid-replay -> resume -> finish 4 maps
            composeRule.onNodeWithTag("btn_replay_play").performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 3_000L) {
                repository.state.value.mapCount >= 1L
            }
            composeRule.onNodeWithTag("btn_replay_pause").performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("local_map_state_label").assertTextContains("PAUSED (SYNTHETIC REPLAY)")

            composeRule.onNodeWithTag("btn_replay_play").performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 3_000L) {
                repository.state.value.connectionState == LocalMapConnectionState.DISCONNECTED &&
                    repository.state.value.mapCount == 4L
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("local_map_counters_label")
                .assertTextContains("Maps: 4 | Aux: 1 | Trajectory (opaque counts only): 1")
            composeRule.onNodeWithTag("local_map_state_label")
                .assertTextContains("DISCONNECTED / STALE")
            composeRule.onNodeWithTag("map_canvas").performScrollTo().assertIsDisplayed()

            // Purely visual Zoom In, Pan, and Reset View
            composeRule.onNodeWithTag("map_viewport_label").assertTextContains("Zoom: 1.0x | Pan: (0, 0)")
            composeRule.onNodeWithTag("btn_map_zoom_in").performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("map_viewport_label").assertTextContains("Zoom: 1.5x", substring = true)
            composeRule.onNodeWithTag("btn_map_pan_right").performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("map_viewport_label").assertTextContains("Pan: (24, 0)", substring = true)
            composeRule.onNodeWithTag("btn_map_reset_view").performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("map_viewport_label").assertTextContains("Zoom: 1.0x | Pan: (0, 0)")

            // Restart replays cleanly from 0 through 4 maps again
            composeRule.onNodeWithTag("btn_replay_restart").performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 3_000L) {
                repository.state.value.connectionState == LocalMapConnectionState.DISCONNECTED &&
                    repository.state.value.mapCount == 4L &&
                    repository.state.value.frameGeneration >= 8L
            }
            composeRule.waitForIdle()
            assertEquals(4L, repository.state.value.mapCount)
        } finally {
            repository.close()
        }
    }

    @Test
    fun zeroMapCleanEofStreamFailsMapViewAcceptanceWithoutRenderingCanvas() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)
        val auxOnly = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.AUX, ByteArray(4) { 0x01 }) +
            HoslamTestFixtures.buildValidFrame(HoslamFrameKind.TRAJECTORY, ByteArray(8) { 0x02 })
        val source = SyntheticReplayTelemetrySource(
            fixtureBytesProvider = { auxOnly },
            expectedSha256Hex = null,
            stepDelayMs = 0L,
            ioDispatcher = dispatcher
        )
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val repository = LocalMapRepository(source, dispatchers, MonotonicClock { scheduler.currentTime })
        val viewModel = MapViewModel(repository, dispatchers, scope)
        try {
            composeRule.setContent {
                WeBackTheme(dynamicColor = false) {
                    MapScreen(viewModel = viewModel, onNavigateBack = {})
                }
            }
            composeRule.onNodeWithTag("btn_replay_play").performScrollTo().performClick()
            scheduler.advanceUntilIdle()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("local_map_state_label")
                .assertTextContains("DISCONNECTED — No valid map received")
            composeRule.onNodeWithTag("local_map_acceptance_status")
                .assertTextContains("Map View Not Accepted (0 valid maps received)")
            composeRule.onNodeWithTag("map_empty_placeholder").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("map_canvas").assertDoesNotExist()
            composeRule.onNodeWithTag("btn_replay_pause").assertIsNotEnabled()
            composeRule.onNodeWithTag("btn_replay_restart").assertIsEnabled()
        } finally {
            repository.close()
            scope.cancel()
        }
    }

    @Test
    fun malformedFixturesShowErrorWithoutPartialRenderAndPreservePriorValidMapOnlyWhenPriorExisted() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)
        val badChecksum = HoslamTestFixtures.loadResourceBytes("bad_checksum.bin")
        val badRepo = LocalMapRepository(
            streamSource = SyntheticReplayTelemetrySource(
                fixtureBytesProvider = { badChecksum },
                expectedSha256Hex = null,
                stepDelayMs = 0L,
                ioDispatcher = dispatcher
            ),
            dispatcherProvider = dispatchers,
            clock = MonotonicClock { scheduler.currentTime }
        )
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val badVm = MapViewModel(badRepo, dispatchers, scope)
        try {
            composeRule.setContent {
                WeBackTheme(dynamicColor = false) {
                    MapScreen(viewModel = badVm, onNavigateBack = {})
                }
            }
            composeRule.onNodeWithTag("btn_replay_play").performScrollTo().performClick()
            scheduler.advanceUntilIdle()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("local_map_state_label").assertTextContains("ERROR (INVALID_CHECKSUM)")
            composeRule.onNodeWithTag("local_map_acceptance_status").assertTextContains("Map View Failed (INVALID_CHECKSUM)")
            composeRule.onNodeWithTag("map_empty_placeholder").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("map_canvas").assertDoesNotExist()
        } finally {
            badRepo.close()
            scope.cancel()
        }

        // Valid first map followed by trailing corrupt frame retains valid map under ERROR / STALE
        val firstMapBody = ByteArray(28).apply {
            HoslamTestFixtures.writeU32Le(this, 16, 2L)
            HoslamTestFixtures.writeU32Le(this, 20, 2L)
            this[24] = 0x10
            this[25] = 0x20
            this[26] = 0x30
            this[27] = 0x40
        }
        val mixedWithTrailingError =
            HoslamTestFixtures.buildValidFrame(HoslamFrameKind.MAP, firstMapBody) + badChecksum
        val partialScheduler = TestCoroutineScheduler()
        val partialDispatcher = StandardTestDispatcher(partialScheduler)
        val partialDispatchers = TestDispatcherProvider(partialDispatcher)
        val partialRepo = LocalMapRepository(
            streamSource = SyntheticReplayTelemetrySource(
                fixtureBytesProvider = { mixedWithTrailingError },
                expectedSha256Hex = null,
                chunkSizeBytes = 256,
                stepDelayMs = 0L,
                ioDispatcher = partialDispatcher
            ),
            dispatcherProvider = partialDispatchers,
            clock = MonotonicClock { partialScheduler.currentTime }
        )
        try {
            partialRepo.startSession()
            partialScheduler.advanceUntilIdle()
            assertEquals(LocalMapConnectionState.ERROR, partialRepo.state.value.connectionState)
            assertEquals(1L, partialRepo.state.value.mapCount)
            assertEquals(
                LocalTelemetrySourceKind.SYNTHETIC_REPLAY,
                partialRepo.state.value.sourceKind
            )
            assertArrayEquals(
                byteArrayOf(0x10, 0x20, 0x30, 0x40),
                partialRepo.state.value.currentSnapshot?.copyPixels()
            )
        } finally {
            partialRepo.close()
        }
    }
}
