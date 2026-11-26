package com.weback.robot.ui.pilot

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.MainActivity
import com.weback.robot.WeBackApplication
import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.DefaultAppContainer
import com.weback.robot.core.PilotContainerTestHooks
import com.weback.robot.domain.pilot.GateActivationResult
import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotEnvironment
import com.weback.robot.domain.pilot.PilotModeState
import com.weback.robot.domain.pilot.PilotRequestBudgets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Fct004CMockExpiryReadinessTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun boundedOneMinuteMockOptionExpiresAutomaticallyOnMonotonicClockAndPreserves30MinMax() {
        var nowMs = 100_000L
        val app = ApplicationProvider.getApplicationContext<Application>() as WeBackApplication
        app.replaceContainerForTesting(
            DefaultAppContainer(
                context = app,
                authClock = AuthMonotonicClock { nowMs },
                pilotTestHooks = PilotContainerTestHooks(enableUiCountdownTicker = false)
            )
        )

        // 1. Out-of-bounds mock windows rejected; 30-min default preserved
        assertTrue(app.container.startOfflineMockPilot(0L) is GateActivationResult.Rejected)
        assertTrue(
            app.container.startOfflineMockPilot(PilotRequestBudgets.MAX_OBSERVATION_WINDOW_MS + 1L)
                is GateActivationResult.Rejected
        )

        val defaultAct = app.container.startOfflineMockPilot()
        assertTrue(defaultAct is GateActivationResult.Activated)
        val defaultCtx = (defaultAct as GateActivationResult.Activated).context
        assertEquals(PilotEnvironment.OFFLINE_MOCK_FIXTURE, defaultCtx.environment)
        assertEquals(PilotRequestBudgets.MAX_OBSERVATION_WINDOW_MS, defaultCtx.budgets.observationWindowMs)
        assertFalse(app.container.pilotAuthorizationGate.isAuthorized)
        kotlinx.coroutines.runBlocking {
            app.container.offlineMockPilotController.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect)
        }

        // 2. UI bounded 1-minute mock option activates OFFLINE_MOCK_FIXTURE only and expires automatically across resume
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.onNodeWithTag("btn_open_pilot_readiness").performScrollTo().performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("btn_start_short_mock_pilot").performScrollTo().performClick()
            composeRule.waitForIdle()

            assertTrue(app.container.offlineMockAuthorizationGate.isAuthorized)
            assertFalse("Short mock must NEVER grant live authorization", app.container.pilotAuthorizationGate.isAuthorized)
            val mockCtx = app.container.offlineMockAuthorizationGate.currentContext!!
            assertEquals(PilotEnvironment.OFFLINE_MOCK_FIXTURE, mockCtx.environment)
            assertEquals(60_000L, mockCtx.observationDeadlineElapsedMs - mockCtx.authorizedAtElapsedMs)
            composeRule.onNodeWithTag("pilot_observation_timer").performScrollTo().assertTextContains("01:00", substring = true)

            // Simulate screen-off/doze past 60_000ms monotonic elapsedRealtime deadline and resume
            scenario.moveToState(Lifecycle.State.CREATED)
            nowMs += 60_000L
            scenario.moveToState(Lifecycle.State.RESUMED)

            composeRule.waitUntil(5_000L) {
                !app.container.offlineMockAuthorizationGate.isAuthorized &&
                    app.container.offlineMockPilotController.pilotModeState.value == PilotModeState.OfflineDefault
            }
            composeRule.waitForIdle()

            assertEquals(
                PilotAbortReason.ObservationWindowExpired,
                app.container.offlineMockPilotController.lastAbortReason.value
            )
            composeRule.onNodeWithTag("pilot_disconnect_status").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("pilot_disconnect_status").assertTextContains("OBSERVATION_WINDOW_EXPIRED", substring = true)
        }
    }
}
