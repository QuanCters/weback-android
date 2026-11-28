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
import com.weback.robot.domain.pilot.AdmissionResult
import com.weback.robot.domain.pilot.ExpectedCandidateIdentity
import com.weback.robot.domain.pilot.GateActivationResult
import com.weback.robot.domain.pilot.GateCheckResult
import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotAdmissionTicket
import com.weback.robot.domain.pilot.PilotAuthorizationContext
import com.weback.robot.domain.pilot.PilotAuthorizationGate
import com.weback.robot.domain.pilot.PilotEmergencyDisconnectResult
import com.weback.robot.domain.pilot.PilotModeState
import com.weback.robot.domain.pilot.PilotOperation
import com.weback.robot.domain.pilot.ReadOnlyPilotGuard
import com.weback.robot.domain.pilot.ReadOnlyTelemetrySnapshot
import com.weback.robot.domain.pilot.RuntimeAppIdentity
import com.weback.robot.protocol.RobotTransport
import com.weback.robot.protocol.pilot.PilotAdmissionContextElement
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
import kotlinx.coroutines.withContext

/**
 * Coordinates opt-in bounded pilot lifecycle (`SEC-401`..`SEC-406`, `FIX-401`..`FIX-405`):
 * - Holds ONLY [PilotReadOnlyTelemetryTransport] (never exposes outbound WS command sending).
 * - Acquires admission tickets before login, inventory, and WS connect/reconnects without double-counting.
 * - Enforces exact account binding, non-reusable authorizations, and generation-fenced cleanup.
 */
