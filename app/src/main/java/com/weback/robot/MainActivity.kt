package com.weback.robot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.weback.robot.domain.pilot.GateActivationResult
import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotModeState
import com.weback.robot.security.ProvisionedAuthorizationLoadResult
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

    override fun onResume() {
        super.onResume()
        val app = application as? WeBackApplication ?: return
        lifecycleScope.launch {
            app.container.pilotSessionController.enforceDeadlineIfExpired()
            app.container.offlineMockPilotController.enforceDeadlineIfExpired()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val app = application as WeBackApplication
            val mockPilotController = app.container.offlineMockPilotController
            val livePilotController = app.container.pilotSessionController
            val isLivePilotSupported = app.container.isLivePilotSupported
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            val livePilotMode by livePilotController.pilotModeState.collectAsStateWithLifecycle()
            val liveOperationInFlight by livePilotController.isOperationInFlight.collectAsStateWithLifecycle()
            val liveLastDisconnect by livePilotController.lastDisconnectResult.collectAsStateWithLifecycle()
            val liveCleanupInFlightOrIncomplete = isLivePilotSupported && (
                app.container.pilotAuthorizationGate.hasInFlightOrIncompleteCleanup() ||
                    liveLastDisconnect?.storageCleared == false
                )
            val liveSessionActive = isLivePilotSupported && (
                app.container.pilotAuthorizationGate.isAuthorized ||
                    liveOperationInFlight ||
                    (livePilotMode !is PilotModeState.OfflineDefault && livePilotMode !is PilotModeState.Aborted)
                )
            val hasUnresolvedLiveSession = liveSessionActive || liveCleanupInFlightOrIncomplete

            var showPilotSheet by remember { mutableStateOf(hasUnresolvedLiveSession) }
            var activeWorkflow by remember {
                mutableStateOf(if (hasUnresolvedLiveSession) PilotUiWorkflow.LIVE else PilotUiWorkflow.MOCK)
            }
            var showLiveAuthReview by remember { mutableStateOf(false) }
            var provisionedAuthResult by remember { mutableStateOf<ProvisionedAuthorizationLoadResult?>(null) }
            var liveAuthValidationError by remember { mutableStateOf<String?>(null) }
            var liveLifecycleNotice by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(hasUnresolvedLiveSession) {
                if (hasUnresolvedLiveSession) {
                    activeWorkflow = PilotUiWorkflow.LIVE
                }
            }
            LaunchedEffect(liveSessionActive) {
                if (liveSessionActive) {
                    showPilotSheet = true
                }
            }

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

            val cleanupFailureNotice = if (isLivePilotSupported &&
                (app.container.pilotAuthorizationGate.hasIncompleteCleanup() || liveLastDisconnect?.storageCleared == false)
            ) {
                "Live pilot storage cleanup incomplete (${liveLastDisconnect?.incompleteStorageReason ?: "STORAGE_CLEAR_PENDING"}). " +
                    "Mock switching and live reactivation remain blocked until resolved."
            } else {
                null
            }

            BackHandler(enabled = showPilotSheet) {
                when {
                    showLiveAuthReview -> {
                        showLiveAuthReview = false
                        liveAuthValidationError = null
                        liveLifecycleNotice = null
                        if (!hasUnresolvedLiveSession && lastObservedOrigins == null && lastAbort == null) {
                            activeWorkflow = PilotUiWorkflow.MOCK
                        }
                    }
                    isLivePilotSupported && activeWorkflow == PilotUiWorkflow.LIVE && hasUnresolvedLiveSession -> {
                        liveLifecycleNotice =
                            "Active or unresolved live pilot session cannot be hidden with Back; use Emergency Disconnect & Wipe or Close."
                    }
                    else -> {
                        showLiveAuthReview = false
                        liveAuthValidationError = null
                        liveLifecycleNotice = null
                        showPilotSheet = false
                    }
                }
            }

            WeBackTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    DashboardScreen(
                        uiState = uiState,
                        onIntent = viewModel::onIntent,
                        onOpenPilotSheet = {
                            if (hasUnresolvedLiveSession) {
                                activeWorkflow = PilotUiWorkflow.LIVE
                            }
                            showPilotSheet = true
                        },
                        pilotButtonLabel = if (isLivePilotSupported) {
                            "Pilot Readiness (Offline / Live Review)"
                        } else {
                            "Pilot Readiness (Offline / Mock)"
                        },
                        unresolvedCleanupNotice = cleanupFailureNotice
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
                                    provisionedAuthLoadResult = provisionedAuthResult,
                                    liveAuthValidationError = liveAuthValidationError,
                                    isLiveOperationInFlight = isLivePilotSupported && liveOperationInFlight,
                                    hasUnresolvedLiveSession = hasUnresolvedLiveSession,
                                    liveLifecycleNotice = liveLifecycleNotice,
                                    targetInventorySummary = (pilotMode as? PilotModeState.InventoryVerified)?.targetSummary
                                        ?: lastTargetInventory
                                ),
                                passwordBuffer = passwordBuffer,
                                onSubmitCredentials = { creds ->
                                    liveLifecycleNotice = null
                                    coroutineScope.launch {
                                        activeController.submitCredentials(creds)
                                    }
                                },
                                onCancelCredentialEntry = {
                                    liveLifecycleNotice = null
                                    activeController.revokeAndBeginCleanupAsync(PilotAbortReason.UserEmergencyDisconnect)
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
                                    liveLifecycleNotice = null
                                    if (activeController === livePilotController &&
                                        (app.container.pilotAuthorizationGate.hasIncompleteCleanup() ||
                                            liveLastDisconnect?.storageCleared == false)
                                    ) {
                                        coroutineScope.launch {
                                            activeController.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect)
                                        }
                                    } else {
                                        activeController.revokeAndBeginCleanupAsync(PilotAbortReason.UserEmergencyDisconnect)
                                    }
                                },
                                onDismissSheet = {
                                    if (isLivePilotSupported && activeWorkflow == PilotUiWorkflow.LIVE) {
                                        if (liveSessionActive) {
                                            livePilotController.revokeAndBeginCleanupAsync(PilotAbortReason.UserEmergencyDisconnect)
                                        }
                                        if (app.container.pilotAuthorizationGate.hasIncompleteCleanup() ||
                                            liveLastDisconnect?.storageCleared == false
                                        ) {
                                            liveLifecycleNotice =
                                                "Live storage cleanup failed; resolve cleanup before dismissing."
                                            return@PilotReadinessSheet
                                        }
                                    }
                                    showLiveAuthReview = false
                                    liveAuthValidationError = null
                                    liveLifecycleNotice = null
                                    showPilotSheet = false
                                },
                                onStartMockPilot = {
                                    if (hasUnresolvedLiveSession) {
                                        liveLifecycleNotice =
                                            "Cannot switch to offline mock pilot while live session or cleanup is unresolved."
                                        return@PilotReadinessSheet
                                    }
                                    activeWorkflow = PilotUiWorkflow.MOCK
                                    showLiveAuthReview = false
                                    liveAuthValidationError = null
                                    liveLifecycleNotice = null
                                    app.container.startOfflineMockPilot()
                                },
                                onStartShortMockPilot = {
                                    if (hasUnresolvedLiveSession) {
                                        liveLifecycleNotice =
                                            "Cannot switch to offline mock pilot while live session or cleanup is unresolved."
                                        return@PilotReadinessSheet
                                    }
                                    activeWorkflow = PilotUiWorkflow.MOCK
                                    showLiveAuthReview = false
                                    liveAuthValidationError = null
                                    liveLifecycleNotice = null
                                    app.container.startOfflineMockPilot(observationWindowMs = 60_000L)
                                },
                                onOpenLiveAuthReview = if (isLivePilotSupported) {
                                    {
                                        activeWorkflow = PilotUiWorkflow.LIVE
                                        showLiveAuthReview = true
                                        liveAuthValidationError = null
                                        provisionedAuthResult = app.container.loadProvisionedLiveAuthorization()
                                    }
                                } else {
                                    null
                                },
                                onRefreshProvisionedAuth = if (isLivePilotSupported) {
                                    {
                                        liveAuthValidationError = null
                                        provisionedAuthResult = app.container.loadProvisionedLiveAuthorization()
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
                                    { expectedSha ->
                                        when (val res = app.container.activateLivePilot(expectedSha)) {
                                            is GateActivationResult.Activated -> {
                                                activeWorkflow = PilotUiWorkflow.LIVE
                                                showLiveAuthReview = false
                                                liveAuthValidationError = null
                                                provisionedAuthResult = null
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
