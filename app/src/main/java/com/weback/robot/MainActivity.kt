package com.weback.robot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotModeState
import com.weback.robot.ui.DashboardViewModel
import com.weback.robot.ui.pilot.EphemeralPasswordBuffer
import com.weback.robot.ui.pilot.PilotReadinessSheet
import com.weback.robot.ui.pilot.PilotSheetUiState
import com.weback.robot.ui.screens.DashboardScreen
import com.weback.robot.ui.theme.WeBackTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: DashboardViewModel by viewModels {
        val app = application as WeBackApplication
        DashboardViewModel.Factory(
            robotTransport = app.container.robotTransport,
            dispatchers = app.container.dispatcherProvider,
            clock = app.container.clock,
            appVersion = getString(R.string.app_version)
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val app = application as WeBackApplication
            val pilotController = app.container.pilotSessionController
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val pilotMode by pilotController.pilotModeState.collectAsStateWithLifecycle()
            val remainingMs by pilotController.remainingObservationMs.collectAsStateWithLifecycle()
            val connectionState by pilotController.connectionState.collectAsStateWithLifecycle()
            val inventoryState by pilotController.inventoryState.collectAsStateWithLifecycle()
            val selectedDevice by pilotController.selectedDevice.collectAsStateWithLifecycle()
            val lastAbort by pilotController.lastAbortReason.collectAsStateWithLifecycle()
            val lastDisconnect by pilotController.lastDisconnectResult.collectAsStateWithLifecycle()

            var showPilotSheet by remember { mutableStateOf(false) }
            val passwordBuffer = remember { EphemeralPasswordBuffer() }
            val coroutineScope = rememberCoroutineScope()

            WeBackTheme {
                DashboardScreen(
                    uiState = uiState,
                    onIntent = viewModel::onIntent,
                    onOpenPilotSheet = { showPilotSheet = true }
                )

                if (showPilotSheet) {
                    ModalBottomSheet(onDismissRequest = { showPilotSheet = false }) {
                        PilotReadinessSheet(
                            state = PilotSheetUiState(
                                modeState = pilotMode,
                                authorizationId = app.container.pilotAuthorizationGate.currentContext?.authorizationId
                                    ?: "DISABLED (OFFLINE)",
                                modeLabel = "OFFLINE / MOCK PILOT BOUNDARY — NO INTERNET PERMISSION",
                                approvedAuthOrigin = app.container.pilotAuthorizationGate.currentContext
                                    ?.endpointConfig?.approvedAuthOrigin ?: "DISABLED",
                                pinnedApiOrigin = app.container.pilotAuthorizationGate.currentContext
                                    ?.endpointConfig?.pinnedApiOrigin,
                                pinnedWssOrigin = app.container.pilotAuthorizationGate.currentContext
                                    ?.endpointConfig?.pinnedWssOrigin,
                                quarantinedSummary = (pilotMode as? PilotModeState.AwaitingOriginConfirmation)?.observedOrigins,
                                remainingObservationMs = remainingMs,
                                connectionState = connectionState,
                                inventoryState = inventoryState,
                                selectedDevice = selectedDevice,
                                lastAbortReason = lastAbort,
                                lastDisconnectResult = lastDisconnect
                            ),
                            passwordBuffer = passwordBuffer,
                            onSubmitCredentials = { creds ->
                                coroutineScope.launch {
                                    pilotController.submitCredentials(creds)
                                }
                            },
                            onCancelCredentialEntry = {
                                coroutineScope.launch {
                                    pilotController.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect)
                                }
                            },
                            onRefreshInventory = {
                                coroutineScope.launch {
                                    pilotController.refreshReadOnlyInventory()
                                }
                            },
                            onSelectDevice = { thingName ->
                                pilotController.selectAndObserveDevice(thingName)
                            },
                            onEmergencyDisconnect = {
                                coroutineScope.launch {
                                    pilotController.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect)
                                }
                            },
                            onDismissSheet = { showPilotSheet = false }
                        )
                    }
                }
            }
        }
    }
}
