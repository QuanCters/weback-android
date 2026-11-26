package com.weback.robot.ui.pilot

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.domain.device.DeviceCapabilityClassifier
import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.model.ControlCompatibility
import com.weback.robot.domain.model.DeviceInventoryItem
import com.weback.robot.domain.model.InventoryState
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.RobotState
import com.weback.robot.domain.pilot.ObservedOriginSummary
import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotEmergencyDisconnectResult
import com.weback.robot.domain.pilot.PilotModeState
import com.weback.robot.domain.pilot.ReadOnlyTelemetrySnapshot
import com.weback.robot.ui.theme.WeBackTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PilotDeviceIdentityCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testDisplaysAuthSummaryEndpointReviewInventoryStatusTimerAndEmergencyDisconnect() {
        val laserItem = DeviceInventoryItem(
            thingName = "raw_secret_thing_001",
            subType = "xs-x8-eu",
            nickname = "Living Laser",
            isAdmin = true,
            capability = DeviceCapabilityClassifier.classify("xs-x8-eu"),
            initialState = RobotState("Living Laser", 88, RobotMode.CLEAN)
        )
        var killSwitchClicked = false

        composeTestRule.setContent {
            WeBackTheme(dynamicColor = false) {
                PilotReadinessSheet(
                    state = PilotSheetUiState(
                        modeState = PilotModeState.Observing(
                            authorizationId = "FCT004-PILOT-20261125-01",
                            remainingWindowMs = 1_500_000L,
                            selectedSnapshot = ReadOnlyTelemetrySnapshot(
                                thingNameRedacted = "LASER_TAG_01",
                                nickname = "Living Laser",
                                normalizedSubType = "xs-x8-eu-",
                                isAdmin = true,
                                compatibility = ControlCompatibility.MvpSupportedShadowWs,
                                batteryPercentage = 88,
                                mode = RobotMode.CLEAN,
                                errorCode = 0
                            )
                        ),
                        authorizationId = "FCT004-PILOT-20261125-01",
                        modeLabel = "OFFLINE / MOCK PILOT BOUNDARY",
                        approvedAuthOrigin = "https://user.grit-cloud.com:443/prod/oauth",
                        pinnedApiOrigin = "https://api-us.grit-cloud.com:443",
                        pinnedWssOrigin = "wss://wss-us.grit-cloud.com:443",
                        quarantinedSummary = ObservedOriginSummary(
                            apiOrigin = "https://dyn-api.example.invalid:443",
                            wssOrigin = "wss://dyn-wss.example.invalid:443",
                            provenanceVerified = false,
                            quarantineReason = "PROVENANCE_REQUIRED"
                        ),
                        remainingObservationMs = 1_500_000L,
                        connectionState = ConnectionState.Connected(epoch = 2L, generation = 1L),
                        inventoryState = InventoryState.Loaded(listOf(laserItem), epoch = 2L),
                        selectedDevice = laserItem,
                        lastAbortReason = PilotAbortReason.UserEmergencyDisconnect,
                        lastDisconnectResult = PilotEmergencyDisconnectResult(
                            networkRevokedImmediately = true,
                            storageCleared = false,
                            incompleteStorageReason = "TOMBSTONE_WRITE_FAILED",
                            restoredOfflineSimulator = true,
                            serverSideTokenRevocationClaimed = false
                        )
                    ),
                    passwordBuffer = EphemeralPasswordBuffer(),
                    onSubmitCredentials = {},
                    onCancelCredentialEntry = {},
                    onRefreshInventory = {},
                    onSelectDevice = {},
                    onEmergencyDisconnect = { killSwitchClicked = true },
                    onDismissSheet = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("pilot_mode_badge").assertIsDisplayed().assertTextContains("OFFLINE / MOCK", substring = true)
        composeTestRule.onNodeWithTag("pilot_auth_summary").performScrollTo().assertIsDisplayed().assertTextContains("FCT004-PILOT-20261125-01", substring = true)
        composeTestRule.onNodeWithTag("pilot_observation_timer").performScrollTo().assertIsDisplayed().assertTextContains("25:00", substring = true)
        composeTestRule.onNodeWithTag("pilot_endpoint_review").performScrollTo().assertIsDisplayed().assertTextContains("https://user.grit-cloud.com:443/prod/oauth", substring = true)
        composeTestRule.onNodeWithTag("pilot_device_list").performScrollTo().assertIsDisplayed().assertTextContains("Living Laser", substring = true)
        composeTestRule.onNodeWithTag("pilot_selected_status").performScrollTo().assertIsDisplayed().assertTextContains("88%", substring = true)
        composeTestRule.onNodeWithTag("pilot_disconnect_status").performScrollTo().assertIsDisplayed().assertTextContains("TOMBSTONE_WRITE_FAILED", substring = true)

        // Raw thing_name must NEVER leak in UI; redacted tag is shown instead
        composeTestRule.onNodeWithText("raw_secret_thing_001", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("pilot_device_list").assertTextContains("LASER_TAG_01", substring = true)

        composeTestRule.onNodeWithTag("btn_pilot_emergency_disconnect").performScrollTo().performClick()
        assertTrue(killSwitchClicked)
    }
}