class PilotSessionController(
    private val gate: PilotAuthorizationGate,
    private val expectedRuntimeIdentity: ExpectedCandidateIdentity? = null,
    private val trustPolicy: AuthorizedPilotEndpointTrustPolicy,
    private val sessionCoordinator: SessionCoordinator,
    private val inventoryRepository: DeviceInventoryRepository,
    private val pilotAuthGateway: PilotCapabilityRestrictedAuthGateway?,
    private val pilotWsDelegate: OkHttpWebSocketTransport?,
    private val simulatorTransport: RobotTransport,
    private val authClock: AuthMonotonicClock,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider(),
    private val logger: DiagnosticLogger = NoOpDiagnosticLogger,
    private val activeCallRegistry: PilotActiveCallRegistry? = null,
    private val exporter: PilotSanitizedExporter = PilotSanitizedExporter(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default),
    private val ownsScope: Boolean = false,
    private val telemetryTransportOverride: PilotReadOnlyTelemetryTransport? = null,
    private val modeLabel: String = "OFFLINE_SIMULATOR_AND_HERMETIC_PILOT_ONLY",
    private val enableUiCountdownTicker: Boolean = false,
    private val runtimeAppIdentity: RuntimeAppIdentity? = null
) : AutoCloseable {
    private val stateLock = Any()

    private var pilotEpoch: Long = 0L
    private var activeAuthorizedGeneration: Long = 0L
    private var lastCleanedGeneration: Long = 0L
    private var loginAttemptsUsed: Int = 0
    private var inventoryRequestsUsed: Int = 0
    private var initialWsConnectionsUsed: Int = 0
    private var deadlineJob: Job? = null
    private var countdownJob: Job? = null
    private var pendingInitialWsTicket: PilotAdmissionTicket? = null
    private var observedApiOrigin: String? = null
    private var observedWssOrigin: String? = null
    private var lastAuthorizationId: String? = null
    private var lastSensitiveThingName: String? = null
    private var lastSensitiveAlias: String? = null
    private var lastSensitiveAccountIdentifier: String? = null

    private val _pilotModeState = MutableStateFlow<PilotModeState>(PilotModeState.OfflineDefault)
    val pilotModeState: StateFlow<PilotModeState> = _pilotModeState.asStateFlow()

    private val _lastAbortReason = MutableStateFlow<PilotAbortReason?>(null)
    val lastAbortReason: StateFlow<PilotAbortReason?> = _lastAbortReason.asStateFlow()

    private val _lastDisconnectResult = MutableStateFlow<PilotEmergencyDisconnectResult?>(null)
    val lastDisconnectResult: StateFlow<PilotEmergencyDisconnectResult?> = _lastDisconnectResult.asStateFlow()

    private val _lastObservedOrigins = MutableStateFlow<com.weback.robot.domain.pilot.ObservedOriginSummary?>(null)
    val lastObservedOrigins: StateFlow<com.weback.robot.domain.pilot.ObservedOriginSummary?> = _lastObservedOrigins.asStateFlow()

    private val _lastTargetInventory = MutableStateFlow<com.weback.robot.domain.pilot.TargetInventorySummary?>(null)
    val lastTargetInventory: StateFlow<com.weback.robot.domain.pilot.TargetInventorySummary?> = _lastTargetInventory.asStateFlow()

    private val _remainingObservationMs = MutableStateFlow(0L)
    val remainingObservationMs: StateFlow<Long> = _remainingObservationMs.asStateFlow()

    private val _isOperationInFlight = MutableStateFlow(false)
    val isOperationInFlight: StateFlow<Boolean> = _isOperationInFlight.asStateFlow()

    val pilotTelemetryTransport: PilotReadOnlyTelemetryTransport =
        telemetryTransportOverride ?: PilotReadOnlyWebSocketTransport(
            delegate = requireNotNull(pilotWsDelegate) {
                "pilotWsDelegate is required unless telemetryTransportOverride is provided"
            },
            onUnexpectedOutboundAttempt = { detail ->
                val gen = gate.currentGeneration()
                logger.log(DiagnosticEvent(category = "PILOT_OUTBOUND_BLOCKED", errorCategory = detail))
                revokeSynchronouslyAndLaunchCleanup(PilotAbortReason.UnexpectedOutboundCommandAttempt, gen)
            }
        )

    val readOnlyPilotGuard: ReadOnlyPilotGuard = ReadOnlyPilotGuard(
        isPilotReadOnlyActive = { gate.isAuthorized },
        onUnexpectedCommandAttempt = { cmd, reason ->
            val gen = gate.currentGeneration()
            logger.log(DiagnosticEvent(category = "PILOT_COMMAND_ABORT", opt = cmd.name, errorCategory = reason.safeCode))
            revokeSynchronouslyAndLaunchCleanup(reason, gen)
        }
    )

    val sessionState: StateFlow<SessionState> = sessionCoordinator.sessionState
    val inventoryState: StateFlow<InventoryState> = inventoryRepository.inventoryState
    val selectedDevice: StateFlow<DeviceInventoryItem?> = inventoryRepository.selectedDevice
    val connectionState: StateFlow<ConnectionState> = pilotTelemetryTransport.connectionState
    val pilotRobotState: StateFlow<RobotState> = pilotTelemetryTransport.robotState
    val simulatorRobotState: StateFlow<RobotState> = simulatorTransport.robotState

    init {
        gate.attachOnWindowExpiredListener { revokedGen ->
            handleGateWindowExpired(revokedGen)
        }
        pilotWsDelegate?.attachPilotAdmissionPolicy(
            maxReconnectsProvider = { gate.currentContext?.budgets?.maxWsReconnectAttempts }
        ) { url, isReconnect ->
            val endpointMatcher: (PilotAuthorizationContext, String) -> Boolean = { ctx, targetUrl ->
                trustPolicy.isUrlAuthorizedForOperation(ctx, PilotOperation.WS_TELEMETRY_OBSERVE, targetUrl)
            }
            if (!isReconnect) {
                val preAdmitted = synchronized(stateLock) {
                    pendingInitialWsTicket.also { pendingInitialWsTicket = null }
                }
                if (preAdmitted != null) {
                    when (gate.verifyTicket(preAdmitted, authClock.elapsedRealtimeMs(), url, endpointMatcher)) {
                        is GateCheckResult.Allowed -> preAdmitted
                        is GateCheckResult.Denied -> null
                    }
                } else {
                    when (
                        val admitted = gate.acquireAdmission(
                            operation = PilotOperation.WS_TELEMETRY_OBSERVE,
                            nowElapsedMs = authClock.elapsedRealtimeMs(),
                            requestUrl = url,
                            isReconnect = false,
                            endpointValidator = endpointMatcher
                        )
                    ) {
                        is AdmissionResult.Admitted -> admitted.ticket
                        is AdmissionResult.Denied -> null
                    }
                }
            } else {
                when (
                    val admitted = gate.acquireAdmission(
                        operation = PilotOperation.WS_TELEMETRY_OBSERVE,
                        nowElapsedMs = authClock.elapsedRealtimeMs(),
                        requestUrl = url,
                        isReconnect = true,
                        endpointValidator = endpointMatcher
                    )
                ) {
                    is AdmissionResult.Admitted -> admitted.ticket
                    is AdmissionResult.Denied -> null
                }
            }
        }
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
        val result = when {
            expectedRuntimeIdentity != null ->
                gate.activate(context, expectedRuntimeIdentity, nowMs, isUiCheckboxOnly)
            runtimeAppIdentity != null ->
                gate.activate(context, runtimeAppIdentity, nowMs, isUiCheckboxOnly)
            else ->
                GateActivationResult.Rejected(
                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "No runtime identity configured"
                )
        }
        if (result is GateActivationResult.Rejected) {
            synchronized(stateLock) {
                _lastAbortReason.value = result.reason
            }
            return result
        }
        trustPolicy.updateEndpointConfig(context.endpointConfig)

        val remaining = (context.observationDeadlineElapsedMs - nowMs).coerceAtLeast(0L)
        val generation = gate.currentGeneration()
        val epoch = synchronized(stateLock) {
            deadlineJob?.cancel()
            countdownJob?.cancel()
            pendingInitialWsTicket = null
            pilotEpoch++
            activeAuthorizedGeneration = generation
            loginAttemptsUsed = 0
            inventoryRequestsUsed = 0
            initialWsConnectionsUsed = 0
            observedApiOrigin = null
            observedWssOrigin = null
            lastAuthorizationId = context.authorizationId
            lastSensitiveThingName = context.targetDevice.expectedTargetThingName
            lastSensitiveAlias = context.accountScope.authorizedAccountAlias
            lastSensitiveAccountIdentifier = context.accountScope.authorizedAccountIdentifier
            _lastAbortReason.value = null
            _lastDisconnectResult.value = null
            _lastObservedOrigins.value = null
            _lastTargetInventory.value = null
            _remainingObservationMs.value = remaining
            _pilotModeState.value = PilotModeState.AuthorizedReady(context.authorizationId, remaining)
            pilotEpoch
        }

        scheduleObservationDeadline(epoch, generation, context.observationDeadlineElapsedMs)
        if (enableUiCountdownTicker) {
            scheduleCountdownTicker(epoch, generation, context.observationDeadlineElapsedMs)
        }
        return result
    }

    suspend fun submitCredentials(credentials: AuthCredentials): AuthResult {
        val capturedEpoch: Long
        val capturedGeneration: Long
        val ctx: PilotAuthorizationContext?
        var abortNow: PilotAbortReason? = null
        var abortMessage = ""
        var loginTicket: PilotAdmissionTicket? = null

        synchronized(stateLock) {
            capturedEpoch = pilotEpoch
            capturedGeneration = gate.currentGeneration()
            val check = gate.authorizeOperation(PilotOperation.LOGIN, authClock.elapsedRealtimeMs())
            when {
                check is GateCheckResult.Denied -> {
                    abortNow = check.reason
                    abortMessage = check.safeMessage
                    ctx = gate.currentContext
                }
                else -> {
                    val allowedCtx = (check as GateCheckResult.Allowed).context
                    ctx = allowedCtx
                    val (accountValid, accountError) = gate.verifyAccountCredentials(
                        credentials.regionCallingCode,
                        credentials.account
                    )
                    when {
                        !accountValid -> {
                            abortNow = PilotAbortReason.AuthorizationExpiredOrInvalid
                            abortMessage = accountError
                        }
                        loginAttemptsUsed >= allowedCtx.budgets.maxLoginAttempts -> {
                            abortNow = PilotAbortReason.BudgetExhausted
                            abortMessage = "Login budget exhausted (max ${allowedCtx.budgets.maxLoginAttempts})"
                        }
                        else -> {
                            when (
                                val admitted = gate.acquireAdmission(
                                    operation = PilotOperation.LOGIN,
                                    nowElapsedMs = authClock.elapsedRealtimeMs(),
                                    expectedGeneration = capturedGeneration
                                )
                            ) {
                                is AdmissionResult.Denied -> {
                                    abortNow = admitted.reason
                                    abortMessage = admitted.safeMessage
                                }
                                is AdmissionResult.Admitted -> {
                                    loginAttemptsUsed++
                                    loginTicket = admitted.ticket
                                }
                            }
                        }
                    }
                }
            }
        }

        if (abortNow != null) {
            credentials.wipeSecret()
            if (ctx == null) {
                synchronized(stateLock) {
                    if (capturedGeneration == gate.currentGeneration()) {
                        abortStateLocked(abortNow!!, abortMessage)
                    }
                }
            } else {
                emergencyDisconnectAndWipe(abortNow!!, expectedGeneration = capturedGeneration)
            }
            return AuthResult.Failure(AuthFailureReason.ConfigurationError, abortMessage)
        }

        val activeCtx = requireNotNull(ctx)
        val ticket = requireNotNull(loginTicket)
        _isOperationInFlight.value = true
        val authResult = try {
            withContext(PilotAdmissionContextElement(ticket)) {
                sessionCoordinator.signIn(credentials)
            }
        } finally {
            credentials.wipeSecret()
            synchronized(stateLock) {
                if (capturedEpoch == pilotEpoch && capturedGeneration == gate.currentGeneration()) {
                    _isOperationInFlight.value = false
                }
            }
        }

        // Generation-fenced late callback check (`FIX-405`): NEVER sign out a newer pilot run
        val stillCurrent = synchronized(stateLock) {
            capturedEpoch == pilotEpoch &&
                capturedGeneration == gate.currentGeneration() &&
                gate.isAuthorized
        }
        if (!stillCurrent) {
            if (authResult is AuthResult.Success) {
                val activeCoord = sessionCoordinator.sessionState.value as? SessionState.Authenticated
                if (activeCoord != null && activeCoord.session == authResult.session) {
                    sessionCoordinator.signOutIfEpoch(activeCoord.epoch)
                }
            }
            return AuthResult.Failure(AuthFailureReason.TokenExpiredOrRevoked, "Pilot revoked during login")
        }

        val quarantined = pilotAuthGateway?.consumeLastQuarantinedObservation()
        if (quarantined != null) {
            val strictTerminate = synchronized(stateLock) {
                if (capturedEpoch == pilotEpoch && capturedGeneration == gate.currentGeneration()) {
                    observedApiOrigin = quarantined.apiOrigin
                    observedWssOrigin = quarantined.wssOrigin
                    _lastObservedOrigins.value = quarantined
                    if (activeCtx.isStrictAuthAndInventoryOnly()) {
                        true
                    } else {
                        _pilotModeState.value = PilotModeState.AwaitingOriginConfirmation(
                            authorizationId = activeCtx.authorizationId,
                            observedOrigins = quarantined
                        )
                        false
                    }
                } else {
                    false
                }
            }
            if (strictTerminate) {
                emergencyDisconnectAndWipe(
                    PilotAbortReason.UntrustedOriginRejected,
                    expectedGeneration = capturedGeneration
                )
            }
            return AuthResult.Failure(AuthFailureReason.UntrustedEndpoint, quarantined.quarantineReason)
        }

        val isTlsFailure = pilotAuthGateway?.consumeLastTlsFailureFlag() ?: false
        if (authResult is AuthResult.Failure) {
            val abortReason = when {
                isTlsFailure -> PilotAbortReason.TlsHandshakeFailure
                authResult.reason == AuthFailureReason.ChallengeRequired -> PilotAbortReason.ChallengeRequired
                authResult.reason == AuthFailureReason.UntrustedEndpoint -> PilotAbortReason.UntrustedOriginRejected
                authResult.reason is AuthFailureReason.ServerRejected -> PilotAbortReason.ProtocolMismatchAbort
                else -> PilotAbortReason.AuthRejected
            }
            emergencyDisconnectAndWipe(abortReason, expectedGeneration = capturedGeneration)
            return authResult
        }

        val session = (authResult as AuthResult.Success).session
        synchronized(stateLock) {
            if (capturedEpoch == pilotEpoch && capturedGeneration == gate.currentGeneration()) {
                observedApiOrigin = session.apiUrl
                observedWssOrigin = session.wssUrl
                val remaining = (activeCtx.observationDeadlineElapsedMs - authClock.elapsedRealtimeMs()).coerceAtLeast(0L)
                _remainingObservationMs.value = remaining
                _pilotModeState.value = if (activeCtx.isStrictAuthAndInventoryOnly()) {
                    val inventoryAllowed = PilotOperation.USER_THING_LIST_GET in activeCtx.permittedOperations &&
                        activeCtx.budgets.maxInventoryRequests > 0
                    PilotModeState.AuthenticatedInventoryReady(
                        authorizationId = activeCtx.authorizationId,
                        remainingWindowMs = remaining,
                        sanitizedAuthSummary = if (inventoryAllowed) {
                            "Authenticated (${session.accountAliasRedacted}, region=${session.regionName})"
                        } else {
                            "Authenticated (${session.accountAliasRedacted}, region=${session.regionName}; Auth-Only Scope, Inventory Prohibited)"
                        },
                        inventoryPermitted = inventoryAllowed
                    )
                } else {
                    PilotModeState.Observing(activeCtx.authorizationId, remaining, null)
                }
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
        val ctx = gate.currentContext
        if (ctx?.isStrictAuthAndInventoryOnly() == true) {
            return DynamicOriginConfirmationResult.Rejected("PILOT_QUARANTINE_TERMINATED_NO_RESUME")
        }
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
        val capturedGeneration: Long
        var abortNow: PilotAbortReason? = null
        var inventoryTicket: PilotAdmissionTicket? = null
        synchronized(stateLock) {
            capturedEpoch = pilotEpoch
            capturedGeneration = gate.currentGeneration()
            val check = gate.authorizeOperation(PilotOperation.USER_THING_LIST_GET, authClock.elapsedRealtimeMs())
            when {
                check is GateCheckResult.Denied -> abortNow = check.reason
                inventoryRequestsUsed >= (check as GateCheckResult.Allowed).context.budgets.maxInventoryRequests ->
                    abortNow = PilotAbortReason.BudgetExhausted
                else -> {
                    when (
                        val admitted = gate.acquireAdmission(
                            operation = PilotOperation.USER_THING_LIST_GET,
                            nowElapsedMs = authClock.elapsedRealtimeMs(),
                            expectedGeneration = capturedGeneration
                        )
                    ) {
                        is AdmissionResult.Denied -> abortNow = admitted.reason
                        is AdmissionResult.Admitted -> {
                            inventoryRequestsUsed++
                            inventoryTicket = admitted.ticket
                        }
                    }
                }
            }
        }
        if (abortNow != null) {
            emergencyDisconnectAndWipe(abortNow!!, expectedGeneration = capturedGeneration)
            return false
        }

        _isOperationInFlight.value = true
        val state = try {
            withContext(PilotAdmissionContextElement(requireNotNull(inventoryTicket))) {
                inventoryRepository.refreshInventory()
            }
        } finally {
            synchronized(stateLock) {
                if (capturedEpoch == pilotEpoch && capturedGeneration == gate.currentGeneration()) {
                    _isOperationInFlight.value = false
                }
            }
        }
        val stillCurrent = synchronized(stateLock) {
            capturedEpoch == pilotEpoch &&
                capturedGeneration == gate.currentGeneration() &&
                gate.isAuthorized
        }
        if (!stillCurrent) {
            return false
        }
        return state is InventoryState.Loaded || state is InventoryState.Empty
    }

    suspend fun fetchAndValidateTargetInventory(): com.weback.robot.domain.pilot.TargetInventorySummary? {
        val (capturedEpoch, capturedGeneration, activeCtx) = synchronized(stateLock) {
            Triple(pilotEpoch, gate.currentGeneration(), gate.currentContext)
        }
        if (activeCtx == null) {
            return null
        }
        val check = gate.authorizeOperation(PilotOperation.USER_THING_LIST_GET, authClock.elapsedRealtimeMs())
        if (check is GateCheckResult.Denied) {
            emergencyDisconnectAndWipe(check.reason, expectedGeneration = capturedGeneration)
            return null
        }
        if (!refreshReadOnlyInventory()) {
            // refreshReadOnlyInventory already triggers emergencyDisconnectAndWipe on budget/gate failure,
            // ensure non-budget fetch errors (e.g. HTTP error/malformed response) also terminate safely.
            val stillAuth = synchronized(stateLock) {
                capturedEpoch == pilotEpoch &&
                    capturedGeneration == gate.currentGeneration() &&
                    gate.isAuthorized
            }
            if (stillAuth) {
                emergencyDisconnectAndWipe(
                    PilotAbortReason.TargetValidationFailed,
                    expectedGeneration = capturedGeneration
                )
            }
            return null
        }
        val stillCurrent = synchronized(stateLock) {
            capturedEpoch == pilotEpoch &&
                capturedGeneration == gate.currentGeneration() &&
                gate.isAuthorized
        }
        if (!stillCurrent) {
            return null
        }

        val loaded = inventoryRepository.inventoryState.value as? InventoryState.Loaded
        val target = loaded?.items?.firstOrNull {
            it.thingName == activeCtx.targetDevice.expectedTargetThingName
        }
        val isSupportedTarget = target != null &&
            target.capability.category == com.weback.robot.domain.model.DeviceCapabilityCategory.LASER_SLAM &&
            target.capability.compatibility.isMvpControlSupported

        if (!isSupportedTarget) {
            emergencyDisconnectAndWipe(
                PilotAbortReason.TargetValidationFailed,
                expectedGeneration = capturedGeneration
            )
            return null
        }

        val validTarget = target!!
        val displayNickname = if (validTarget.nickname == validTarget.thingName) {
            activeCtx.targetDevice.redactedTag
        } else {
            validTarget.nickname
        }
        val summary = com.weback.robot.domain.pilot.TargetInventorySummary(
            thingNameRedacted = activeCtx.targetDevice.redactedTag,
            nickname = displayNickname,
            normalizedSubType = validTarget.capability.normalizedSubType,
            isAdmin = validTarget.isAdmin,
            compatibility = validTarget.capability.compatibility,
            batteryPercentage = validTarget.initialState?.batteryPercentage,
            mode = validTarget.initialState?.mode,
            errorCode = validTarget.initialState?.errorCode
        )
        synchronized(stateLock) {
            if (capturedEpoch == pilotEpoch &&
                capturedGeneration == gate.currentGeneration() &&
                gate.isAuthorized
            ) {
                val remaining = (activeCtx.observationDeadlineElapsedMs - authClock.elapsedRealtimeMs()).coerceAtLeast(0L)
                _remainingObservationMs.value = remaining
                _lastTargetInventory.value = summary
                _pilotModeState.value = PilotModeState.InventoryVerified(
                    authorizationId = activeCtx.authorizationId,
                    remainingWindowMs = remaining,
                    targetSummary = summary
                )
            } else {
                return null
            }
        }
        return summary
    }

    fun selectAndObserveDevice(thingName: String): Boolean {
        val capturedGeneration = gate.currentGeneration()
        val check = gate.authorizeOperation(PilotOperation.WS_TELEMETRY_OBSERVE, authClock.elapsedRealtimeMs())
        if (check is GateCheckResult.Denied) {
            revokeSynchronouslyAndLaunchCleanup(check.reason, capturedGeneration)
            return false
        }
        val ctx = (check as GateCheckResult.Allowed).context
        if (ctx.isStrictAuthAndInventoryOnly() || ctx.budgets.maxInitialWsConnections == 0) {
            revokeSynchronouslyAndLaunchCleanup(PilotAbortReason.BudgetExhausted, capturedGeneration)
            return false
        }
        val sessionAuth = sessionCoordinator.authorizeActiveSessionForSend() ?: return false

        val loaded = inventoryRepository.inventoryState.value as? InventoryState.Loaded ?: return false
        val candidate = loaded.items.firstOrNull { it.thingName == thingName } ?: return false

        if (!candidate.capability.compatibility.isMvpControlSupported ||
            candidate.thingName != ctx.targetDevice.expectedTargetThingName
        ) {
            return false
        }

        val capturedEpoch: Long
        var abortBudget: PilotAbortReason? = null
        synchronized(stateLock) {
            capturedEpoch = pilotEpoch
            if (initialWsConnectionsUsed >= ctx.budgets.maxInitialWsConnections) {
                abortBudget = PilotAbortReason.BudgetExhausted
            } else {
                when (
                    val admitted = gate.acquireAdmission(
                        operation = PilotOperation.WS_TELEMETRY_OBSERVE,
                        nowElapsedMs = authClock.elapsedRealtimeMs(),
                        requestUrl = sessionAuth.session.wssUrl,
                        isReconnect = false,
                        expectedGeneration = capturedGeneration,
                        endpointValidator = { authCtx, targetUrl ->
                            trustPolicy.isUrlAuthorizedForOperation(
                                authCtx,
                                PilotOperation.WS_TELEMETRY_OBSERVE,
                                targetUrl
                            )
                        }
                    )
                ) {
                    is AdmissionResult.Denied -> abortBudget = admitted.reason
                    is AdmissionResult.Admitted -> {
                        initialWsConnectionsUsed++
                        pendingInitialWsTicket = admitted.ticket
                    }
                }
            }
        }
        if (abortBudget != null) {
            revokeSynchronouslyAndLaunchCleanup(abortBudget!!, capturedGeneration)
            return false
        }

        val selected = inventoryRepository.selectDevice(thingName, sessionAuth.epoch) ?: return false

        val authorizedToConnect = synchronized(stateLock) {
            capturedEpoch == pilotEpoch &&
                capturedGeneration == gate.currentGeneration() &&
                gate.isAuthorized
        }
        if (!authorizedToConnect) {
            synchronized(stateLock) { pendingInitialWsTicket = null }
            return false
        }

        pilotTelemetryTransport.selectDevice(selected, sessionAuth.epoch)
        pilotTelemetryTransport.connect(sessionAuth.session, sessionAuth.epoch)

        val stillAuthorized = synchronized(stateLock) {
            if (capturedEpoch == pilotEpoch &&
                capturedGeneration == gate.currentGeneration() &&
                gate.isAuthorized
            ) {
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

    fun revokeAndBeginCleanupAsync(reason: PilotAbortReason = PilotAbortReason.UserEmergencyDisconnect) {
        val targetGen = synchronized(stateLock) {
            _isOperationInFlight.value = false
            if (gate.isAuthorized) gate.currentGeneration() else activeAuthorizedGeneration
        }
        revokeSynchronouslyAndLaunchCleanup(reason, targetGen)
    }

    /**
     * Generation-fenced Emergency Disconnect & Wipe (`SEC-404`, `AC-408.1`, `FIX-405`):
     * When [expectedGeneration] is provided, ignores calls targeting stale generations so old
     * callbacks never tear down a newer pilot run.
     */
    suspend fun emergencyDisconnectAndWipe(
        reason: PilotAbortReason = PilotAbortReason.UserEmergencyDisconnect,
        expectedGeneration: Long? = null
    ): PilotEmergencyDisconnectResult {
        val cleanupAction = synchronized(stateLock) {
            val currentGen = gate.currentGeneration()
            if (expectedGeneration != null &&
                expectedGeneration != currentGen &&
                expectedGeneration != activeAuthorizedGeneration
            ) {
                return@synchronized null
            }
            if (expectedGeneration != null &&
                !gate.isAuthorized &&
                expectedGeneration <= lastCleanedGeneration
            ) {
                return@synchronized null
            }
            val targetGen = expectedGeneration ?: if (gate.isAuthorized) currentGen else activeAuthorizedGeneration
            if (!gate.isAuthorized && !gate.hasInFlightOrIncompleteCleanup() && targetGen <= lastCleanedGeneration) {
                _lastDisconnectResult.value?.let { previous ->
                    return@synchronized CleanupAction.AlreadyClean(previous)
                }
            }
            if (targetGen > lastCleanedGeneration) {
                lastCleanedGeneration = targetGen
            }
            val cleanupToken = gate.beginCleanup(reason)
            val sessionEpochAtRevoke = sessionCoordinator.currentEpoch()
            pilotEpoch++
            deadlineJob?.cancel()
            deadlineJob = null
            countdownJob?.cancel()
            countdownJob = null
            pendingInitialWsTicket = null
            _isOperationInFlight.value = false
            _lastAbortReason.value = reason
            _remainingObservationMs.value = 0L
            _pilotModeState.value = PilotModeState.OfflineDefault
            CleanupAction.Run(
                cleanupToken = cleanupToken,
                revokedGeneration = targetGen,
                sessionEpoch = sessionEpochAtRevoke
            )
        } ?: return _lastDisconnectResult.value ?: PilotEmergencyDisconnectResult(
            networkRevokedImmediately = true,
            storageCleared = true,
            incompleteStorageReason = null,
            restoredOfflineSimulator = true,
            serverSideTokenRevocationClaimed = false
        )

        if (cleanupAction is CleanupAction.AlreadyClean) {
            return cleanupAction.result
        }
        val run = cleanupAction as CleanupAction.Run
        return finishCleanupRun(reason, run.cleanupToken, run.revokedGeneration, run.sessionEpoch)
    }

    /**
     * Called when an underlying WebSocket transport invalidates a session asynchronously (`FIX-405`).
     * Only cleans up if `sessionCoordinator.currentEpoch()` still matches [invalidatedSessionEpoch].
     */
    suspend fun emergencyDisconnectForSessionEpoch(
        reason: PilotAbortReason,
        invalidatedSessionEpoch: Long
    ) {
        val matchingGen = synchronized(stateLock) {
            if (sessionCoordinator.currentEpoch() != invalidatedSessionEpoch) return
            gate.currentGeneration()
        }
        emergencyDisconnectAndWipe(reason, expectedGeneration = matchingGen)
    }

    private suspend fun finishCleanupRun(
        reason: PilotAbortReason,
        cleanupToken: Long,
        revokedGeneration: Long,
        sessionEpoch: Long
    ): PilotEmergencyDisconnectResult {
        activeCallRegistry?.revokeAndCancelAll(revokedGeneration)
        trustPolicy.clearConfirmedDynamicOrigins()
        pilotTelemetryTransport.forceDisconnect(ConnectivityError.Unauthenticated)

        val logoutResult = sessionCoordinator.signOutIfEpoch(sessionEpoch)
        val clearedOrNull: Boolean? = when (logoutResult) {
            null -> null
            LogoutResult.DurableSuccess -> true
            is LogoutResult.IncompleteStorageClear -> false
        }
        gate.completeCleanup(cleanupToken, clearedOrNull)
        val cleared = when (logoutResult) {
            LogoutResult.DurableSuccess -> true
            is LogoutResult.IncompleteStorageClear -> false
            null -> !gate.hasIncompleteCleanup()
        }
        val incompleteReason = (logoutResult as? LogoutResult.IncompleteStorageClear)?.safeCategory
            ?: if (!cleared) "INCOMPLETE_PRIOR_STORAGE_CLEAR" else null

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
        val aliases = setOfNotNull(
            ctx?.accountScope?.authorizedAccountAlias ?: lastSensitiveAlias,
            ctx?.accountScope?.authorizedAccountIdentifier ?: lastSensitiveAccountIdentifier
        )
        return exporter.exportReport(
            authorizationId = authId,
            modeLabel = modeLabel,
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
        val gen = synchronized(stateLock) {
            val g = gate.currentGeneration()
            gate.revoke(PilotAbortReason.UserEmergencyDisconnect)
            pilotEpoch++
            deadlineJob?.cancel()
            deadlineJob = null
            countdownJob?.cancel()
            countdownJob = null
            pendingInitialWsTicket = null
            g
        }
        activeCallRegistry?.revokeAndCancelAll(gen)
        pilotTelemetryTransport.close()
        if (ownsScope) {
            scope.cancel()
        }
    }

    /**
     * Checks the injected [AuthMonotonicClock] (backed by Android `SystemClock.elapsedRealtime()`)
     * and triggers full generation-fenced 9-step cleanup if the observation deadline has elapsed
     * (including across device deep sleep) (`B-SEC-03`).
     */
    suspend fun enforceDeadlineIfExpired(): Boolean {
        val expiredGen = synchronized(stateLock) {
            val ctx = gate.currentContext ?: return false
            val nowMs = authClock.elapsedRealtimeMs()
            if (nowMs < ctx.authorizedAtElapsedMs || nowMs >= ctx.observationDeadlineElapsedMs) {
                activeAuthorizedGeneration
            } else {
                null
            }
        } ?: return false
        emergencyDisconnectAndWipe(
            reason = PilotAbortReason.ObservationWindowExpired,
            expectedGeneration = expiredGen
        )
        return true
    }

    private fun handleGateWindowExpired(revokedGeneration: Long) {
        val run = synchronized(stateLock) {
            if (revokedGeneration <= lastCleanedGeneration || revokedGeneration != activeAuthorizedGeneration) {
                return
            }
            lastCleanedGeneration = revokedGeneration
            val cleanupToken = gate.beginCleanupForRevokedGeneration(PilotAbortReason.ObservationWindowExpired)
            val sessionEpoch = sessionCoordinator.currentEpoch()
            pilotEpoch++
            deadlineJob?.cancel()
            deadlineJob = null
            countdownJob?.cancel()
            countdownJob = null
            pendingInitialWsTicket = null
            _isOperationInFlight.value = false
            _lastAbortReason.value = PilotAbortReason.ObservationWindowExpired
            _remainingObservationMs.value = 0L
            _pilotModeState.value = PilotModeState.OfflineDefault
            CleanupAction.Run(cleanupToken, revokedGeneration, sessionEpoch)
        }
        activeCallRegistry?.revokeAndCancelAll(run.revokedGeneration)
        trustPolicy.clearConfirmedDynamicOrigins()
        pilotTelemetryTransport.forceDisconnect(ConnectivityError.TokenExpired)
        scope.launch(dispatchers.default) {
            finishCleanupRun(
                PilotAbortReason.ObservationWindowExpired,
                run.cleanupToken,
                run.revokedGeneration,
                run.sessionEpoch
            )
        }
    }

    private fun revokeSynchronouslyAndLaunchCleanup(reason: PilotAbortReason, expectedGeneration: Long) {
        val run = synchronized(stateLock) {
            val currentGen = gate.currentGeneration()
            if (expectedGeneration != currentGen && expectedGeneration != activeAuthorizedGeneration) return
            if (expectedGeneration <= lastCleanedGeneration) return
            lastCleanedGeneration = expectedGeneration
            val cleanupToken = gate.beginCleanup(reason)
            val sessionEpoch = sessionCoordinator.currentEpoch()
            pilotEpoch++
            deadlineJob?.cancel()
            deadlineJob = null
            countdownJob?.cancel()
            countdownJob = null
            pendingInitialWsTicket = null
            _isOperationInFlight.value = false
            _lastAbortReason.value = reason
            _remainingObservationMs.value = 0L
            _pilotModeState.value = PilotModeState.OfflineDefault
            CleanupAction.Run(cleanupToken, expectedGeneration, sessionEpoch)
        }
        activeCallRegistry?.revokeAndCancelAll(run.revokedGeneration)
        trustPolicy.clearConfirmedDynamicOrigins()
        pilotTelemetryTransport.forceDisconnect(ConnectivityError.Unauthenticated)
        scope.launch(dispatchers.default) {
            finishCleanupRun(reason, run.cleanupToken, run.revokedGeneration, run.sessionEpoch)
        }
    }

    private fun scheduleObservationDeadline(expectedEpoch: Long, expectedGeneration: Long, deadlineElapsedMs: Long) {
        val job = scope.launch(dispatchers.default) {
            while (true) {
                val stillActive = synchronized(stateLock) {
                    expectedEpoch == pilotEpoch &&
                        expectedGeneration == activeAuthorizedGeneration &&
                        gate.isAuthorized
                }
                if (!stillActive) return@launch
                val remainingMs = deadlineElapsedMs - authClock.elapsedRealtimeMs()
                if (remainingMs <= 0L) break
                delay(remainingMs.coerceAtLeast(1L))
            }
            val shouldExpire = synchronized(stateLock) {
                expectedEpoch == pilotEpoch &&
                    expectedGeneration == activeAuthorizedGeneration &&
                    gate.isAuthorized
            }
            if (shouldExpire) {
                emergencyDisconnectAndWipe(
                    PilotAbortReason.ObservationWindowExpired,
                    expectedGeneration = expectedGeneration
                )
            }
        }
        synchronized(stateLock) {
            if (expectedEpoch == pilotEpoch && expectedGeneration == gate.currentGeneration() && gate.isAuthorized) {
                deadlineJob?.cancel()
                deadlineJob = job
            } else {
                job.cancel()
            }
        }
    }

    private fun scheduleCountdownTicker(
        expectedEpoch: Long,
        expectedGeneration: Long,
        deadlineElapsedMs: Long
    ) {
        val job = scope.launch(dispatchers.default) {
            while (true) {
                delay(1_000L)
                var expiredNow = false
                val keepGoing = synchronized(stateLock) {
                    if (expectedEpoch != pilotEpoch || expectedGeneration != activeAuthorizedGeneration || !gate.isAuthorized) {
                        false
                    } else {
                        val rem = (deadlineElapsedMs - authClock.elapsedRealtimeMs()).coerceAtLeast(0L)
                        _remainingObservationMs.value = rem
                        val cur = _pilotModeState.value
                        when (cur) {
                            is PilotModeState.Observing -> _pilotModeState.value = cur.copy(remainingWindowMs = rem)
                            is PilotModeState.AuthorizedReady -> _pilotModeState.value = cur.copy(remainingWindowMs = rem)
                            is PilotModeState.AuthenticatedInventoryReady -> _pilotModeState.value = cur.copy(remainingWindowMs = rem)
                            is PilotModeState.InventoryVerified -> _pilotModeState.value = cur.copy(remainingWindowMs = rem)
                            else -> Unit
                        }
                        if (rem <= 0L) {
                            expiredNow = true
                            false
                        } else {
                            true
                        }
                    }
                }
                if (expiredNow) {
                    emergencyDisconnectAndWipe(
                        PilotAbortReason.ObservationWindowExpired,
                        expectedGeneration = expectedGeneration
                    )
                }
                if (!keepGoing) break
            }
        }
        synchronized(stateLock) {
            if (expectedEpoch == pilotEpoch && expectedGeneration == gate.currentGeneration() && gate.isAuthorized) {
                countdownJob?.cancel()
                countdownJob = job
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
        countdownJob?.cancel()
        countdownJob = null
        pendingInitialWsTicket = null
        _isOperationInFlight.value = false
        _lastAbortReason.value = reason
        _remainingObservationMs.value = 0L
        _pilotModeState.value = PilotModeState.Aborted(reason, safeMessage)
    }

    private sealed interface CleanupAction {
        data class AlreadyClean(val result: PilotEmergencyDisconnectResult) : CleanupAction
        data class Run(
            val cleanupToken: Long,
            val revokedGeneration: Long,
            val sessionEpoch: Long
        ) : CleanupAction
    }
}
