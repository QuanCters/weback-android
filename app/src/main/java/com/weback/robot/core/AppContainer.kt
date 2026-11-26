package com.weback.robot.core

import android.content.Context
import com.weback.robot.BuildConfig
import com.weback.robot.app.pilot.PilotSessionController
import com.weback.robot.app.session.DeviceInventoryRepository
import com.weback.robot.app.session.SessionCoordinator
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.ConnectivityError
import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.domain.pilot.ExpectedCandidateIdentity
import com.weback.robot.domain.pilot.GateActivationResult
import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotAccountScope
import com.weback.robot.domain.pilot.PilotAccountScopeType
import com.weback.robot.domain.pilot.PilotAuthorizationContext
import com.weback.robot.domain.pilot.PilotAuthorizationGate
import com.weback.robot.domain.pilot.PilotClock
import com.weback.robot.domain.pilot.PilotEndpointConfig
import com.weback.robot.domain.pilot.PilotEnvironment
import com.weback.robot.domain.pilot.PilotExternalApproval
import com.weback.robot.domain.pilot.PilotOperation
import com.weback.robot.domain.pilot.PilotRequestBudgets
import com.weback.robot.domain.pilot.PilotTargetDevice
import com.weback.robot.domain.pilot.RuntimeAppIdentity
import com.weback.robot.protocol.RobotTransport
import com.weback.robot.protocol.SimulatorRobotTransport
import com.weback.robot.protocol.auth.AuthEnvelopeConfig
import com.weback.robot.protocol.auth.AuthGateway
import com.weback.robot.protocol.auth.DisabledPasswordlessRefreshStrategy
import com.weback.robot.protocol.auth.OfflineFixtureAuthGateway
import com.weback.robot.protocol.auth.RegionEndpointRouter
import com.weback.robot.protocol.inventory.DeviceInventoryGateway
import com.weback.robot.protocol.inventory.OfflineFixtureInventoryGateway
import com.weback.robot.protocol.inventory.OkHttpDeviceInventoryGateway
import com.weback.robot.protocol.pilot.PilotCapabilityRestrictedAuthGateway
import com.weback.robot.protocol.pilot.PilotCapabilityRestrictedInventoryGateway
import com.weback.robot.protocol.pilot.PilotReadOnlyFixtureTransport
import com.weback.robot.protocol.ws.ConnectionConfig
import com.weback.robot.protocol.ws.OfflineFixtureRealtimeTransport
import com.weback.robot.protocol.ws.OkHttpWebSocketTransport
import com.weback.robot.protocol.ws.RealtimeSessionTransport
import com.weback.robot.security.AndroidKeystoreAeadCipherProvider
import com.weback.robot.security.AuthorizedPilotEndpointTrustPolicy
import com.weback.robot.security.DiagnosticLogger
import com.weback.robot.security.EndpointTrustPolicy
import com.weback.robot.security.KeystoreAeadTokenStore
import com.weback.robot.security.NoOpDiagnosticLogger
import com.weback.robot.security.PilotActiveCallRegistry
import com.weback.robot.security.PilotClientFactory
import com.weback.robot.security.ProductionEndpointTrustPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.weback.robot.security.TokenClearResult
import com.weback.robot.security.TokenLoadResult
import com.weback.robot.security.TokenSaveResult
import com.weback.robot.security.TokenStore
import java.io.File

data class LivePilotActivationRequest(
    val authorizationId: String,
    val approvedByProductOwner: String,
    val approvedByLeadMaintainer: String,
    val externalRecordRef: String,
    val candidateCommitSha: String,
    val apkSha256: String,
    val regionCallingCode: String,
    val authorizedAccountIdentifier: String,
    val expectedTargetThingName: String,
    val approvedAuthOrigin: String,
    val pinnedApiOrigin: String? = null,
    val pinnedWssOrigin: String? = null,
    val windowMinutes: Int = 15,
    val uiConsentCheckedOnly: Boolean = false
)

