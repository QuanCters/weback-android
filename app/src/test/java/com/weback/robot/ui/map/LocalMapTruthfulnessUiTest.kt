package com.weback.robot.ui.map

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.app.local.LocalMapRepository
import com.weback.robot.core.DispatcherProvider
import com.weback.robot.core.MonotonicClock
import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.RasterSnapshot
import com.weback.robot.protocol.local.LocalTelemetryEvent
import com.weback.robot.protocol.local.LocalTelemetrySourceKind
import com.weback.robot.protocol.local.LocalTelemetryStreamSource
import com.weback.robot.protocol.local.SyntheticReplayTelemetrySource
import com.weback.robot.protocol.local.codec.HoslamTestFixtures
import com.weback.robot.testutils.TestDispatcherProvider
import com.weback.robot.ui.theme.WeBackTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LocalMapTruthfulnessUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class ControllableClock(var nowMs: Long = 1_000L) : MonotonicClock {
        override fun elapsedMs(): Long = nowMs
    }

    private class ControlledStreamSource(
        override val sourceKind: LocalTelemetrySourceKind = LocalTelemetrySourceKind.SYNTHETIC_REPLAY
    ) : LocalTelemetryStreamSource {
        val stream = MutableSharedFlow<LocalTelemetryEvent>(extraBufferCapacity = 32)
        override fun openStream(): Flow<LocalTelemetryEvent> = stream
        override fun close() = Unit
    }

    @Test
    fun fixedClockTruthfullyDisplaysLiveStaleErrorEofAndAccessibleSemantics() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val dispatchers: DispatcherProvider = TestDispatcherProvider(dispatcher)
        val clock = ControllableClock(1_000L)
        val source = ControlledStreamSource()
        val repository = LocalMapRepository(source, dispatchers, clock)
        val vmScope = CoroutineScope(SupervisorJob() + dispatcher)
        val viewModel = MapViewModel(repository, dispatchers, vmScope)

        val validMap = buildMapFrame(2, 2, 0x5A)
        val aux = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.AUX, ByteArray(4) { 0x10 })
        val traj = HoslamTestFixtures.buildValidFrame(HoslamFrameKind.TRAJECTORY, ByteArray(8) { 0x20 })

        try {
            composeRule.setContent {
                WeBackTheme(dynamicColor = false) {
                    MapScreen(viewModel = viewModel, onNavigateBack = {})
                }
            }

            composeRule.waitForIdle()
            composeRule.onNodeWithTag("local_map_state_label").assertTextContains("IDLE")
            composeRule.onNodeWithTag("local_map_a11y_summary").assert(
                SemanticsMatcher("has accessible summary") {
                    val desc = it.config[SemanticsProperties.ContentDescription].joinToString(" ")
                    desc.contains("IDLE")
                }
            )

            repository.startSession()
            scheduler.runCurrent()
            source.stream.tryEmit(LocalTelemetryEvent.Connected())
            scheduler.runCurrent()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("local_map_state_label").assertTextContains("WAITING FOR FIRST MAP")

            source.stream.tryEmit(LocalTelemetryEvent.BytesChunk(validMap))
            scheduler.runCurrent()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("local_map_state_label").assertTextContains("STREAMING (LIVE)")
            composeRule.onNodeWithTag("local_map_age_label").assertTextContains("Map Age: 0 ms", substring = true)

            clock.nowMs = 6_000L
            source.stream.tryEmit(LocalTelemetryEvent.BytesChunk(aux + traj))
            scheduler.runCurrent()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("local_map_age_label").assertTextContains("Map Age: 5000 ms", substring = true)
            composeRule.onNodeWithTag("local_map_counters_label")
                .assertTextContains("Maps: 1 | Aux: 1 | Trajectory (opaque counts only): 1")

            clock.nowMs = 11_500L
            scheduler.advanceTimeBy(10_500L)
            scheduler.runCurrent()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("local_map_state_label").assertTextContains("STALE")
            composeRule.onNodeWithTag("local_map_age_label").assertTextContains("Map Age: 10500 ms", substring = true)

            source.stream.tryEmit(LocalTelemetryEvent.EndOfStream)
            scheduler.runCurrent()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("local_map_state_label").assertTextContains("DISCONNECTED / STALE")
            composeRule.onNodeWithTag("local_map_a11y_summary").assert(
                SemanticsMatcher("describes disconnected stale accessibility summary") {
                    val desc = it.config[SemanticsProperties.ContentDescription].joinToString(" ")
                    desc.contains("DISCONNECTED / STALE") && !desc.contains("LIVE")
                }
            )
        } finally {
            repository.close()
            vmScope.cancel()
        }
    }

    @Test
    fun unsupportedCapabilitiesDisabledAndVisiblyLabeledUnsupported() {
        val repository = LocalMapRepository(
            SyntheticReplayTelemetrySource(
                fixtureBytesProvider = { byteArrayOf() },
                expectedSha256Hex = null
            )
        )
        val viewModel = MapViewModel(repository)
        try {
            composeRule.setContent {
                WeBackTheme(dynamicColor = false) {
                    MapScreen(viewModel = viewModel, onNavigateBack = {})
                }
            }
            composeRule.waitForIdle()

            listOf(
                "btn_local_unsupported_clean",
                "btn_local_unsupported_pause",
                "btn_local_unsupported_resume",
                "btn_local_unsupported_dock",
                "btn_local_unsupported_locate"
            ).forEach { tag ->
                composeRule.onNodeWithTag(tag)
                    .performScrollTo()
                    .assertIsNotEnabled()
                    .assertTextContains("UNSUPPORTED", substring = true)
            }

            composeRule.onNodeWithTag("unsupported_telemetry_row")
                .performScrollTo().assertIsDisplayed().assertTextContains("UNSUPPORTED", substring = true)
            composeRule.onNodeWithTag("unsupported_overlay_row")
                .performScrollTo().assertIsDisplayed().assertTextContains("UNSUPPORTED", substring = true)
            composeRule.onNodeWithTag("unsupported_rooms_row")
                .performScrollTo().assertIsDisplayed().assertTextContains("UNSUPPORTED", substring = true)
            composeRule.onNodeWithTag("unsupported_firmware_row")
                .performScrollTo().assertIsDisplayed().assertTextContains("UNSUPPORTED", substring = true)
        } finally {
            repository.close()
        }
    }

    @Test
    fun mapCanvasCachesSingleBitmapPerFrameGenerationAcrossZoomAndPan() {
        val bitmapCreations = mutableListOf<Long>()
        val snapshot1 = RasterSnapshot(
            width = 2,
            height = 2,
            pixels = byteArrayOf(10, 20, 30, 40),
            opaqueMetadata = ByteArray(16),
            frameGeneration = 1L
        )
        composeRule.setContent {
            WeBackTheme(dynamicColor = false) {
                MapCanvas(
                    snapshot = snapshot1,
                    onBitmapCreatedForGeneration = { bitmapCreations.add(it) }
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(listOf(1L), bitmapCreations)

        composeRule.onNodeWithTag("btn_map_zoom_in").performClick()
        composeRule.onNodeWithTag("btn_map_pan_right").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("map_viewport_label").assertTextContains("Zoom: 1.5x | Pan: (24, 0)")
        assertEquals("Recomposition or viewport controls must reuse cached bitmap", listOf(1L), bitmapCreations)
    }

    @Test
    fun errorAfterValidMapDisplaysErrorStaleWithoutLiveClaim() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)
        val source = ControlledStreamSource()
        val repository = LocalMapRepository(source, dispatchers, ControllableClock(1_000L))
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val viewModel = MapViewModel(repository, dispatchers, scope)
        try {
            composeRule.setContent {
                WeBackTheme(dynamicColor = false) {
                    MapScreen(viewModel = viewModel, onNavigateBack = {})
                }
            }
            repository.startSession()
            scheduler.runCurrent()
            source.stream.tryEmit(LocalTelemetryEvent.Connected())
            source.stream.tryEmit(LocalTelemetryEvent.BytesChunk(buildMapFrame(2, 2, 0x33)))
            source.stream.tryEmit(
                LocalTelemetryEvent.BytesChunk(HoslamTestFixtures.loadResourceBytes("bad_checksum.bin"))
            )
            scheduler.runCurrent()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("local_map_state_label")
                .assertTextContains("ERROR / STALE (INVALID_CHECKSUM)")
            composeRule.onNodeWithTag("map_canvas").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("local_map_a11y_summary").assert(
                SemanticsMatcher("must not report LIVE on ERROR / STALE") {
                    val desc = it.config[SemanticsProperties.ContentDescription].joinToString(" ")
                    desc.contains("ERROR / STALE (INVALID_CHECKSUM)") && !desc.contains("LIVE")
                }
            )
        } finally {
            repository.close()
            scope.cancel()
        }
    }

    private fun buildMapFrame(width: Int, height: Int, fill: Int): ByteArray {
        val pixelCount = width * height
        val rawEnd = 24 + pixelCount
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
