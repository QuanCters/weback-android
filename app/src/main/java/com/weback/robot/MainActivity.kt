package com.weback.robot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val app = application as WeBackApplication
            val mockPilotController = app.container.offlineMockPilotController
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val pilotMode by mockPilotController.pilotModeState.collectAsStateWithLifecycle()
            val remainingMs by mockPilotController.remainingObservationMs.collectAsStateWithLifecycle()
            val connectionState by mockPilotController.connectionState.collectAsStateWithLifecycle()
            val inventoryState by mockPilotController.inventoryState.collectAsStateWithLifecycle()
            val selectedDevice by mockPilotController.selectedDevice.collectAsStateWithLifecycle()
            val lastAbort by mockPilotController.lastAbortReason.collectAsStateWithLifecycle()
            val lastDisconnect by mockPilotController.lastDisconnectResult.collectAsStateWithLifecycle()

            var showPilotSheet by remember { mutableStateOf(false) }
            val passwordBuffer = remember { EphemeralPasswordBuffer() }
            val coroutineScope = rememberCoroutineScope()

            WeBackTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    DashboardScreen(
                        uiState = uiState,
                        onIntent = viewModel::onIntent,
                        onOpenPilotSheet = { showPilotSheet = true }
                    )

                    if (showPilotSheet) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                                PilotReadinessSheet(
                                    state = PilotSheetUiState(
                                        modeState = pilotMode,
                                        authorizationId = app.container.offlineMockAuthorizationGate.currentContext?.authorizationId
                                            ?: "DISABLED (OFFLINE)",
                                        modeLabel = "OFFLINE / MOCK PILOT WORKFLOW — SYNTHETIC FIXTURES ONLY (NO NETWORK)",
                                        approvedAuthOrigin = app.container.offlineMockAuthorizationGate.currentContext
                                            ?.endpointConfig?.approvedAuthOrigin ?: "DISABLED",
                                        pinnedApiOrigin = app.container.offlineMockAuthorizationGate.currentContext
                                            ?.endpointConfig?.pinnedApiOrigin,
                                        pinnedWssOrigin = app.container.offlineMockAuthorizationGate.currentContext
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
                                            mockPilotController.submitCredentials(creds)
                                        }
                                    },
                                    onCancelCredentialEntry = {
                                        coroutineScope.launch {
                                            mockPilotController.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect)
                                        }
                                    },
                                    onRefreshInventory = {
                                        coroutineScope.launch {
                                            mockPilotController.refreshReadOnlyInventory()
                                        }
                                    },
                                    onSelectDevice = { thingName ->
                                        mockPilotController.selectAndObserveDevice(thingName)
                                    },
                                    onEmergencyDisconnect = {
                                        coroutineScope.launch {
                                            mockPilotController.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect)
                                        }
                                    },
                                    onDismissSheet = { showPilotSheet = false },
                                    onStartMockPilot = {
                                        app.container.startOfflineMockPilot()
                                    }
                                )
                        }
                    }
                }
            }
        }
    }
}