data class PilotContainerTestHooks(
    val routerOverride: RegionEndpointRouter? = null,
    val trustPolicyOverride: AuthorizedPilotEndpointTrustPolicy? = null,
    val gatewayTrustPolicyOverride: EndpointTrustPolicy? = null,
    val allowedAuthOriginsOverride: Set<String>? = null,
    val expectedRuntimeIdentityOverride: ExpectedCandidateIdentity? = null,
    val runtimeAppIdentityOverride: RuntimeAppIdentity? = null,
    val enableUiCountdownTicker: Boolean = false
)

/**
 * Manual dependency container owning transport, session, inventory, security, dispatcher, and clock lifecycles.
 * Preserves offline [SimulatorRobotTransport] as the default FCT-002/FCT-003 robot control path.
 */
interface AppContainer : AutoCloseable {
    val robotTransport: RobotTransport
    val dispatcherProvider: DispatcherProvider
    val clock: MonotonicClock
    val endpointTrustPolicy: EndpointTrustPolicy
    val diagnosticLogger: DiagnosticLogger
    val sessionCoordinator: SessionCoordinator
    val inventoryRepository: DeviceInventoryRepository
    val realtimeTransport: RealtimeSessionTransport
    val pilotAuthorizationGate: PilotAuthorizationGate
    val pilotSessionController: PilotSessionController
    val offlineMockAuthorizationGate: PilotAuthorizationGate
    val offlineMockPilotController: PilotSessionController
    val isLivePilotSupported: Boolean
        get() = BuildConfig.PILOT_INTERNET_CAPABLE
    fun startOfflineMockPilot(): GateActivationResult
    fun activateLivePilot(request: LivePilotActivationRequest): GateActivationResult =
        GateActivationResult.Rejected(PilotAbortReason.AuthorizationExpiredOrInvalid, "Live pilot not implemented")
    override fun close() {}
}

