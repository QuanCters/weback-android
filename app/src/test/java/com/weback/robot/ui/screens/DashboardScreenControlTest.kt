package com.weback.robot.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.core.MonotonicClock
import com.weback.robot.domain.model.CommandExecutionState
import com.weback.robot.domain.model.CommandRejectionReason
import com.weback.robot.domain.model.ControlAvailability
import com.weback.robot.domain.model.RobotCommand
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.RobotState
import com.weback.robot.domain.model.SimulatorErrorCodes
import com.weback.robot.protocol.SimulatorConfig
import com.weback.robot.protocol.SimulatorRobotTransport
import com.weback.robot.testutils.TestDispatcherProvider
import com.weback.robot.ui.DashboardIntent
import com.weback.robot.ui.DashboardViewModel
import com.weback.robot.ui.RobotUiState
import com.weback.robot.ui.theme.WeBackTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardScreenControlTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private data class ModeUiExpectation(
        val label: String,
        val state: RobotState,
        val commandState: CommandExecutionState = CommandExecutionState.Idle,
        val cleanEnabled: Boolean,
        val pauseEnabled: Boolean,
        val resumeEnabled: Boolean,
        val dockEnabled: Boolean
    )

    @Test
    fun testButtonEnablementMatrixAndOfflineBadgeAcrossAllControlStates() {
        val scenarios = listOf(
            ModeUiExpectation(
                label = "DOCK",
                state = RobotState(mode = RobotMode.DOCK, batteryPercentage = 100),
                cleanEnabled = true, pauseEnabled = false, resumeEnabled = false, dockEnabled = false
            ),
            ModeUiExpectation(
                label = "CLEAN",
                state = RobotState(mode = RobotMode.CLEAN, batteryPercentage = 90),
                cleanEnabled = false, pauseEnabled = true, resumeEnabled = false, dockEnabled = true
            ),
            ModeUiExpectation(
                label = "PAUSED",
                state = RobotState(mode = RobotMode.PAUSED, batteryPercentage = 90),
                cleanEnabled = false, pauseEnabled = false, resumeEnabled = true, dockEnabled = true
            ),
            ModeUiExpectation(
                label = "RETURNING_DOCK",
                state = RobotState(mode = RobotMode.RETURNING_DOCK, batteryPercentage = 20),
                cleanEnabled = false, pauseEnabled = false, resumeEnabled = false, dockEnabled = false
            ),
            ModeUiExpectation(
                label = "CHARGING",
                state = RobotState(mode = RobotMode.CHARGING, batteryPercentage = 55),
                cleanEnabled = true, pauseEnabled = false, resumeEnabled = false, dockEnabled = false
            ),
            ModeUiExpectation(
                label = "DORMANT",
                state = RobotState(mode = RobotMode.DORMANT, batteryPercentage = 70),
                cleanEnabled = true, pauseEnabled = false, resumeEnabled = false, dockEnabled = false
            ),
            ModeUiExpectation(
                label = "IDLE",
                state = RobotState(mode = RobotMode.IDLE, batteryPercentage = 70),
                cleanEnabled = true, pauseEnabled = false, resumeEnabled = false, dockEnabled = false
            ),
            ModeUiExpectation(
                label = "LOW_BATTERY_DOCK_14",
                state = RobotState(mode = RobotMode.DOCK, batteryPercentage = 14),
                cleanEnabled = false, pauseEnabled = false, resumeEnabled = false, dockEnabled = false
            ),
            ModeUiExpectation(
                label = "FAULT",
                state = RobotState(
                    mode = RobotMode.FAULT,
                    batteryPercentage = 0,
                    errorCode = SimulatorErrorCodes.SYNTHETIC_BATTERY_DEPLETED
                ),
                cleanEnabled = false, pauseEnabled = false, resumeEnabled = false, dockEnabled = false
            ),
            ModeUiExpectation(
                label = "PENDING",
                state = RobotState(mode = RobotMode.CLEAN, batteryPercentage = 80),
                commandState = CommandExecutionState.Pending(RobotCommand.PAUSE, commandId = 1L, submittedAtMs = 0L),
                cleanEnabled = false, pauseEnabled = false, resumeEnabled = false, dockEnabled = false
            )
        )

        var renderedState by mutableStateOf(RobotUiState())
        composeTestRule.setContent {
            WeBackTheme(darkTheme = false, dynamicColor = false) {
                DashboardScreen(uiState = renderedState, onIntent = {})
            }
        }

        for (scenario in scenarios) {
            renderedState = RobotUiState(
                robotName = scenario.state.name,
                batteryPercentage = scenario.state.batteryPercentage,
                mode = scenario.state.mode,
                isDocked = scenario.state.isDocked,
                isDustbinClean = scenario.state.isDustbinClean,
                errorCode = scenario.state.errorCode,
                isFault = scenario.state.isFault,
                commandState = scenario.commandState,
                controls = ControlAvailability.from(scenario.state, scenario.commandState)
            )
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("simulator_badge").assertIsDisplayed()
            composeTestRule.onNodeWithTag("robot_mode_text")
                .performScrollTo()
                .assertTextContains(scenario.state.mode.name)

            val cleanNode = composeTestRule.onNodeWithTag("btn_clean").performScrollTo()
            val pauseNode = composeTestRule.onNodeWithTag("btn_pause").performScrollTo()
            val resumeNode = composeTestRule.onNodeWithTag("btn_resume").performScrollTo()
            val dockNode = composeTestRule.onNodeWithTag("btn_dock").performScrollTo()

            if (scenario.cleanEnabled) cleanNode.assertIsEnabled() else cleanNode.assertIsNotEnabled()
            if (scenario.pauseEnabled) pauseNode.assertIsEnabled() else pauseNode.assertIsNotEnabled()
            if (scenario.resumeEnabled) resumeNode.assertIsEnabled() else resumeNode.assertIsNotEnabled()
            if (scenario.dockEnabled) dockNode.assertIsEnabled() else dockNode.assertIsNotEnabled()
        }
    }

    @Test
    fun testClickDispatchAndFeedbackBannersInLightAndDarkThemes() {
        val dispatched = mutableListOf<DashboardIntent>()

        val dockRejectedState = RobotUiState(
            mode = RobotMode.DOCK,
            batteryPercentage = 100,
            commandState = CommandExecutionState.Rejected(
                command = RobotCommand.PAUSE,
                commandId = 3L,
                reason = CommandRejectionReason.INVALID_TRANSITION,
                message = "Cannot pause while docked",
                settledAtMs = 30L
            ),
            busyConflictNotice = "COMMAND_IN_FLIGHT: CLEAN is still pending",
            controls = ControlAvailability(canClean = true, canPause = false, canResume = false, canDock = false)
        )
        val pausedAckState = RobotUiState(
            mode = RobotMode.PAUSED,
            batteryPercentage = 40,
            commandState = CommandExecutionState.Acknowledged(
                command = RobotCommand.PAUSE,
                commandId = 4L,
                settledAtMs = 100L
            ),
            controls = ControlAvailability(canClean = false, canPause = false, canResume = true, canDock = true)
        )

        var currentUiState by mutableStateOf(dockRejectedState)
        var useDarkTheme by mutableStateOf(false)

        composeTestRule.setContent {
            WeBackTheme(darkTheme = useDarkTheme, dynamicColor = false) {
                DashboardScreen(uiState = currentUiState, onIntent = { dispatched.add(it) })
            }
        }
        composeTestRule.waitForIdle()

        // 1. Light theme: Rejected feedback + Clean click + Dismiss click
        composeTestRule.onNodeWithTag("simulator_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("command_status_banner")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("Cannot pause while docked", substring = true)
        composeTestRule.onNodeWithTag("busy_conflict_banner")
            .performScrollTo()
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag("btn_clean").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("btn_dismiss_feedback").performScrollTo().performClick()
        assertEquals(listOf(DashboardIntent.Clean, DashboardIntent.DismissCommandFeedback), dispatched)

        // 2. Dark theme: PAUSED + Acknowledged banner + Resume/Dock clicks
        dispatched.clear()
        useDarkTheme = true
        currentUiState = pausedAckState
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("simulator_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("command_status_banner")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("Acknowledged", substring = true)
        composeTestRule.onNodeWithTag("btn_resume").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("btn_dock").performScrollTo().performClick()
        assertEquals(listOf(DashboardIntent.Resume, DashboardIntent.Dock), dispatched)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testEndToEndViewModelAndSimulatorInteractionViaComposeClicks() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)
        val clock = MonotonicClock { scheduler.currentTime }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val transport = SimulatorRobotTransport(
            initialState = RobotState(mode = RobotMode.DOCK, batteryPercentage = 100),
            dispatcherProvider = dispatchers,
            config = SimulatorConfig(ackDelayMs = 300L, returnToDockDurationMs = 1_000L),
            clock = clock
        )
        val viewModel = DashboardViewModel(
            robotTransport = transport,
            dispatchers = dispatchers,
            coroutineScope = scope,
            clock = clock
        )

        composeTestRule.setContent {
            WeBackTheme(dynamicColor = false) {
                DashboardScreen(viewModel = viewModel)
            }
        }
        scheduler.runCurrent()
        composeTestRule.waitForIdle()

        // Tap Clean -> immediately Pending, all controls locked
        composeTestRule.onNodeWithTag("btn_clean").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("command_status_banner")
            .performScrollTo()
            .assertTextContains("Pending", substring = true)
        composeTestRule.onNodeWithTag("btn_clean").assertIsNotEnabled()

        // Advance virtual time for ack -> CLEAN mode, Pause and Dock enabled
        scheduler.advanceTimeBy(300L)
        scheduler.runCurrent()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("robot_mode_text").assertTextContains("CLEAN")
        composeTestRule.onNodeWithTag("btn_pause").performScrollTo().assertIsEnabled()
        composeTestRule.onNodeWithTag("btn_dock").performScrollTo().assertIsEnabled()

        // Tap Pause -> ack -> PAUSED, Resume and Dock enabled
        composeTestRule.onNodeWithTag("btn_pause").performClick()
        scheduler.advanceTimeBy(300L)
        scheduler.runCurrent()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("robot_mode_text").assertTextContains("PAUSED")
        composeTestRule.onNodeWithTag("btn_resume").performScrollTo().assertIsEnabled()

        // Tap Dock -> ack -> RETURNING_DOCK -> controls locked
        composeTestRule.onNodeWithTag("btn_dock").performScrollTo().performClick()
        scheduler.advanceTimeBy(300L)
        scheduler.runCurrent()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("robot_mode_text").assertTextContains("RETURNING_DOCK")
        composeTestRule.onNodeWithTag("btn_clean").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("btn_dock").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("simulator_badge").assertIsDisplayed()

        // Dismiss post-settlement Acknowledged banner via UI button -> resets banner to Ready
        composeTestRule.onNodeWithTag("btn_dismiss_feedback").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("command_status_banner")
            .performScrollTo()
            .assertTextContains("Ready", substring = true)

        // Tap Resume when PAUSED vs Pending dismiss preserves Pending lock:
        // Advance to CHARGING -> tap Clean -> tap conflicting Dock while Pending -> dismiss notice -> Pending stays locked
        scheduler.advanceTimeBy(1_000L)
        scheduler.runCurrent()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("robot_mode_text").performScrollTo().assertTextContains("CHARGING")

        composeTestRule.onNodeWithTag("btn_clean").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        viewModel.onIntent(DashboardIntent.Dock) // conflicting intent while CLEAN is Pending
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("busy_conflict_banner").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_dismiss_feedback").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("command_status_banner")
            .performScrollTo()
            .assertTextContains("Pending", substring = true)
        composeTestRule.onNodeWithTag("btn_clean").performScrollTo().assertIsNotEnabled()

        scheduler.advanceTimeBy(300L)
        scheduler.runCurrent()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("robot_mode_text").performScrollTo().assertTextContains("CLEAN")

        transport.close()
        scope.cancel()
    }
}
