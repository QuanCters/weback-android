package com.weback.robot.ui.pilot

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.core.DefaultPilotContainer
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.model.InventoryState
import com.weback.robot.domain.pilot.PilotModeState
import com.weback.robot.security.EndpointTrustDecision
import com.weback.robot.ui.theme.WeBackTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowDialog

@RunWith(AndroidJUnit4::class)
class PilotSignInSheetTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun testDefaultContainerStartsOfflineAndNeverAutoEnablesPilot() {
        val container = DefaultPilotContainer()
        try {
            assertFalse(container.pilotAuthorizationGate.isAuthorized)
            assertEquals(PilotModeState.OfflineDefault, container.pilotSessionController.pilotModeState.value)
            assertTrue(
                container.endpointTrustPolicy.authorize("https://user.grit-cloud.com/prod/oauth")
                    is EndpointTrustDecision.Untrusted
            )
        } finally {
            container.close()
        }
    }

    @Test
    fun testFlagSecureAndCharArrayWipeOnSubmitCancelErrorAndExit() {
        val buffer = EphemeralPasswordBuffer()
        var showSheet by mutableStateOf(true)
        var modeState by mutableStateOf<PilotModeState>(
            PilotModeState.AuthorizedReady("FCT004-PILOT-20261125-01", 1_800_000L)
        )
        var submittedCreds: AuthCredentials? = null
        var cancelCount = 0

        composeTestRule.setContent {
            WeBackTheme(dynamicColor = false) {
                if (showSheet) {
                    PilotReadinessSheet(
                        state = PilotSheetUiState(
                            modeState = modeState,
                            authorizationId = "FCT004-PILOT-20261125-01",
                            modeLabel = "MOCK / OFFLINE PILOT TEST",
                            approvedAuthOrigin = "https://user.grit-cloud.com:443/prod/oauth",
                            pinnedApiOrigin = "https://api-us.grit-cloud.com:443",
                            pinnedWssOrigin = "wss://wss-us.grit-cloud.com:443",
                            remainingObservationMs = 1_800_000L,
                            connectionState = ConnectionState.Disconnected(),
                            inventoryState = InventoryState.Idle,
                            selectedDevice = null,
                            lastAbortReason = null,
                            lastDisconnectResult = null
                        ),
                        passwordBuffer = buffer,
                        onSubmitCredentials = { creds -> submittedCreds = creds },
                        onCancelCredentialEntry = { cancelCount++ },
                        onRefreshInventory = {},
                        onSelectDevice = {},
                        onEmergencyDisconnect = {},
                        onDismissSheet = { showSheet = false }
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // FLAG_SECURE must be active during credential entry
        val flagsWhileOpen = composeTestRule.activity.window.attributes.flags
        assertTrue(
            "Expected FLAG_SECURE during credential entry",
            (flagsWhileOpen and WindowManager.LayoutParams.FLAG_SECURE) != 0
        )

        // 1. Submit wipes mutable CharArray buffer immediately
        composeTestRule.onNodeWithTag("pilot_input_region").performScrollTo().performTextInput("+1")
        composeTestRule.onNodeWithTag("pilot_input_account").performScrollTo().performTextInput("pilot@example.invalid")
        composeTestRule.onNodeWithTag("pilot_input_password").performScrollTo().performTextInput("SecretPass1!")
        assertTrue(buffer.length > 0)

        composeTestRule.onNodeWithTag("btn_pilot_submit_login").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertNotNull(submittedCreds)
        assertEquals(0, buffer.length)
        assertTrue("Mutable CharArray must be zeroed on submit", buffer.chars.all { it == '\u0000' })

        // 2. Cancel wipes mutable CharArray buffer immediately
        composeTestRule.onNodeWithTag("pilot_input_password").performScrollTo().performTextInput("CancelSecret2!")
        assertTrue(buffer.length > 0)
        composeTestRule.onNodeWithTag("btn_pilot_cancel_login").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, cancelCount)
        assertEquals(0, buffer.length)
        assertTrue("Mutable CharArray must be zeroed on cancel", buffer.chars.all { it == '\u0000' })

        // 3. Error / Aborted mode state wipes buffer immediately
        composeTestRule.onNodeWithTag("pilot_input_password").performScrollTo().performTextInput("ErrorSecret3!")
        assertTrue(buffer.length > 0)
        modeState = PilotModeState.Aborted(
            com.weback.robot.domain.pilot.PilotAbortReason.AuthRejected,
            "Observed server rejection"
        )
        composeTestRule.waitForIdle()
        assertEquals(0, buffer.length)
        assertTrue("Mutable CharArray must be zeroed on error state", buffer.chars.all { it == '\u0000' })

        // 4. Screen exit wipes buffer and clears FLAG_SECURE
        modeState = PilotModeState.AuthorizedReady("FCT004-PILOT-20261125-01", 1_700_000L)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("pilot_input_password").performScrollTo().performTextInput("ExitSecret4!")
        assertTrue(buffer.length > 0)
        showSheet = false
        composeTestRule.waitForIdle()
        assertEquals(0, buffer.length)
        assertTrue("Mutable CharArray must be zeroed on screen exit", buffer.chars.all { it == '\u0000' })
        val flagsAfterClose = composeTestRule.activity.window.attributes.flags
        assertEquals(0, flagsAfterClose and WindowManager.LayoutParams.FLAG_SECURE)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun testFlagSecureAppliedWhenHostedInModalBottomSheetDialogWindow() {
        var showModal by mutableStateOf(true)
        composeTestRule.setContent {
            WeBackTheme(dynamicColor = false) {
                if (showModal) {
                    ModalBottomSheet(onDismissRequest = { showModal = false }) {
                        PilotReadinessSheet(
                            state = PilotSheetUiState(
                                modeState = PilotModeState.AuthorizedReady("FCT004-PILOT-20261125-01", 1_800_000L),
                                authorizationId = "FCT004-PILOT-20261125-01",
                                modeLabel = "MOCK / OFFLINE PILOT TEST",
                                approvedAuthOrigin = "https://user.grit-cloud.com:443/prod/oauth",
                                pinnedApiOrigin = null,
                                pinnedWssOrigin = null,
                                remainingObservationMs = 1_800_000L,
                                connectionState = ConnectionState.Disconnected(),
                                inventoryState = InventoryState.Idle,
                                selectedDevice = null,
                                lastAbortReason = null,
                                lastDisconnectResult = null
                            ),
                            onSubmitCredentials = {},
                            onCancelCredentialEntry = {},
                            onRefreshInventory = {},
                            onSelectDevice = {},
                            onEmergencyDisconnect = {},
                            onDismissSheet = { showModal = false }
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("pilot_input_password").performScrollTo().assertIsDisplayed()
        val dialogWindow = ShadowDialog.getLatestDialog()?.window
        assertNotNull("Expected ModalBottomSheet Dialog window", dialogWindow)
        assertTrue(
            "Expected FLAG_SECURE on ModalBottomSheet Dialog window while credential entry is active",
            (dialogWindow!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        )
        assertTrue(
            "Expected FLAG_SECURE on host Activity window while credential entry is active",
            (composeTestRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        )
        showModal = false
        composeTestRule.waitForIdle()
        assertEquals(0, dialogWindow.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        assertEquals(0, composeTestRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test
    fun testModeSpecificCredentialLabelsAndFlagSecureDuringLiveAuthReview() {
        var isLiveWorkflow by mutableStateOf(false)
        var showReview by mutableStateOf(false)
        var modeState by mutableStateOf<PilotModeState>(
            PilotModeState.AuthorizedReady("MOCK-OFFLINE-PILOT-1", 1_800_000L)
        )

        composeTestRule.setContent {
            WeBackTheme(dynamicColor = false) {
                PilotReadinessSheet(
                    state = PilotSheetUiState(
                        modeState = modeState,
                        authorizationId = if (isLiveWorkflow) "FCT004C-EXT-01" else "MOCK-OFFLINE-PILOT-1",
                        modeLabel = if (isLiveWorkflow) "PILOT LIVE MODE" else "OFFLINE MOCK MODE",
                        approvedAuthOrigin = "https://user.grit-cloud.com:443",
                        pinnedApiOrigin = null,
                        pinnedWssOrigin = null,
                        remainingObservationMs = 900_000L,
                        connectionState = ConnectionState.Disconnected(),
                        inventoryState = InventoryState.Idle,
                        selectedDevice = null,
                        lastAbortReason = null,
                        lastDisconnectResult = null,
                        isLivePilotSupported = true,
                        isLiveWorkflowActive = isLiveWorkflow,
                        showLiveAuthReview = showReview
                    ),
                    onSubmitCredentials = {},
                    onCancelCredentialEntry = {},
                    onRefreshInventory = {},
                    onSelectDevice = {},
                    onEmergencyDisconnect = {},
                    onDismissSheet = {},
                    onActivateLivePilot = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Mock mode displays synthetic labels and NOT live real-server labels
        composeTestRule.onNodeWithText("Synthetic Credential Entry", substring = true).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Synthetic Password", substring = true).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Real-Server Authentication", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Real-Server Account Password", substring = true).assertDoesNotExist()

        // 2. Live mode displays real-server labels and NOT synthetic labels
        isLiveWorkflow = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Real-Server Authentication", substring = true).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Real-Server Account Password", substring = true).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Authenticate Live Account (1 Attempt)", substring = true).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Synthetic Credential Entry", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Synthetic Password", substring = true).assertDoesNotExist()

        // 3. FLAG_SECURE MUST be active during sensitive live authorization review even when OfflineDefault
        modeState = PilotModeState.OfflineDefault
        showReview = true
        composeTestRule.waitForIdle()
        assertTrue(
            "FLAG_SECURE must be active during live authorization review",
            (composeTestRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        )

        showReview = false
        composeTestRule.waitForIdle()
        assertEquals(
            0,
            composeTestRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE
        )
    }
}
