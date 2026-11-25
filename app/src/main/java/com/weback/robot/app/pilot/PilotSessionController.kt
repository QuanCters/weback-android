package com.weback.robot.app.pilot

import com.weback.robot.app.session.DeviceInventoryRepository
import com.weback.robot.app.session.SessionCoordinator
import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.DefaultDispatcherProvider
import com.weback.robot.core.DispatcherProvider
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.CommandResult
import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.model.ConnectivityError
import com.weback.robot.domain.model.DeviceInventoryItem
import com.weback.robot.domain.model.InventoryState
import com.weback.robot.domain.model.LogoutResult
import com.weback.robot.domain.model.RobotCommand
import com.weback.robot.domain.model.RobotState
import com.weback.robot.domain.model.SessionState
import com.weback.robot.domain.pilot.ExpectedCandidateIdentity
import com.weback.robot.domain.pilot.GateActivationResult
import com.weback.robot.domain.pilot.GateCheckResult
import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotAuthorizationContext
import com.weback.robot.domain.pilot.PilotAuthorizationGate
import com.weback.robot.domain.pilot.PilotEmergencyDisconnectResult
import com.weback.robot.domain.pilot.PilotModeState
import com.weback.robot.domain.pilot.PilotOperation
import com.weback.robot.domain.pilot.ReadOnlyPilotGuard
import com.weback.robot.domain.pilot.ReadOnlyTelemetrySnapshot
import com.weback.robot.protocol.RobotTransport
import com.weback.robot.protocol.pilot.PilotCapabilityRestrictedAuthGateway
import com.weback.robot.protocol.pilot.PilotReadOnlyTelemetryTransport
import com.weback.robot.protocol.pilot.PilotReadOnlyWebSocketTransport
import com.weback.robot.protocol.ws.OkHttpWebSocketTransport
import com.weback.robot.security.AuthorizedPilotEndpointTrustPolicy
import com.weback.robot.security.DiagnosticEvent
import com.weback.robot.security.DiagnosticLogger
import com.weback.robot.security.DynamicOriginConfirmationResult
import com.weback.robot.security.NoOpDiagnosticLogger
import com.weback.robot.security.OriginProvenanceVerifier
import com.weback.robot.security.PilotActiveCallRegistry
import com.weback.robot.security.PilotSanitizedExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinates opt-in bounded pilot lifecycle (`SEC-401`..`SEC-406`):
 * - Holds ONLY [PilotReadOnlyTelemetryTransport] (never exposes outbound WS command sending).
 * - Enforces one-shot login/inventory budgets, cumulative WS reconnect caps, and monotonic deadline.
 * - Executes 9-step non-blocking Emergency Disconnect & Wipe revoking network before disk I/O.
 */
