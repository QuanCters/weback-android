package com.weback.robot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weback.robot.domain.pilot.GateActivationResult
import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotModeState
import com.weback.robot.ui.DashboardViewModel
import com.weback.robot.ui.pilot.EphemeralPasswordBuffer
import com.weback.robot.ui.pilot.PilotReadinessSheet
import com.weback.robot.ui.pilot.PilotSheetUiState
import com.weback.robot.ui.screens.DashboardScreen
import com.weback.robot.ui.theme.WeBackTheme
import kotlinx.coroutines.launch

private enum class PilotUiWorkflow {
    MOCK,
    LIVE
}

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
            val livePilotController = app.container.pilotSessionController
            val isLivePilotSupported = app.container.isLivePilotSupported
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            var showPilotSheet by remember { mutableStateOf(false) }
            var activeWorkflow by remember { mutableStateOf(PilotUiWorkflow.MOCK) }
            var showLiveAuthReview by remember { mutableStateOf(false) }
            var liveAuthValidationError by remember { mutableStateOf<String?>(null) }

            val activeController = if (isLivePilotSupported && activeWorkflow == PilotUiWorkflow.LIVE) {
                livePilotController
            } else {
                mockPilotController
            }
            val activeGate = if (isLivePilotSupported && activeWorkflow == PilotUiWorkflow.LIVE) {
                app.container.pilotAuthorizationGate
            } else {
                app.container.offlineMockAuthorizationGate
            }

            val pilotMode by activeController.pilotModeState.collectAsStateWithLifecycle()
            val remainingMs by activeController.remainingObservationMs.collectAsStateWithLifecycle()
            val connectionState by activeController.connectionState.collectAsStateWithLifecycle()
            val inventoryState by activeController.inventoryState.collectAsStateWithLifecycle()
            val selectedDevice by activeController.selectedDevice.collectAsStateWithLifecycle()
            val lastAbort by activeController.lastAbortReason.collectAsStateWithLifecycle()
            val lastDisconnect by activeController.lastDisconnectResult.collectAsStateWithLifecycle()
            val lastObservedOrigins by activeController.lastObservedOrigins.collectAsStateWithLifecycle()
            val lastTargetInventory by activeController.lastTargetInventory.collectAsStateWithLifecycle()

            val passwordBuffer = remember { EphemeralPasswordBuffer() }
            val coroutineScope = rememberCoroutineScope()

            WeBackTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    DashboardScreen(
                        uiState = uiState,
                        onIntent = viewModel::onIntent,
                        onOpenPilotSheet = { showPilotSheet = true },
                        pilotButtonLabel = if (isLivePilotSupported) {
                            "Pilot Readiness (Offline / Live Review)"
                        } else {
                            "Pilot Readiness (Offline / Mock)"
                        }
                    )

                    if (showPilotSheet) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            PilotReadinessSheet(
                                state = PilotSheetUiState(
                                    modeState = pilotMode,
                                    authorizationId = activeGate.currentContext?.authorizationId
                                        ?: "DISABLED (OFFLINE)",
                                    modeLabel = if (isLivePilotSupported && activeWorkflow == PilotUiWorkflow.LIVE) {
                                        "PILOT BUILD (${BuildConfig.APPLICATION_ID}) — CONTROLLED AUTH & SINGLE INVENTORY (ZERO WS)"
                                    } else if (BuildConfig.PILOT_INTERNET_CAPABLE) {
                                        "PILOT BUILD (${BuildConfig.APPLICATION_ID}) — OFFLINE DEFAULT / SYNTHETIC MOCK WORKFLOW ONLY"
                                    } else {
                                        "OFFLINE / MOCK PILOT WORKFLOW — SYNTHETIC FIXTURES ONLY (NO NETWORK)"
                                    },
                                    approvedAuthOrigin = activeGate.currentContext
                                        ?.endpointConfig?.approvedAuthOrigin ?: "DISABLED",
                                    pinnedApiOrigin = activeGate.currentContext
                                        ?.endpointConfig?.pinnedApiOrigin,
                                    pinnedWssOrigin = activeGate.currentContext
                                        ?.endpointConfig?.pinnedWssOrigin,
                                    quarantinedSummary = (pilotMode as? PilotModeState.AwaitingOriginConfirmation)?.observedOrigins
                                        ?: lastObservedOrigins,
                                    remainingObservationMs = remainingMs,
                                    connectionState = connectionState,
                                    inventoryState = inventoryState,
                                    selectedDevice = selectedDevice,
                                    lastAbortReason = lastAbort,
                                    lastDisconnectResult = lastDisconnect,
                                    isLivePilotSupported = isLivePilotSupported,
                                    isLiveWorkflowActive = isLivePilotSupported && activeWorkflow == PilotUiWorkflow.LIVE,
                                    showLiveAuthReview = showLiveAuthReview,
                                    liveAuthValidationError = liveAuthValidationError,
                                    targetInventorySummary = (pilotMode as? PilotModeState.InventoryVerified)?.targetSummary
                                        ?: lastTargetInventory
                                ),
                                passwordBuffer = passwordBuffer,
                                onSubmitCredentials = { creds ->
                                    coroutineScope.launch {
                                        activeController.submitCredentials(creds)
                                    }
                                },
                                onCancelCredentialEntry = {
                                    coroutineScope.launch {
                                        activeController.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect)
                                    }
                                },
                                onRefreshInventory = {
                                    coroutineScope.launch {
                                        activeController.refreshReadOnlyInventory()
                                    }
                                },
                                onSelectDevice = { thingName ->
                                    activeController.selectAndObserveDevice(thingName)
                                },
                                onEmergencyDisconnect = {
                                    coroutineScope.launch {
                                        activeController.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect)
                                    }
                                },
                                onDismissSheet = {
                                    showLiveAuthReview = false
                                    liveAuthValidationError = null
                                    showPilotSheet = false
                                },
                                onStartMockPilot = {
                                    activeWorkflow = PilotUiWorkflow.MOCK
                                    showLiveAuthReview = false
                                    liveAuthValidationError = null
                                    app.container.startOfflineMockPilot()
                                },
                                onOpenLiveAuthReview = if (isLivePilotSupported) {
                                    {
                                        activeWorkflow = PilotUiWorkflow.LIVE
                                        showLiveAuthReview = true
                                        liveAuthValidationError = null
                                    }
                                } else {
                                    null
                                },
                                onCancelLiveAuthReview = {
                                    showLiveAuthReview = false
                                    liveAuthValidationError = null
                                    if (!app.container.pilotAuthorizationGate.isAuthorized &&
                                        lastObservedOrigins == null &&
                                        lastAbort == null
                                    ) {
                                        activeWorkflow = PilotUiWorkflow.MOCK
                                    }
                                },
                                onActivateLivePilot = if (isLivePilotSupported) {
                                    { req ->
                                        when (val res = app.container.activateLivePilot(req)) {
                                            is GateActivationResult.Activated -> {
                                                activeWorkflow = PilotUiWorkflow.LIVE
                                                showLiveAuthReview = false
                                                liveAuthValidationError = null
                                            }
                                            is GateActivationResult.Rejected -> {
                                                liveAuthValidationError = res.safeMessage
                                            }
                                        }
                                    }
                                } else {
                                    null
                                },
                                onFetchTargetInventory = if (isLivePilotSupported) {
                                    {
                                        coroutineScope.launch {
                                            livePilotController.fetchAndValidateTargetInventory()
                                        }
                                    }
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
