package com.weback.robot.ui.map

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weback.robot.MainActivity
import com.weback.robot.WeBackApplication
import com.weback.robot.app.local.LocalMapConnectionState
import com.weback.robot.core.AppContainerDependency
import com.weback.robot.core.DefaultPilotContainer
import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.CommandRejectionReason
import com.weback.robot.domain.model.CommandResult
import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.model.ConnectivityError
import com.weback.robot.domain.model.DeviceInventoryItem
import com.weback.robot.domain.model.RobotCommand
import com.weback.robot.domain.model.RobotState
import com.weback.robot.domain.model.SessionCredentials
import com.weback.robot.domain.pilot.PilotModeState
import com.weback.robot.protocol.RobotTransport
import com.weback.robot.protocol.auth.AuthGateway
import com.weback.robot.protocol.inventory.DeviceInventoryGateway
import com.weback.robot.protocol.inventory.InventoryFetchResult
import com.weback.robot.protocol.local.LocalTelemetryStreamSource
import com.weback.robot.protocol.local.SyntheticReplayTelemetrySource
import com.weback.robot.protocol.local.codec.HoslamTestFixtures
import com.weback.robot.protocol.ws.RealtimeSessionTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class LocalMapActivityNavigationTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private class SpyRobotTransport : RobotTransport, AutoCloseable {
        var sendCommandCount: Int = 0
        private val _state = MutableStateFlow(RobotState())
        override val robotState: StateFlow<RobotState> = _state.asStateFlow()
        override suspend fun sendCommand(command: RobotCommand): CommandResult {
            sendCommandCount++
            return CommandResult.Rejected(CommandRejectionReason.TRANSPORT_ERROR, "Spy", 0L)
        }
        override fun close() = Unit
    }

    private class SpyAuthGateway : AuthGateway {
        var signInCount: Int = 0
        override suspend fun signIn(credentials: AuthCredentials): AuthResult {
            signInCount++
            error("Unexpected fallback to AuthGateway.signIn")
        }
    }

    private class SpyInventoryGateway : DeviceInventoryGateway {
        var fetchThingsCount: Int = 0
        override suspend fun fetchThings(session: SessionCredentials): InventoryFetchResult {
            fetchThingsCount++
            error("Unexpected fallback to DeviceInventoryGateway.fetchThings")
        }
    }

    private class SpyRealtimeTransport : RealtimeSessionTransport {
        var connectCount: Int = 0
        var sendOutboundCount: Int = 0
        private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected(null))
        private val _robotState = MutableStateFlow(RobotState())
        override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
        override val robotState: StateFlow<RobotState> = _robotState.asStateFlow()
        override fun connect(session: SessionCredentials, epoch: Long) {
            connectCount++
            error("Unexpected fallback to RealtimeSessionTransport.connect")
        }
        override fun sendOutbound(payload: String, epoch: Long): Boolean {
            sendOutboundCount++
            return false
        }
        override fun selectDevice(item: DeviceInventoryItem?, epoch: Long) = Unit
        override fun invalidateForEpoch(expectedEpoch: Long, reason: ConnectivityError) = Unit
        override fun currentGeneration(): Long = 0L
        override fun close() = Unit
    }

    private data class OracleSnapshot(
        val robotProviderCount: Int,
        val authProviderCount: Int,
        val inventoryProviderCount: Int,
        val realtimeProviderCount: Int,
        val localSourceProviderCount: Int,
        val resolvedCounts: Map<AppContainerDependency, Int>,
        val sendCommandCount: Int,
        val signInCount: Int,
        val fetchThingsCount: Int,
        val connectCount: Int,
        val sendOutboundCount: Int
    ) {
        fun resolvedCount(dep: AppContainerDependency): Int = resolvedCounts[dep] ?: 0
    }

    private class ScenarioOracleRecorder {
        val spyRobot = SpyRobotTransport()
        val spyAuth = SpyAuthGateway()
        val spyInventory = SpyInventoryGateway()
        val spyRealtime = SpyRealtimeTransport()

        val robotProviderCount = AtomicInteger(0)
        val authProviderCount = AtomicInteger(0)
        val inventoryProviderCount = AtomicInteger(0)
        val realtimeProviderCount = AtomicInteger(0)
        val localSourceProviderCount = AtomicInteger(0)
        private val resolvedCounts = ConcurrentHashMap<AppContainerDependency, AtomicInteger>().apply {
            AppContainerDependency.values().forEach { put(it, AtomicInteger(0)) }
        }

        fun recordResolution(dep: AppContainerDependency) {
            resolvedCounts.getValue(dep).incrementAndGet()
        }

        fun buildContainer(
            context: WeBackApplication,
            localStreamSourceFactory: (() -> LocalTelemetryStreamSource)? = null
        ): DefaultPilotContainer {
            return DefaultPilotContainer(
                context = context,
                authGatewayProvider = {
                    authProviderCount.incrementAndGet()
                    spyAuth
                },
                inventoryGatewayProvider = {
                    inventoryProviderCount.incrementAndGet()
                    spyInventory
                },
                realtimeTransportProvider = {
                    realtimeProviderCount.incrementAndGet()
                    spyRealtime
                },
                robotTransportProvider = {
                    robotProviderCount.incrementAndGet()
                    spyRobot
                },
                localStreamSourceProvider = localStreamSourceFactory?.let { factory ->
                    {
                        localSourceProviderCount.incrementAndGet()
                        factory()
                    }
                },
                dependencyResolutionObserver = ::recordResolution
            )
        }

        fun snapshot(): OracleSnapshot = OracleSnapshot(
            robotProviderCount = robotProviderCount.get(),
            authProviderCount = authProviderCount.get(),
            inventoryProviderCount = inventoryProviderCount.get(),
            realtimeProviderCount = realtimeProviderCount.get(),
            localSourceProviderCount = localSourceProviderCount.get(),
            resolvedCounts = resolvedCounts.mapValues { it.value.get() },
            sendCommandCount = spyRobot.sendCommandCount,
            signInCount = spyAuth.signInCount,
            fetchThingsCount = spyInventory.fetchThingsCount,
            connectCount = spyRealtime.connectCount,
            sendOutboundCount = spyRealtime.sendOutboundCount
        )

        fun assertStartupBaseline(phaseLabel: String, baseline: OracleSnapshot, container: DefaultPilotContainer) {
            assertEquals("$phaseLabel: robotProviderCount startup baseline", 1, baseline.robotProviderCount)
            assertTrue(
                "$phaseLabel: expected >= 1 initial MainActivity/Dashboard ROBOT_TRANSPORT resolution",
                baseline.resolvedCount(AppContainerDependency.ROBOT_TRANSPORT) >= 1
            )
            assertEquals("$phaseLabel: authProviderCount startup baseline", 0, baseline.authProviderCount)
            assertEquals("$phaseLabel: inventoryProviderCount startup baseline", 0, baseline.inventoryProviderCount)
            assertEquals("$phaseLabel: realtimeProviderCount startup baseline", 0, baseline.realtimeProviderCount)
            assertEquals(0, baseline.resolvedCount(AppContainerDependency.AUTH_GATEWAY))
            assertEquals(0, baseline.resolvedCount(AppContainerDependency.INVENTORY_GATEWAY))
            assertEquals(0, baseline.resolvedCount(AppContainerDependency.REALTIME_TRANSPORT))
            assertEquals(0, baseline.resolvedCount(AppContainerDependency.SESSION_COORDINATOR))
            assertEquals(0, baseline.resolvedCount(AppContainerDependency.INVENTORY_REPOSITORY))
            assertEquals(0, baseline.sendCommandCount)
            assertEquals(0, baseline.signInCount)
            assertEquals(0, baseline.fetchThingsCount)
            assertEquals(0, baseline.connectCount)
            assertEquals(0, baseline.sendOutboundCount)
            assertFalse(container.pilotAuthorizationGate.isAuthorized)
            assertFalse(container.offlineMockAuthorizationGate.isAuthorized)
            assertEquals(PilotModeState.OfflineDefault, container.pilotSessionController.pilotModeState.value)
            assertEquals(PilotModeState.OfflineDefault, container.offlineMockPilotController.pilotModeState.value)
        }

        fun assertZeroFallbackDelta(
            phaseLabel: String,
            before: OracleSnapshot,
            after: OracleSnapshot,
            container: DefaultPilotContainer,
            expectedRecreateRobotTransportResolutions: Int = 0
        ) {
            assertEquals(
                "$phaseLabel: unexpected robotTransportProvider delta",
                0,
                after.robotProviderCount - before.robotProviderCount
            )
            assertEquals(
                "$phaseLabel: unexpected authGatewayProvider delta",
                0,
                after.authProviderCount - before.authProviderCount
            )
            assertEquals(
                "$phaseLabel: unexpected inventoryGatewayProvider delta",
                0,
                after.inventoryProviderCount - before.inventoryProviderCount
            )
            assertEquals(
                "$phaseLabel: unexpected realtimeTransportProvider delta",
                0,
                after.realtimeProviderCount - before.realtimeProviderCount
            )
            assertEquals(
                "$phaseLabel: unexpected cached/lazy container resolution delta for ROBOT_TRANSPORT",
                expectedRecreateRobotTransportResolutions,
                after.resolvedCount(AppContainerDependency.ROBOT_TRANSPORT) -
                    before.resolvedCount(AppContainerDependency.ROBOT_TRANSPORT)
            )
            listOf(
                AppContainerDependency.AUTH_GATEWAY,
                AppContainerDependency.INVENTORY_GATEWAY,
                AppContainerDependency.REALTIME_TRANSPORT,
                AppContainerDependency.SESSION_COORDINATOR,
                AppContainerDependency.INVENTORY_REPOSITORY
            ).forEach { dep ->
                assertEquals(
                    "$phaseLabel: unexpected cached/lazy container resolution delta for $dep",
                    0,
                    after.resolvedCount(dep) - before.resolvedCount(dep)
                )
            }
            assertEquals("$phaseLabel: unexpected RobotTransport.sendCommand delta", 0, after.sendCommandCount - before.sendCommandCount)
            assertEquals("$phaseLabel: unexpected AuthGateway.signIn delta", 0, after.signInCount - before.signInCount)
            assertEquals("$phaseLabel: unexpected InventoryGateway.fetchThings delta", 0, after.fetchThingsCount - before.fetchThingsCount)
            assertEquals("$phaseLabel: unexpected RealtimeSessionTransport.connect delta", 0, after.connectCount - before.connectCount)
            assertEquals("$phaseLabel: unexpected RealtimeSessionTransport.sendOutbound delta", 0, after.sendOutboundCount - before.sendOutboundCount)
            assertFalse(container.pilotAuthorizationGate.isAuthorized)
            assertFalse(container.offlineMockAuthorizationGate.isAuthorized)
            assertEquals(PilotModeState.OfflineDefault, container.pilotSessionController.pilotModeState.value)
            assertEquals(PilotModeState.OfflineDefault, container.offlineMockPilotController.pilotModeState.value)
        }
    }

    @Test
    fun syntheticReplaySurvivesRecreateAndExplicitNavigationHasZeroFallbackOnEmptyAndErrorStreams() {
        val context = ApplicationProvider.getApplicationContext<WeBackApplication>()

        // Scenario 1: Valid synthetic replay + Activity.recreate() + explicit Back to Simulator + explicit simulator command
        val replayRecorder = ScenarioOracleRecorder()
        val replayContainer = replayRecorder.buildContainer(context = context)
        context.replaceContainerForTesting(replayContainer)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("simulator_badge").assertIsDisplayed()
            val preEnter = replayRecorder.snapshot()
            replayRecorder.assertStartupBaseline("valid_replay.startup", preEnter, replayContainer)

            composeRule.onNodeWithTag("btn_open_local_map").performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("local_map_source_badge").assertIsDisplayed()
            replayRecorder.assertZeroFallbackDelta("valid_replay.enter", preEnter, replayRecorder.snapshot(), replayContainer)

            composeRule.onNodeWithTag("btn_replay_play").performScrollTo().performClick()
            waitUntil("valid replay completion") {
                val s = replayContainer.localMapRepository.state.value
                s.connectionState == LocalMapConnectionState.DISCONNECTED && s.mapCount == 4L
            }
            val beforeSnapshot = replayContainer.localMapRepository.state.value.currentSnapshot
            assertNotNull(beforeSnapshot)
            replayRecorder.assertZeroFallbackDelta("valid_replay.eof", preEnter, replayRecorder.snapshot(), replayContainer)

            scenario.recreate()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("local_map_source_badge").assertIsDisplayed()
            composeRule.onNodeWithTag("local_map_counters_label")
                .performScrollTo()
                .assertTextContains("Maps: 4 | Aux: 1 | Trajectory (opaque counts only): 1")
            composeRule.onNodeWithTag("map_canvas").performScrollTo().assertIsDisplayed()
            assertEquals(beforeSnapshot, replayContainer.localMapRepository.state.value.currentSnapshot)
            // Activity.recreate() re-evaluates ComponentActivity.by viewModels factoryProducer (1 cached ROBOT_TRANSPORT lookup, 0 new provider invocations)
            val postRecreate = replayRecorder.snapshot()
            replayRecorder.assertZeroFallbackDelta(
                "valid_replay.recreate",
                preEnter,
                postRecreate,
                replayContainer,
                expectedRecreateRobotTransportResolutions = 1
            )

            // Explicit user return to simulator
            composeRule.onNodeWithTag("btn_back_to_simulator").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("simulator_badge").assertIsDisplayed()
            replayRecorder.assertZeroFallbackDelta("valid_replay.after_explicit_back", postRecreate, replayRecorder.snapshot(), replayContainer)

            // Verify simulator transport only receives commands when user explicitly acts on DashboardScreen
            composeRule.onNodeWithTag("btn_clean").performScrollTo().performClick()
            waitUntil("explicit simulator Clean invocation") {
                replayRecorder.spyRobot.sendCommandCount == 1
            }
            assertEquals(1, replayRecorder.spyRobot.sendCommandCount)
            assertEquals(0, replayRecorder.spyAuth.signInCount)
            assertEquals(0, replayRecorder.spyInventory.fetchThingsCount)
            assertEquals(0, replayRecorder.spyRealtime.connectCount)
            assertEquals(0, replayRecorder.spyRealtime.sendOutboundCount)
        }

        // Scenario 2: Zero-map clean EOF -> immediate zero-fallback assertions BEFORE system Back navigation
        val emptyRecorder = ScenarioOracleRecorder()
        val emptyContainer = emptyRecorder.buildContainer(
            context = context,
            localStreamSourceFactory = {
                SyntheticReplayTelemetrySource(
                    fixtureBytesProvider = { byteArrayOf() },
                    expectedSha256Hex = null,
                    stepDelayMs = 0L
                )
            }
        )
        context.replaceContainerForTesting(emptyContainer)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.waitForIdle()
            val preEnter = emptyRecorder.snapshot()
            emptyRecorder.assertStartupBaseline("zero_map_eof.startup", preEnter, emptyContainer)
            assertEquals(1, preEnter.localSourceProviderCount)

            composeRule.onNodeWithTag("btn_open_local_map").performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("btn_replay_play").performScrollTo().performClick()
            waitUntil("zero-map clean EOF") {
                emptyContainer.localMapRepository.state.value.connectionState == LocalMapConnectionState.DISCONNECTED
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("local_map_acceptance_status")
                .assertTextContains("Map View Not Accepted (0 valid maps received)")
            composeRule.onNodeWithTag("simulator_badge").assertDoesNotExist()
            assertNull(emptyContainer.localMapRepository.state.value.currentSnapshot)
            assertFalse(emptyContainer.localMapRepository.state.value.mapViewAccepted)

            // Immediate oracle check BEFORE navigation
            emptyRecorder.assertZeroFallbackDelta("zero_map_eof.immediate_before_back", preEnter, emptyRecorder.snapshot(), emptyContainer)

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("simulator_badge").assertIsDisplayed()
            emptyRecorder.assertZeroFallbackDelta("zero_map_eof.after_system_back", preEnter, emptyRecorder.snapshot(), emptyContainer)
        }

        // Scenario 3: Codec error BEFORE first MAP -> immediate zero-fallback assertions + system Back
        val errorBeforeMapRecorder = ScenarioOracleRecorder()
        val errorBeforeMapContainer = errorBeforeMapRecorder.buildContainer(
            context = context,
            localStreamSourceFactory = {
                SyntheticReplayTelemetrySource(
                    fixtureBytesProvider = { HoslamTestFixtures.loadResourceBytes("bad_checksum.bin") },
                    expectedSha256Hex = null,
                    stepDelayMs = 0L
                )
            }
        )
        context.replaceContainerForTesting(errorBeforeMapContainer)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.waitForIdle()
            val preEnter = errorBeforeMapRecorder.snapshot()
            errorBeforeMapRecorder.assertStartupBaseline("error_before_map.startup", preEnter, errorBeforeMapContainer)

            composeRule.onNodeWithTag("btn_open_local_map").performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("btn_replay_play").performScrollTo().performClick()
            waitUntil("codec error before first map") {
                errorBeforeMapContainer.localMapRepository.state.value.connectionState == LocalMapConnectionState.ERROR
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("local_map_state_label").assertTextContains("ERROR (INVALID_CHECKSUM)")
            composeRule.onNodeWithTag("simulator_badge").assertDoesNotExist()
            assertNull(errorBeforeMapContainer.localMapRepository.state.value.currentSnapshot)

            errorBeforeMapRecorder.assertZeroFallbackDelta(
                "error_before_map.immediate_before_back",
                preEnter,
                errorBeforeMapRecorder.snapshot(),
                errorBeforeMapContainer
            )

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("simulator_badge").assertIsDisplayed()
            errorBeforeMapRecorder.assertZeroFallbackDelta(
                "error_before_map.after_system_back",
                preEnter,
                errorBeforeMapRecorder.snapshot(),
                errorBeforeMapContainer
            )
        }

        // Scenario 4: Codec error AFTER valid MAP -> ERROR / STALE retained without fallback + explicit Back
        val errorAfterMapRecorder = ScenarioOracleRecorder()
        val validMapPlusBadChecksum = buildSyntheticMapFrame(width = 2, height = 2, fill = 0x33) +
            HoslamTestFixtures.loadResourceBytes("bad_checksum.bin")
        val errorAfterMapContainer = errorAfterMapRecorder.buildContainer(
            context = context,
            localStreamSourceFactory = {
                SyntheticReplayTelemetrySource(
                    fixtureBytesProvider = { validMapPlusBadChecksum },
                    expectedSha256Hex = null,
                    stepDelayMs = 0L
                )
            }
        )
        context.replaceContainerForTesting(errorAfterMapContainer)

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitForIdle()
            val preEnter = errorAfterMapRecorder.snapshot()
            errorAfterMapRecorder.assertStartupBaseline("error_after_map.startup", preEnter, errorAfterMapContainer)

            composeRule.onNodeWithTag("btn_open_local_map").performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("btn_replay_play").performScrollTo().performClick()
            waitUntil("codec error after valid map") {
                errorAfterMapContainer.localMapRepository.state.value.connectionState == LocalMapConnectionState.ERROR
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("local_map_state_label").assertTextContains("ERROR / STALE (INVALID_CHECKSUM)")
            composeRule.onNodeWithTag("map_canvas").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("simulator_badge").assertDoesNotExist()
            assertNotNull(errorAfterMapContainer.localMapRepository.state.value.currentSnapshot)
            assertEquals(1L, errorAfterMapContainer.localMapRepository.state.value.mapCount)

            errorAfterMapRecorder.assertZeroFallbackDelta(
                "error_after_map.immediate_before_back",
                preEnter,
                errorAfterMapRecorder.snapshot(),
                errorAfterMapContainer
            )

            composeRule.onNodeWithTag("btn_back_to_simulator").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("simulator_badge").assertIsDisplayed()
            errorAfterMapRecorder.assertZeroFallbackDelta(
                "error_after_map.after_explicit_back",
                preEnter,
                errorAfterMapRecorder.snapshot(),
                errorAfterMapContainer
            )
        }
    }

    @Test
    fun ac501_3_nonFallbackOracleRequiresRealProviderAndCachedResolutionTelemetryAcrossScenarios() {
        val functionSeamCount = DefaultPilotContainer::class.java.constructors
            .flatMap { ctor -> ctor.genericParameterTypes.toList() }
            .map { it.toString() }
            .count {
                it.contains("kotlin.jvm.functions.Function0") ||
                    it.contains("kotlin.jvm.functions.Function1")
            }
        assertTrue(
            "AC-501.3 / TEST-LOCAL-513 requires real provider/factory and resolution observation seams on DefaultPilotContainer; found $functionSeamCount",
            functionSeamCount >= 6
        )

        val recorder = ScenarioOracleRecorder()
        val container = DefaultPilotContainer(
            authGatewayProvider = {
                recorder.authProviderCount.incrementAndGet()
                recorder.spyAuth
            },
            inventoryGatewayProvider = {
                recorder.inventoryProviderCount.incrementAndGet()
                recorder.spyInventory
            },
            realtimeTransportProvider = {
                recorder.realtimeProviderCount.incrementAndGet()
                recorder.spyRealtime
            },
            robotTransportProvider = {
                recorder.robotProviderCount.incrementAndGet()
                recorder.spyRobot
            },
            dependencyResolutionObserver = recorder::recordResolution
        )
        try {
            val first = container.robotTransport
            val second = container.robotTransport
            assertTrue(first === second)
            assertEquals(1, recorder.robotProviderCount.get())
            assertEquals(2, recorder.snapshot().resolvedCount(AppContainerDependency.ROBOT_TRANSPORT))
            assertEquals(0, recorder.spyRobot.sendCommandCount)
        } finally {
            container.close()
        }
    }

    private fun waitUntil(description: String, timeoutMs: Long = 4_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (predicate()) return
            Thread.sleep(20L)
        }
        assertTrue("Timed out waiting for $description", predicate())
    }

    private fun buildSyntheticMapFrame(width: Int, height: Int, fill: Int): ByteArray {
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
