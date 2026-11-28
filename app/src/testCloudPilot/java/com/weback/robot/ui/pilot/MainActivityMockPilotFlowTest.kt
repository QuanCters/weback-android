package com.weback.robot.ui.pilot

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityMockPilotFlowTest {

    @get:Rule
    val activityRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun testCompleteWiredOfflineMockPilotWorkflowAndReturnToSimulator() {
        // 1. Launch on FCT-002 default offline simulator
        activityRule.onNodeWithTag("simulator_badge").assertIsDisplayed()
        activityRule.onNodeWithTag("btn_clean").performScrollTo().assertIsEnabled()

        // 2. Open PilotReadinessSheet and start offline mock pilot
        activityRule.onNodeWithTag("btn_open_pilot_readiness").performScrollTo().performClick()
        activityRule.waitForIdle()
        activityRule.onNodeWithTag("pilot_mode_badge").assertIsDisplayed()
        activityRule.onNodeWithTag("btn_start_mock_pilot").performScrollTo().performClick()
        activityRule.waitForIdle()
        val app = activityRule.activity.application as com.weback.robot.WeBackApplication
        val pilotContainer = app.container as com.weback.robot.core.PilotAppContainer
        org.junit.Assert.assertTrue(
            pilotContainer.offlineMockPilotController.pilotModeState.value
                is com.weback.robot.domain.pilot.PilotModeState.AuthorizedReady
        )

        // 3. Synthetic sign-in using mock isolated test account
        activityRule.onNodeWithTag("pilot_input_region").performScrollTo().performTextInput("+1")
        activityRule.onNodeWithTag("pilot_input_account").performScrollTo().performTextInput("mock-pilot@offline.invalid")
        activityRule.onNodeWithTag("pilot_input_password").performScrollTo().performTextInput("MockSecret1!")
        activityRule.onNodeWithTag("btn_pilot_submit_login").performScrollTo().performClick()
        activityRule.waitForIdle()

        // 4. Refresh fixture inventory and verify Laser SLAM + unsupported device
        activityRule.onNodeWithTag("btn_pilot_refresh_inventory").performScrollTo().performClick()
        activityRule.waitForIdle()
        activityRule.onNodeWithTag("pilot_device_list").performScrollTo().assertTextContains("WeBack Simulator Robot", substring = true)

        // 5. Select supported synthetic Laser SLAM device and observe synthetic telemetry + timer
        activityRule.onNodeWithTag("btn_observe_offline_laser_01").performScrollTo().performClick()
        activityRule.waitForIdle()
        activityRule.onNodeWithTag("pilot_selected_status").performScrollTo().assertTextContains("100%", substring = true)
        activityRule.onNodeWithTag("pilot_observation_timer").performScrollTo().assertIsDisplayed()

        // 6. Emergency Disconnect & Wipe -> returns to default offline simulator
        activityRule.onNodeWithTag("btn_pilot_emergency_disconnect").performScrollTo().performClick()
        activityRule.waitForIdle()
        activityRule.onNodeWithTag("pilot_auth_summary").performScrollTo().assertTextContains("OFFLINE_DEFAULT", substring = true)

        activityRule.onNodeWithTag("btn_pilot_close_sheet").performClick()
        activityRule.waitForIdle()
        activityRule.onNodeWithTag("simulator_badge").assertIsDisplayed()
        activityRule.onNodeWithTag("btn_clean").performScrollTo().assertIsEnabled()
    }
}