class DefaultAppContainer(
    override val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    override val clock: MonotonicClock = SystemMonotonicClock,
    private val context: Context? = null,
    private val authClock: AuthMonotonicClock = AndroidAuthMonotonicClock,
    private val timeProviderOverride: BootSessionTimeProvider? = null,
    private val tokenStoreOverride: TokenStore? = null,
    override val endpointTrustPolicy: EndpointTrustPolicy = ProductionEndpointTrustPolicy,
    override val diagnosticLogger: DiagnosticLogger = NoOpDiagnosticLogger,
    private val authGatewayOverride: AuthGateway? = null,
    private val inventoryGatewayOverride: DeviceInventoryGateway? = null,
    private val realtimeTransportOverride: RealtimeSessionTransport? = null,
    private val pilotTestHooks: PilotContainerTestHooks? = null
) : AppContainer {

    private val timeProvider: BootSessionTimeProvider by lazy {
        timeProviderOverride ?: context?.let {
            AndroidBootSessionTimeProvider(context = it, authClock = authClock)
        } ?: BootSessionTimeProvider {
            BootTimeSnapshot(
                bootGenerationId = null,
                elapsedRealtimeMs = authClock.elapsedRealtimeMs(),
                wallEpochMs = System.currentTimeMillis()
            )
        }
    }

    private val tokenStore: TokenStore by lazy {
        tokenStoreOverride ?: context?.let { appCtx ->
            KeystoreAeadTokenStore(
                storageDir = File(appCtx.noBackupFilesDir, "auth_session_store"),
                cipherProvider = AndroidKeystoreAeadCipherProvider(),
                allowLoopbackHttp = false
            )
        } ?: NonPersistentSessionStore
    }

    private val authGateway: AuthGateway by lazy {
        authGatewayOverride ?: OfflineFixtureAuthGateway(authClock = authClock)
    }

    private val inventoryGateway: DeviceInventoryGateway by lazy {
        inventoryGatewayOverride ?: OfflineFixtureInventoryGateway()
    }

    override val realtimeTransport: RealtimeSessionTransport by lazy {
        realtimeTransportOverride ?: OfflineFixtureRealtimeTransport()
    }

    override val sessionCoordinator: SessionCoordinator by lazy {
        SessionCoordinator(
            authGateway = authGateway,
            tokenStore = tokenStore,
            timeProvider = timeProvider,
            refreshStrategy = DisabledPasswordlessRefreshStrategy,
            logger = diagnosticLogger
        ).also { coordinator ->
            coordinator.registerOnSessionInvalidated { oldEpoch, reason ->
                val connErr = when (reason) {
                    com.weback.robot.domain.model.AuthFailureReason.UntrustedEndpoint -> ConnectivityError.UntrustedEndpoint
                    com.weback.robot.domain.model.AuthFailureReason.RedirectRejected -> ConnectivityError.RedirectRejected
                    com.weback.robot.domain.model.AuthFailureReason.StorageFailure -> ConnectivityError.StorageFailure
                    com.weback.robot.domain.model.AuthFailureReason.TokenExpiredOrRevoked -> ConnectivityError.TokenExpired
                    else -> ConnectivityError.Unauthenticated
                }
                realtimeTransport.invalidateForEpoch(
                    expectedEpoch = oldEpoch,
                    reason = connErr
                )
            }
        }
    }

    override val inventoryRepository: DeviceInventoryRepository by lazy {
        DeviceInventoryRepository(
            sessionCoordinator = sessionCoordinator,
            inventoryGateway = inventoryGateway
        )
    }

    override val robotTransport: RobotTransport by lazy {
        SimulatorRobotTransport(
            dispatcherProvider = dispatcherProvider,
            clock = clock
        )
    }

    override val pilotAuthorizationGate: PilotAuthorizationGate by lazy {
        PilotAuthorizationGate(
            allowedAuthOrigins = pilotTestHooks?.allowedAuthOriginsOverride
                ?: PilotAuthorizationGate.CANONICAL_AUTH_ORIGINS,
            clock = PilotClock { authClock.elapsedRealtimeMs() }
        )
    }

    private val pilotEndpointTrustPolicy: AuthorizedPilotEndpointTrustPolicy by lazy {
        pilotTestHooks?.trustPolicyOverride ?: AuthorizedPilotEndpointTrustPolicy(
            endpointConfig = PilotEndpointConfig(
                approvedAuthOrigin = "https://user.grit-cloud.com:443",
                approvedAuthPath = "/prod/oauth",
                requireProvenanceVerification = true
            ),
            allowLoopbackForHermeticTests = false
        )
    }

    private val pilotGatewayTrustPolicy: EndpointTrustPolicy by lazy {
        pilotTestHooks?.gatewayTrustPolicyOverride ?: pilotEndpointTrustPolicy
    }

    private val pilotActiveCallRegistry: PilotActiveCallRegistry by lazy {
        PilotActiveCallRegistry()
    }

    private val pilotScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + dispatcherProvider.default)
    }

    private val pilotHttpClient by lazy {
        PilotClientFactory.buildPilotOkHttpClient(
            trustPolicy = pilotEndpointTrustPolicy,
            activeCallRegistry = pilotActiveCallRegistry,
            admissionGate = pilotAuthorizationGate,
            authClock = authClock
        )
    }

    private val restrictedPilotAuth: PilotCapabilityRestrictedAuthGateway by lazy {
        PilotCapabilityRestrictedAuthGateway(
            router = pilotTestHooks?.routerOverride ?: RegionEndpointRouter(),
            trustPolicy = pilotGatewayTrustPolicy,
            authClock = authClock,
            logger = diagnosticLogger,
            dispatchers = dispatcherProvider,
            envelopeConfig = AuthEnvelopeConfig(),
            baseClient = pilotHttpClient,
            pilotEndpointTrustPolicy = pilotEndpointTrustPolicy,
            admissionGate = pilotAuthorizationGate
        )
    }

    private val restrictedPilotInventory: PilotCapabilityRestrictedInventoryGateway by lazy {
        PilotCapabilityRestrictedInventoryGateway(
            delegate = OkHttpDeviceInventoryGateway(
                trustPolicy = pilotGatewayTrustPolicy,
                authClock = authClock,
                dispatchers = dispatcherProvider,
                logger = diagnosticLogger,
                client = pilotHttpClient
            ),
            admissionGate = pilotAuthorizationGate,
            authClock = authClock,
            pilotEndpointTrustPolicy = pilotEndpointTrustPolicy
        )
    }

    private val pilotSessionCoordinator: SessionCoordinator by lazy {
        SessionCoordinator(
            authGateway = restrictedPilotAuth,
            tokenStore = tokenStore,
            timeProvider = timeProvider,
            refreshStrategy = DisabledPasswordlessRefreshStrategy,
            logger = diagnosticLogger
        ).also { coordinator ->
            coordinator.registerOnSessionInvalidated { oldEpoch, reason ->
                val connErr = when (reason) {
                    AuthFailureReason.UntrustedEndpoint -> ConnectivityError.UntrustedEndpoint
                    AuthFailureReason.RedirectRejected -> ConnectivityError.RedirectRejected
                    AuthFailureReason.StorageFailure -> ConnectivityError.StorageFailure
                    AuthFailureReason.TokenExpiredOrRevoked -> ConnectivityError.TokenExpired
                    else -> ConnectivityError.Unauthenticated
                }
                pilotWsDelegate.invalidateForEpoch(expectedEpoch = oldEpoch, reason = connErr)
            }
        }
    }

    private val pilotInventoryRepository: DeviceInventoryRepository by lazy {
        DeviceInventoryRepository(
            sessionCoordinator = pilotSessionCoordinator,
            inventoryGateway = restrictedPilotInventory
        )
    }

    private val pilotWsDelegate: OkHttpWebSocketTransport by lazy {
        OkHttpWebSocketTransport(
            trustPolicy = pilotGatewayTrustPolicy,
            authClock = authClock,
            dispatchers = dispatcherProvider,
            connectionConfig = ConnectionConfig(reconnectDelayMs = 4_000L, maxReconnectAttempts = 3),
            logger = diagnosticLogger,
            client = pilotHttpClient,
            onSessionInvalidated = { oldEpoch, reason ->
                val abortReason = when (reason) {
                    AuthFailureReason.UntrustedEndpoint -> PilotAbortReason.UntrustedOriginRejected
                    AuthFailureReason.ChallengeRequired -> PilotAbortReason.ChallengeRequired
                    else -> PilotAbortReason.AuthRejected
                }
                pilotScope.launch {
                    pilotSessionController.emergencyDisconnectForSessionEpoch(abortReason, oldEpoch)
                }
            },
            resetReconnectAttemptOnOpen = false
        )
    }

    // Runtime identity derived from BuildConfig without embedding a self-referential APK SHA-256 (`FCT-004B`)
    private val runtimeLiveIdentity = RuntimeAppIdentity(
        applicationId = BuildConfig.APPLICATION_ID,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        pilotBuildEnabled = BuildConfig.PILOT_INTERNET_CAPABLE
    )

    override val pilotSessionController: PilotSessionController by lazy {
        PilotSessionController(
            gate = pilotAuthorizationGate,
            expectedRuntimeIdentity = pilotTestHooks?.expectedRuntimeIdentityOverride,
            trustPolicy = pilotEndpointTrustPolicy,
            sessionCoordinator = pilotSessionCoordinator,
            inventoryRepository = pilotInventoryRepository,
            pilotAuthGateway = restrictedPilotAuth,
            pilotWsDelegate = pilotWsDelegate,
            simulatorTransport = robotTransport,
            authClock = authClock,
            dispatchers = dispatcherProvider,
            logger = diagnosticLogger,
            activeCallRegistry = pilotActiveCallRegistry,
            scope = pilotScope,
            ownsScope = true,
            modeLabel = if (isLivePilotSupported) {
                "PILOT BUILD (${BuildConfig.APPLICATION_ID}) — CONTROLLED AUTH & SINGLE INVENTORY (ZERO WS)"
            } else {
                "OFFLINE_BUILD_LIVE_PILOT_DISABLED"
            },
            enableUiCountdownTicker = pilotTestHooks?.enableUiCountdownTicker ?: true,
            runtimeAppIdentity = pilotTestHooks?.runtimeAppIdentityOverride ?: runtimeLiveIdentity
        )
    }

    // Separate in-process offline mock workflow (`FIX-404`) incapable of authorizing live cloud access
    private val mockIdentity = ExpectedCandidateIdentity(
        candidateCommitSha = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
        applicationId = "com.weback.robot.mock",
        versionName = "0.1.0-alpha-mock",
        apkSha256 = "a".repeat(64)
    )
    private var mockActivationSeq = 0L

    override val offlineMockAuthorizationGate: PilotAuthorizationGate by lazy {
        PilotAuthorizationGate(
            allowedAuthOrigins = setOf("https://loopback.offline.invalid:443"),
            allowedEnvironment = PilotEnvironment.OFFLINE_MOCK_FIXTURE,
            clock = PilotClock { authClock.elapsedRealtimeMs() }
        )
    }

    private val offlineMockTrustPolicy: AuthorizedPilotEndpointTrustPolicy by lazy {
        AuthorizedPilotEndpointTrustPolicy(
            endpointConfig = PilotEndpointConfig(
                approvedAuthOrigin = "https://loopback.offline.invalid:443",
                approvedAuthPath = "/prod/oauth",
                pinnedApiOrigin = "https://loopback.offline.invalid:443",
                pinnedWssOrigin = "wss://loopback.offline.invalid:443",
                requireProvenanceVerification = true
            ),
            allowedAuthOrigins = setOf("https://loopback.offline.invalid:443"),
            allowLoopbackForHermeticTests = false
        )
    }

    private val offlineMockFixtureTransport: OfflineFixtureRealtimeTransport by lazy {
        OfflineFixtureRealtimeTransport()
    }

    private val offlineMockSessionCoordinator: SessionCoordinator by lazy {
        SessionCoordinator(
            authGateway = OfflineFixtureAuthGateway(authClock = authClock),
            tokenStore = InMemoryMockTokenStore(),
            timeProvider = timeProvider,
            refreshStrategy = DisabledPasswordlessRefreshStrategy,
            logger = diagnosticLogger
        ).also { coordinator ->
            coordinator.registerOnSessionInvalidated { oldEpoch, reason ->
                val connErr = when (reason) {
                    AuthFailureReason.UntrustedEndpoint -> ConnectivityError.UntrustedEndpoint
                    AuthFailureReason.RedirectRejected -> ConnectivityError.RedirectRejected
                    AuthFailureReason.StorageFailure -> ConnectivityError.StorageFailure
                    AuthFailureReason.TokenExpiredOrRevoked -> ConnectivityError.TokenExpired
                    else -> ConnectivityError.Unauthenticated
                }
                offlineMockFixtureTransport.invalidateForEpoch(expectedEpoch = oldEpoch, reason = connErr)
            }
        }
    }

    private val offlineMockInventoryRepository: DeviceInventoryRepository by lazy {
        DeviceInventoryRepository(
            sessionCoordinator = offlineMockSessionCoordinator,
            inventoryGateway = OfflineFixtureInventoryGateway()
        )
    }

    override val offlineMockPilotController: PilotSessionController by lazy {
        PilotSessionController(
            gate = offlineMockAuthorizationGate,
            expectedRuntimeIdentity = mockIdentity,
            trustPolicy = offlineMockTrustPolicy,
            sessionCoordinator = offlineMockSessionCoordinator,
            inventoryRepository = offlineMockInventoryRepository,
            pilotAuthGateway = null,
            pilotWsDelegate = null,
            simulatorTransport = robotTransport,
            authClock = authClock,
            dispatchers = dispatcherProvider,
            logger = diagnosticLogger,
            activeCallRegistry = null,
            scope = pilotScope,
            ownsScope = false,
            telemetryTransportOverride = PilotReadOnlyFixtureTransport(
                delegate = offlineMockFixtureTransport,
                onUnexpectedOutboundAttempt = { detail ->
                    diagnosticLogger.log(
                        com.weback.robot.security.DiagnosticEvent(
                            category = "MOCK_PILOT_OUTBOUND_BLOCKED",
                            errorCategory = detail
                        )
                    )
                }
            ),
            modeLabel = "OFFLINE_MOCK_FIXTURE_ONLY_NO_NETWORK",
            enableUiCountdownTicker = true
        )
    }

    override fun startOfflineMockPilot(): GateActivationResult {
        val nowMs = authClock.elapsedRealtimeMs()
        val seq = ++mockActivationSeq
        val ctx = PilotAuthorizationContext(
            authorizationId = "MOCK-OFFLINE-PILOT-$seq",
            candidateIdentity = mockIdentity,
            accountScope = PilotAccountScope(
                scopeType = PilotAccountScopeType.DEDICATED_ISOLATED_TEST_ACCOUNT,
                regionCallingCode = "+1",
                authorizedAccountAlias = "m***@offline.invalid",
                authorizedAccountIdentifier = "mock-pilot@offline.invalid"
            ),
            targetDevice = PilotTargetDevice(
                expectedSubTypeFamily = "LaserSlam",
                expectedTargetThingName = "offline_laser_01",
                redactedTag = "MOCK_LASER_01"
            ),
            permittedOperations = setOf(
                PilotOperation.LOGIN,
                PilotOperation.USER_THING_LIST_GET,
                PilotOperation.WS_TELEMETRY_OBSERVE
            ),
            endpointConfig = PilotEndpointConfig(
                approvedAuthOrigin = "https://loopback.offline.invalid:443",
                approvedAuthPath = "/prod/oauth",
                pinnedApiOrigin = "https://loopback.offline.invalid:443",
                pinnedWssOrigin = "wss://loopback.offline.invalid:443",
                requireProvenanceVerification = true
            ),
            readOnlyMode = true,
            physicalRobotCommandsPermitted = false,
            budgets = PilotRequestBudgets(),
            authorizedAtElapsedMs = nowMs,
            observationDeadlineElapsedMs = nowMs + PilotRequestBudgets.MAX_OBSERVATION_WINDOW_MS,
            externalApproval = PilotExternalApproval(
                approvedByProductOwner = "SYNTHETIC_MOCK_WORKFLOW_ONLY",
                approvedByLeadMaintainer = "SYNTHETIC_MOCK_WORKFLOW_ONLY",
                externalRecordRef = "MOCK-FIXTURE-$seq"
            ),
            uiConsentCheckedOnly = false,
            environment = PilotEnvironment.OFFLINE_MOCK_FIXTURE
        )
        return offlineMockPilotController.activatePilot(ctx)
    }

    override fun activateLivePilot(request: LivePilotActivationRequest): GateActivationResult {
        if (!isLivePilotSupported) {
            return GateActivationResult.Rejected(
                PilotAbortReason.AuthorizationExpiredOrInvalid,
                "Live pilot authorization is disabled on offline build"
            )
        }
        if (request.windowMinutes !in 1..15) {
            return GateActivationResult.Rejected(
                PilotAbortReason.AuthorizationExpiredOrInvalid,
                "FCT-004C observation window must be between 1 and 15 minutes"
            )
        }
        val trimmedAccount = request.authorizedAccountIdentifier.trim()
        val atIdx = trimmedAccount.indexOf('@')
        val accountAlias = when {
            trimmedAccount.isEmpty() -> ""
            atIdx > 1 -> "${trimmedAccount.first()}***${trimmedAccount.substring(atIdx)}"
            else -> "${trimmedAccount.first()}***"
        }
        val nowMs = authClock.elapsedRealtimeMs()
        val windowMs = request.windowMinutes * 60_000L
        val ctx = PilotAuthorizationContext(
            authorizationId = request.authorizationId.trim(),
            candidateIdentity = ExpectedCandidateIdentity(
                candidateCommitSha = request.candidateCommitSha.trim(),
                applicationId = BuildConfig.APPLICATION_ID,
                versionName = BuildConfig.VERSION_NAME,
                apkSha256 = request.apkSha256.trim()
            ),
            accountScope = PilotAccountScope(
                scopeType = PilotAccountScopeType.DEDICATED_ISOLATED_TEST_ACCOUNT,
                regionCallingCode = request.regionCallingCode.trim(),
                authorizedAccountAlias = accountAlias,
                authorizedAccountIdentifier = trimmedAccount
            ),
            targetDevice = PilotTargetDevice(
                expectedSubTypeFamily = PilotAuthorizationGate.REQUIRED_SUB_TYPE_FAMILY,
                expectedTargetThingName = request.expectedTargetThingName.trim(),
                redactedTag = "TARGET_LASER_01"
            ),
            permittedOperations = setOf(
                PilotOperation.LOGIN,
                PilotOperation.USER_THING_LIST_GET
            ),
            endpointConfig = PilotEndpointConfig(
                approvedAuthOrigin = request.approvedAuthOrigin.trim(),
                approvedAuthPath = PilotAuthorizationGate.REQUIRED_AUTH_PATH,
                pinnedApiOrigin = request.pinnedApiOrigin?.trim()?.takeIf { it.isNotEmpty() },
                pinnedWssOrigin = request.pinnedWssOrigin?.trim()?.takeIf { it.isNotEmpty() },
                requireProvenanceVerification = true
            ),
            readOnlyMode = true,
            physicalRobotCommandsPermitted = false,
            budgets = PilotRequestBudgets.strictAuthAndInventoryOnly(observationWindowMs = windowMs),
            authorizedAtElapsedMs = nowMs,
            observationDeadlineElapsedMs = nowMs + windowMs,
            externalApproval = PilotExternalApproval(
                approvedByProductOwner = request.approvedByProductOwner.trim(),
                approvedByLeadMaintainer = request.approvedByLeadMaintainer.trim(),
                externalRecordRef = request.externalRecordRef.trim()
            ),
            uiConsentCheckedOnly = request.uiConsentCheckedOnly,
            environment = PilotEnvironment.LIVE_PILOT
        )
        return pilotSessionController.activatePilot(ctx)
    }

    override fun close() {
        (robotTransport as? AutoCloseable)?.close()
        realtimeTransport.close()
        offlineMockPilotController.close()
        pilotSessionController.close()
    }
}

private class InMemoryMockTokenStore : TokenStore {
    private var saved: PersistedSessionRecord? = null
    override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
        saved = record
        return TokenSaveResult.Success
    }
    override suspend fun load(): TokenLoadResult =
        saved?.let { TokenLoadResult.Loaded(it) } ?: TokenLoadResult.Empty
    override suspend fun clear(): TokenClearResult {
        saved = null
        return TokenClearResult.Success
    }
}

/**
 * Used ONLY when [DefaultAppContainer] is instantiated in context-less pure-JVM unit tests;
 * performs zero disk writes and never provides software-key cryptographic storage fallback.
 */
private object NonPersistentSessionStore : TokenStore {
    override suspend fun save(record: PersistedSessionRecord): TokenSaveResult =
        TokenSaveResult.Failure("NO_ANDROID_CONTEXT")

    override suspend fun load(): TokenLoadResult = TokenLoadResult.Empty

    override suspend fun clear(): TokenClearResult = TokenClearResult.Success
}