class PilotSessionController(
    private val gate: PilotAuthorizationGate,
    private val expectedRuntimeIdentity: ExpectedCandidateIdentity,
    private val trustPolicy: AuthorizedPilotEndpointTrustPolicy,
    private val sessionCoordinator: SessionCoordinator,
    private val inventoryRepository: DeviceInventoryRepository,
    private val pilotAuthGateway: PilotCapabilityRestrictedAuthGateway,
    pilotWsDelegate: OkHttpWebSocketTransport,
    private val simulatorTransport: RobotTransport,
    private val authClock: AuthMonotonicClock,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider(),
    private val logger: DiagnosticLogger = NoOpDiagnosticLogger,
    private val activeCallRegistry: PilotActiveCallRegistry? = null,
    private val exporter: PilotSanitizedExporter = PilotSanitizedExporter(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default),
    private val ownsScope: Boolean = false
) : AutoCloseable {
    private val stateLock = Any()

    private var pilotEpoch: Long = 0L
    private var loginAttemptsUsed: Int = 0
    private var inventoryRequestsUsed: Int = 0
    private var initialWsConnectionsUsed: Int = 0
    private var deadlineJob: Job? = null
    private var observedApiOrigin: String? = null
    private var observedWssOrigin: String? = null
    private var lastAuthorizationId: String? = null
    private var lastSensitiveThingName: String? = null
    private var lastSensitiveAlias: String? = null

    private val _pilotModeState = MutableStateFlow<PilotModeState>(PilotModeState.OfflineDefault)
    val pilotModeState: StateFlow<PilotModeState> = _pilotModeState.asStateFlow()

    private val _lastAbortReason = MutableStateFlow<PilotAbortReason?>(null)
    val lastAbortReason: StateFlow<PilotAbortReason?> = _lastAbortReason.asStateFlow()

    private val _lastDisconnectResult = MutableStateFlow<PilotEmergencyDisconnectResult?>(null)
    val lastDisconnectResult: StateFlow<PilotEmergencyDisconnectResult?> = _lastDisconnectResult.asStateFlow()

    private val _remainingObservationMs = MutableStateFlow(0L)
    val remainingObservationMs: StateFlow<Long> = _remainingObservationMs.asStateFlow()

    val pilotTelemetryTransport: PilotReadOnlyTelemetryTransport = PilotReadOnlyWebSocketTransport(
        delegate = pilotWsDelegate,
        onUnexpectedOutboundAttempt = { detail ->
            logger.log(DiagnosticEvent(category = "PILOT_OUTBOUND_BLOCKED", errorCategory = detail))
            revokeSynchronouslyAndLaunchCleanup(PilotAbortReason.UnexpectedOutboundCommandAttempt)
        }
    )

    val readOnlyPilotGuard: ReadOnlyPilotGuard = ReadOnlyPilotGuard(
        isPilotReadOnlyActive = { gate.isAuthorized },
        onUnexpectedCommandAttempt = { cmd, reason ->
            logger.log(DiagnosticEvent(category = "PILOT_COMMAND_ABORT", opt = cmd.name, errorCategory = reason.safeCode))
            revokeSynchronouslyAndLaunchCleanup(reason)
        }
    )

    val sessionState: StateFlow<SessionState> = sessionCoordinator.sessionState
    val inventoryState: StateFlow<InventoryState> = inventoryRepository.inventoryState
    val selectedDevice: StateFlow<DeviceInventoryItem?> = inventoryRepository.selectedDevice
    val connectionState: StateFlow<ConnectionState> = pilotTelemetryTransport.connectionState
    val pilotRobotState: StateFlow<RobotState> = pilotTelemetryTransport.robotState
    val simulatorRobotState: StateFlow<RobotState> = simulatorTransport.robotState

    init {
        scope.launch(dispatchers.default) {
            pilotTelemetryTransport.robotState.collect { robotState ->
                synchronized(stateLock) {
                    val currentMode = _pilotModeState.value
                    val ctx = gate.currentContext
                    val selected = inventoryRepository.selectedDevice.value
                    if (currentMode is PilotModeState.Observing && ctx != null && selected != null) {
                        val snapshot = ReadOnlyTelemetrySnapshot(
                            thingNameRedacted = ctx.targetDevice.redactedTag,
                            nickname = if (selected.nickname == selected.thingName) ctx.targetDevice.redactedTag else selected.nickname,
                            normalizedSubType = selected.capability.normalizedSubType,
                            isAdmin = selected.isAdmin,
                            compatibility = selected.capability.compatibility,
                            batteryPercentage = robotState.batteryPercentage,
                            mode = robotState.mode,
                            errorCode = robotState.errorCode
                        )
                        val remaining = (ctx.observationDeadlineElapsedMs - authClock.elapsedRealtimeMs()).coerceAtLeast(0L)
                        _remainingObservationMs.value = remaining
                        _pilotModeState.value = currentMode.copy(
                            remainingWindowMs = remaining,
                            selectedSnapshot = snapshot
                        )
                    }
                }
            }
        }
    }

    fun activatePilot(
        context: PilotAuthorizationContext,
        isUiCheckboxOnly: Boolean = false
    ): GateActivationResult {
        val nowMs = authClock.elapsedRealtimeMs()
        val result = gate.activate(context, expectedRuntimeIdentity, nowMs, isUiCheckboxOnly)
        if (result is GateActivationResult.Rejected) {
            synchronized(stateLock) {
                _lastAbortReason.value = result.reason
            }
            return result
        }
        trustPolicy.updateEndpointConfig(context.endpointConfig)

        val remaining = (context.observationDeadlineElapsedMs - nowMs).coerceAtLeast(0L)
        val epoch = synchronized(stateLock) {
            deadlineJob?.cancel()
            pilotEpoch++
            loginAttemptsUsed = 0
            inventoryRequestsUsed = 0
            initialWsConnectionsUsed = 0
            observedApiOrigin = null
            observedWssOrigin = null
            lastAuthorizationId = context.authorizationId
            lastSensitiveThingName = context.targetDevice.expectedTargetThingName
            lastSensitiveAlias = context.accountScope.authorizedAccountAlias
            _lastAbortReason.value = null
            _lastDisconnectResult.value = null
            _remainingObservationMs.value = remaining
            _pilotModeState.value = PilotModeState.AuthorizedReady(context.authorizationId, remaining)
            pilotEpoch
        }

        scheduleObservationDeadline(epoch, remaining)
        return result
    }

    suspend fun submitCredentials(credentials: AuthCredentials): AuthResult {
        val capturedEpoch: Long
        val ctx: PilotAuthorizationContext
        val abortNow: PilotAbortReason?
        val abortMessage: String
        synchronized(stateLock) {
            val check = gate.authorizeOperation(PilotOperation.LOGIN, authClock.elapsedRealtimeMs())
            when {
                check is GateCheckResult.Denied -> {
                    abortNow = check.reason
                    abortMessage = check.safeMessage
                    ctx = gate.currentContext ?: return AuthResult.Failure(
                        AuthFailureReason.ConfigurationError,
                        check.safeMessage
                    ).also {
                        credentials.wipeSecret()
                        abortStateLocked(check.reason, check.safeMessage)
                    }
                    capturedEpoch = pilotEpoch
                }
                credentials.regionCallingCode.trim() != (check as GateCheckResult.Allowed).context.accountScope.regionCallingCode.trim() -> {
                    ctx = check.context
                    abortNow = PilotAbortReason.AuthorizationExpiredOrInvalid
                    abortMessage = "Region calling code mismatches authorized account scope"
                    capturedEpoch = pilotEpoch
                }
                loginAttemptsUsed >= check.context.budgets.maxLoginAttempts -> {
                    ctx = check.context
                    abortNow = PilotAbortReason.BudgetExhausted
                    abortMessage = "Login budget exhausted (max ${check.context.budgets.maxLoginAttempts})"
                    capturedEpoch = pilotEpoch
                }
                else -> {
                    ctx = check.context
                    loginAttemptsUsed++
                    capturedEpoch = pilotEpoch
                    abortNow = null
                    abortMessage = ""
                }
            }
        }

        if (abortNow != null) {
            credentials.wipeSecret()
            emergencyDisconnectAndWipe(abortNow)
            return AuthResult.Failure(AuthFailureReason.ConfigurationError, abortMessage)
        }

        val authResult = try {
            sessionCoordinator.signIn(credentials)
        } finally {
            credentials.wipeSecret()
        }

        // Discard late callback if Emergency Disconnect or epoch change occurred while in-flight
        val stillCurrent = synchronized(stateLock) { capturedEpoch == pilotEpoch && gate.isAuthorized }
        if (!stillCurrent) {
            if (sessionCoordinator.sessionState.value is SessionState.Authenticated) {
                sessionCoordinator.signOut()
            }
            return AuthResult.Failure(AuthFailureReason.TokenExpiredOrRevoked, "Pilot revoked during login")
        }

        val quarantined = pilotAuthGateway.consumeLastQuarantinedObservation()
        if (quarantined != null) {
            synchronized(stateLock) {
                if (capturedEpoch == pilotEpoch) {
                    observedApiOrigin = quarantined.apiOrigin
                    observedWssOrigin = quarantined.wssOrigin
                    _pilotModeState.value = PilotModeState.AwaitingOriginConfirmation(
                        authorizationId = ctx.authorizationId,
                        observedOrigins = quarantined
                    )
                }
            }
            return AuthResult.Failure(AuthFailureReason.UntrustedEndpoint, quarantined.quarantineReason)
        }

        val isTlsFailure = pilotAuthGateway.consumeLastTlsFailureFlag()
        if (authResult is AuthResult.Failure) {
            val abortReason = when {
                isTlsFailure -> PilotAbortReason.TlsHandshakeFailure
                authResult.reason == AuthFailureReason.ChallengeRequired -> PilotAbortReason.ChallengeRequired
                authResult.reason == AuthFailureReason.UntrustedEndpoint -> PilotAbortReason.UntrustedOriginRejected
                authResult.reason is AuthFailureReason.ServerRejected -> PilotAbortReason.ProtocolMismatchAbort
                else -> PilotAbortReason.AuthRejected
            }
            emergencyDisconnectAndWipe(abortReason)
            return authResult
        }

        val session = (authResult as AuthResult.Success).session
        synchronized(stateLock) {
            if (capturedEpoch == pilotEpoch) {
                observedApiOrigin = session.apiUrl
                observedWssOrigin = session.wssUrl
                val remaining = (ctx.observationDeadlineElapsedMs - authClock.elapsedRealtimeMs()).coerceAtLeast(0L)
                _remainingObservationMs.value = remaining
                _pilotModeState.value = PilotModeState.Observing(ctx.authorizationId, remaining, null)
            }
        }
        return authResult
    }

    fun confirmQuarantinedOrigins(
        apiUrl: String,
        wssUrl: String,
        humanConfirmed: Boolean,
        provenanceVerifier: OriginProvenanceVerifier
    ): DynamicOriginConfirmationResult {
        val currentState = synchronized(stateLock) { _pilotModeState.value }
        if (currentState !is PilotModeState.AwaitingOriginConfirmation || !gate.isAuthorized) {
            return DynamicOriginConfirmationResult.Rejected("PILOT_NOT_IN_QUARANTINE")
        }
        val res = trustPolicy.confirmDynamicOrigins(apiUrl, wssUrl, humanConfirmed, provenanceVerifier)
        if (res is DynamicOriginConfirmationResult.Rejected) {
            synchronized(stateLock) {
                _lastAbortReason.value = PilotAbortReason.UntrustedOriginRejected
            }
        }
        return res
    }

    suspend fun refreshReadOnlyInventory(): Boolean {
        val capturedEpoch: Long
        var abortNow: PilotAbortReason? = null
        synchronized(stateLock) {
            val check = gate.authorizeOperation(PilotOperation.USER_THING_LIST_GET, authClock.elapsedRealtimeMs())
            when {
                check is GateCheckResult.Denied -> {
                    abortNow = check.reason
                    capturedEpoch = pilotEpoch
                }
                inventoryRequestsUsed >= (check as GateCheckResult.Allowed).context.budgets.maxInventoryRequests -> {
                    abortNow = PilotAbortReason.BudgetExhausted
                    capturedEpoch = pilotEpoch
                }
                else -> {
                    inventoryRequestsUsed++
                    capturedEpoch = pilotEpoch
                }
            }
        }
        if (abortNow != null) {
            emergencyDisconnectAndWipe(abortNow!!)
            return false
        }

        val state = inventoryRepository.refreshInventory()
        val stillCurrent = synchronized(stateLock) { capturedEpoch == pilotEpoch && gate.isAuthorized }
        if (!stillCurrent) {
            return false
        }
        return state is InventoryState.Loaded || state is InventoryState.Empty
    }

    fun selectAndObserveDevice(thingName: String): Boolean {
        val check = gate.authorizeOperation(PilotOperation.WS_TELEMETRY_OBSERVE, authClock.elapsedRealtimeMs())
        if (check is GateCheckResult.Denied) {
            revokeSynchronouslyAndLaunchCleanup(check.reason)
            return false
        }
        val ctx = (check as GateCheckResult.Allowed).context
        val sessionAuth = sessionCoordinator.authorizeActiveSessionForSend() ?: return false

        val loaded = inventoryRepository.inventoryState.value as? InventoryState.Loaded ?: return false
        val candidate = loaded.items.firstOrNull { it.thingName == thingName } ?: return false

        if (!candidate.capability.compatibility.isMvpControlSupported ||
            candidate.thingName != ctx.targetDevice.expectedTargetThingName
        ) {
            return false
        }

        val capturedEpoch: Long
        var budgetExceeded = false
        synchronized(stateLock) {
            if (initialWsConnectionsUsed >= ctx.budgets.maxInitialWsConnections) {
                budgetExceeded = true
                capturedEpoch = pilotEpoch
            } else {
                initialWsConnectionsUsed++
                capturedEpoch = pilotEpoch
            }
        }
        if (budgetExceeded) {
            revokeSynchronouslyAndLaunchCleanup(PilotAbortReason.BudgetExhausted)
            return false
        }

        val selected = inventoryRepository.selectDevice(thingName, sessionAuth.epoch) ?: return false

        // Re-check authorization & epoch immediately prior to connecting (`CRIT-02` race prevention)
        val authorizedToConnect = synchronized(stateLock) {
            capturedEpoch == pilotEpoch && gate.isAuthorized
        }
        if (!authorizedToConnect) {
            return false
        }

        pilotTelemetryTransport.selectDevice(selected, sessionAuth.epoch)
        pilotTelemetryTransport.connect(sessionAuth.session, sessionAuth.epoch)

        // Post-connect race check: if emergencyDisconnectAndWipe occurred concurrently, tear down immediately
        val stillAuthorized = synchronized(stateLock) {
            if (capturedEpoch == pilotEpoch && gate.isAuthorized) {
                val rState = pilotTelemetryTransport.robotState.value
                val remaining = (ctx.observationDeadlineElapsedMs - authClock.elapsedRealtimeMs()).coerceAtLeast(0L)
                val displayNickname = if (selected.nickname == selected.thingName) ctx.targetDevice.redactedTag else selected.nickname
                _pilotModeState.value = PilotModeState.Observing(
                    authorizationId = ctx.authorizationId,
                    remainingWindowMs = remaining,
                    selectedSnapshot = ReadOnlyTelemetrySnapshot(
                        thingNameRedacted = ctx.targetDevice.redactedTag,
                        nickname = displayNickname,
                        normalizedSubType = selected.capability.normalizedSubType,
                        isAdmin = selected.isAdmin,
                        compatibility = selected.capability.compatibility,
                        batteryPercentage = rState.batteryPercentage,
                        mode = rState.mode,
                        errorCode = rState.errorCode
                    )
                )
                true
            } else {
                false
            }
        }
        if (!stillAuthorized) {
            pilotTelemetryTransport.forceDisconnect(ConnectivityError.Unauthenticated)
            return false
        }
        return true
    }

    fun dispatchPhysicalCommandInPilot(command: RobotCommand): CommandResult {
        return readOnlyPilotGuard.guardLiveCommand(command)
    }

    /**
     * 9-step non-blocking Emergency Disconnect & Wipe (`SEC-404`, `AC-408.1`):
     * Steps 1-6 revoke network/auth/calls/jobs/WS/session/inventory immediately without awaiting disk I/O.
     * Steps 7-9 execute durable cleanup, restore offline simulator, and report incomplete storage honestly.
     */
    suspend fun emergencyDisconnectAndWipe(
        reason: PilotAbortReason = PilotAbortReason.UserEmergencyDisconnect
    ): PilotEmergencyDisconnectResult {
        // Steps 1, 3, 8 (in-memory immediate revocation + job cancel + offline simulator restoration)
        synchronized(stateLock) {
            gate.revoke(reason)
            pilotEpoch++
            deadlineJob?.cancel()
            deadlineJob = null
            _lastAbortReason.value = reason
            _remainingObservationMs.value = 0L
            _pilotModeState.value = PilotModeState.OfflineDefault
        }

        // Step 2: Cancel all active HTTP calls immediately
        activeCallRegistry?.cancelAll()
        trustPolicy.clearConfirmedDynamicOrigins()

        // Step 4: Unconditionally close/cancel active or pending WebSocket regardless of epoch drift (`CRIT-02`)
        pilotTelemetryTransport.forceDisconnect(ConnectivityError.Unauthenticated)

        // Steps 5, 6, 7: Invalidate SessionCoordinator (synchronously clears memory/inventory via epoch listener,
        // then attempts NonCancellable TokenStore.clear() without ever blocking steps 1-4)
        val logoutResult = sessionCoordinator.signOut()
        val (cleared, incompleteReason) = when (logoutResult) {
            LogoutResult.DurableSuccess -> true to null
            is LogoutResult.IncompleteStorageClear -> false to logoutResult.safeCategory
        }

        val result = PilotEmergencyDisconnectResult(
            networkRevokedImmediately = true,
            storageCleared = cleared,
            incompleteStorageReason = incompleteReason,
            restoredOfflineSimulator = true,
            serverSideTokenRevocationClaimed = false
        )
        _lastDisconnectResult.value = result
        logger.log(
            DiagnosticEvent(
                category = "PILOT_EMERGENCY_DISCONNECT",
                sessionEpoch = sessionCoordinator.currentEpoch(),
                errorCategory = reason.safeCode
            )
        )
        return result
    }

    fun exportSanitizedReport(rawEvents: List<String>): String {
        val ctx = gate.currentContext
        val authId = ctx?.authorizationId ?: lastAuthorizationId ?: "DISABLED"
        val thingNames = setOfNotNull(ctx?.targetDevice?.expectedTargetThingName ?: lastSensitiveThingName)
        val aliases = setOfNotNull(ctx?.accountScope?.authorizedAccountAlias ?: lastSensitiveAlias)
        return exporter.exportReport(
            authorizationId = authId,
            modeLabel = "OFFLINE_SIMULATOR_AND_HERMETIC_PILOT_ONLY",
            rawEvents = rawEvents,
            observedApiOrigin = observedApiOrigin,
            observedWssOrigin = observedWssOrigin,
            lastAbortReason = _lastAbortReason.value,
            disconnectResult = _lastDisconnectResult.value,
            sensitiveThingNames = thingNames,
            sensitiveAliases = aliases
        )
    }

    override fun close() {
        synchronized(stateLock) {
            gate.revoke(PilotAbortReason.UserEmergencyDisconnect)
            pilotEpoch++
            deadlineJob?.cancel()
            deadlineJob = null
        }
        activeCallRegistry?.cancelAll()
        pilotTelemetryTransport.close()
        if (ownsScope) {
            scope.cancel()
        }
    }

    private fun revokeSynchronouslyAndLaunchCleanup(reason: PilotAbortReason) {
        synchronized(stateLock) {
            gate.revoke(reason)
            pilotEpoch++
            deadlineJob?.cancel()
            deadlineJob = null
            _lastAbortReason.value = reason
            _remainingObservationMs.value = 0L
            _pilotModeState.value = PilotModeState.OfflineDefault
        }
        activeCallRegistry?.cancelAll()
        trustPolicy.clearConfirmedDynamicOrigins()
        pilotTelemetryTransport.forceDisconnect(ConnectivityError.Unauthenticated)
        scope.launch(dispatchers.default) {
            emergencyDisconnectAndWipe(reason)
        }
    }

    private fun scheduleObservationDeadline(expectedEpoch: Long, delayMs: Long) {
        val job = scope.launch(dispatchers.default) {
            delay(delayMs.coerceAtLeast(1L))
            val shouldExpire = synchronized(stateLock) { expectedEpoch == pilotEpoch && gate.isAuthorized }
            if (shouldExpire) {
                emergencyDisconnectAndWipe(PilotAbortReason.ObservationWindowExpired)
            }
        }
        synchronized(stateLock) {
            if (expectedEpoch == pilotEpoch && gate.isAuthorized) {
                deadlineJob?.cancel()
                deadlineJob = job
            } else {
                job.cancel()
            }
        }
    }

    private fun abortStateLocked(reason: PilotAbortReason, safeMessage: String) {
        gate.revoke(reason)
        pilotEpoch++
        deadlineJob?.cancel()
        deadlineJob = null
        _lastAbortReason.value = reason
        _remainingObservationMs.value = 0L
        _pilotModeState.value = PilotModeState.Aborted(reason, safeMessage)
    }
}
