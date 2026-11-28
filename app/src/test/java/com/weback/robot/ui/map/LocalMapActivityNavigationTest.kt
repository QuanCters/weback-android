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
import com.weback.robot.protocol.RobotTransport
import com.weback.robot.protocol.auth.AuthGateway
import com.weback.robot.protocol.inventory.DeviceInventoryGateway
import com.weback.robot.protocol.inventory.InventoryFetchResult
import com.weback.robot.protocol.local.SyntheticReplayTelemetrySource
import com.weback.robot.protocol.local.codec.HoslamTestFixtures
import com.weback.robot.protocol.ws.RealtimeSessionTransport
import com.weback.robot.core.DefaultAppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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

    @Test
    fun syntheticReplaySurvivesRecreateAndExplicitNavigationHasZeroFallbackOnEmptyAndErrorStreams() {
        val context = ApplicationProvider.getApplicationContext<WeBackApplication>()
        val spyRobot = SpyRobotTransport()
        val spyAuth = SpyAuthGateway()
        val spyInventory = SpyInventoryGateway()
        val spyRealtime = SpyRealtimeTransport()

        val replayContainer = DefaultAppContainer(
            context = context,
            authGatewayOverride = spyAuth,
            inventoryGatewayOverride = spyInventory,
            realtimeTransportOverride = spyRealtime,
            robotTransportOverride = spyRobot
        )
        context.replaceContainerForTesting(replayContainer)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("simulator_badge").assertIsDisplayed()
            composeRule.onNodeWithTag("btn_open_local_map").performScrollTo().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("local_map_source_badge").assertIsDisplayed()
            composeRule.onNodeWithTag("btn_replay_play").performScrollTo().performClick()

            val deadline = System.currentTimeMillis() + 4_000L
            while (System.currentTimeMillis() < deadline) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                val s = replayContainer.localMapRepository.state.value
                if (s.connectionState == LocalMapConnectionState.DISCONNECTED && s.mapCount == 4L) {
                    break
                }
                Thread.sleep(25L)
            }
            val beforeSnapshot = replayContainer.localMapRepository.state.value.currentSnapshot
            assertNotNull(beforeSnapshot)

            scenario.recreate()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("local_map_source_badge").assertIsDisplayed()
            composeRule.onNodeWithTag("local_map_counters_label")
                .performScrollTo()
                .assertTextContains("Maps: 4 | Aux: 1 | Trajectory (opaque counts only): 1")
            composeRule.onNodeWithTag("map_canvas").performScrollTo().assertIsDisplayed()
            assertEquals(beforeSnapshot, replayContainer.localMapRepository.state.value.currentSnapshot)

            assertEquals(0, spyRobot.sendCommandCount)
            assertEquals(0, spyAuth.signInCount)
            assertEquals(0, spyInventory.fetchThingsCount)
            assertEquals(0, spyRealtime.connectCount)
            assertEquals(0, spyRealtime.sendOutboundCount)

            composeRule.onNodeWithTag("btn_back_to_simulator").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("simulator_badge").assertIsDisplayed()
        }

        // Empty (0-map) stream -> stays on MapScreen with zero fallback
        val emptyContainer = DefaultAppContainer(
            context = context,
            authGatewayOverride = spyAuth,
            inventoryGatewayOverride = spyInventory,
            realtimeTransportOverride = spyRealtime,
            robotTransportOverride = spyRobot,
            localStreamSourceOverride = SyntheticReplayTelemetrySource(
                fixtureBytesProvider = { byteArrayOf() },
                expectedSha256Hex = null,
                stepDelayMs = 0L
            )
        )
        context.replaceContainerForTesting(emptyContainer)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("btn_open_local_map").performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("btn_replay_play").performScrollTo().performClick()

            val deadline = System.currentTimeMillis() + 3_000L
            while (System.currentTimeMillis() < deadline) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                if (emptyContainer.localMapRepository.state.value.connectionState == LocalMapConnectionState.DISCONNECTED) {
                    break
                }
                Thread.sleep(20L)
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("local_map_acceptance_status")
                .assertTextContains("Map View Not Accepted (0 valid maps received)")
            composeRule.onNodeWithTag("simulator_badge").assertDoesNotExist()
            assertNull(emptyContainer.localMapRepository.state.value.currentSnapshot)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("simulator_badge").assertIsDisplayed()
        }

        // Corrupt stream -> stays on MapScreen with zero fallback
        val errorContainer = DefaultAppContainer(
            context = context,
            authGatewayOverride = spyAuth,
            inventoryGatewayOverride = spyInventory,
            realtimeTransportOverride = spyRealtime,
            robotTransportOverride = spyRobot,
            localStreamSourceOverride = SyntheticReplayTelemetrySource(
                fixtureBytesProvider = { HoslamTestFixtures.loadResourceBytes("bad_checksum.bin") },
                expectedSha256Hex = null,
                stepDelayMs = 0L
            )
        )
        context.replaceContainerForTesting(errorContainer)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("btn_open_local_map").performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("btn_replay_play").performScrollTo().performClick()

            val deadline = System.currentTimeMillis() + 3_000L
            while (System.currentTimeMillis() < deadline) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                if (errorContainer.localMapRepository.state.value.connectionState == LocalMapConnectionState.ERROR) {
                    break
                }
                Thread.sleep(20L)
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("local_map_state_label").assertTextContains("ERROR (INVALID_CHECKSUM)")
            composeRule.onNodeWithTag("simulator_badge").assertDoesNotExist()
            assertNull(errorContainer.localMapRepository.state.value.currentSnapshot)

            assertEquals(0, spyRobot.sendCommandCount)
            assertEquals(0, spyAuth.signInCount)
            assertEquals(0, spyInventory.fetchThingsCount)
            assertEquals(0, spyRealtime.connectCount)
            assertEquals(0, spyRealtime.sendOutboundCount)

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("simulator_badge").assertIsDisplayed()
        }
    }
}
