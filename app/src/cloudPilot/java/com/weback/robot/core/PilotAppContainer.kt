package com.weback.robot.core

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weback.robot.BuildConfig
import com.weback.robot.app.local.LocalDiagnosticLogger
import com.weback.robot.app.local.LocalDiagnosticSink
import com.weback.robot.app.local.LocalMapRepository
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
import com.weback.robot.domain.pilot.PilotModeState
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
import com.weback.robot.protocol.local.LocalTelemetryStreamSource
import com.weback.robot.protocol.local.SyntheticReplayTelemetrySource
import com.weback.robot.protocol.pilot.PilotCapabilityRestrictedAuthGateway
import com.weback.robot.protocol.pilot.PilotCapabilityRestrictedInventoryGateway
import com.weback.robot.protocol.pilot.PilotReadOnlyFixtureTransport
import com.weback.robot.protocol.ws.ConnectionConfig
import com.weback.robot.protocol.ws.OfflineFixtureRealtimeTransport
import com.weback.robot.protocol.ws.OkHttpWebSocketTransport
import com.weback.robot.protocol.ws.RealtimeSessionTransport
import com.weback.robot.security.AndroidInstalledApkVerifier
import com.weback.robot.security.AndroidKeystoreAeadCipherProvider
import com.weback.robot.security.AuthorizedPilotEndpointTrustPolicy
import com.weback.robot.security.ConsumeAuthorizationResult
import com.weback.robot.security.DiagnosticEvent
import com.weback.robot.security.DiagnosticLogger
import com.weback.robot.security.EndpointTrustPolicy
import com.weback.robot.security.ExternalPilotAuthorizationLoader
import com.weback.robot.security.FileBackedProvisionedPilotAuthorizationStore
import com.weback.robot.security.InMemoryProvisionedPilotAuthorizationStore
import com.weback.robot.security.InstalledApkVerification
import com.weback.robot.security.InstalledApkVerifier
import com.weback.robot.security.KeystoreAeadTokenStore
import com.weback.robot.security.NoOpDiagnosticLogger
import com.weback.robot.security.PilotActiveCallRegistry
import com.weback.robot.security.PilotClientFactory
import com.weback.robot.security.ProductionEndpointTrustPolicy
import com.weback.robot.security.ProvisionedActivationPreparation
import com.weback.robot.security.ProvisionedAuthorizationLoadResult
import com.weback.robot.security.ProvisionedPilotAuthorizationStore
import com.weback.robot.security.TokenClearResult
import com.weback.robot.security.TokenLoadResult
import com.weback.robot.security.TokenSaveResult
import com.weback.robot.security.TokenStore
import com.weback.robot.ui.pilot.EphemeralPasswordBuffer
import com.weback.robot.ui.pilot.PilotReadinessSheet
import com.weback.robot.ui.pilot.PilotSheetUiState
import com.weback.robot.ui.theme.WeBackTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

data class PilotContainerTestHooks(
    val routerOverride: RegionEndpointRouter? = null,
    val trustPolicyOverride: AuthorizedPilotEndpointTrustPolicy? = null,
    val gatewayTrustPolicyOverride: EndpointTrustPolicy? = null,
    val allowedAuthOriginsOverride: Set<String>? = null,
    val expectedRuntimeIdentityOverride: ExpectedCandidateIdentity? = null,
    val runtimeAppIdentityOverride: RuntimeAppIdentity? = null,
    val enableUiCountdownTicker: Boolean = false,
    val installedApkVerificationOverride: InstalledApkVerification? = null,
    val provisionedAuthStoreOverride: ProvisionedPilotAuthorizationStore? = null,
    val allowUnsupportedInstalledApkForHostTests: Boolean = false
)

/**
 * Cloud-pilot-specific dependency container active strictly in `debug` and `pilot` variants.
 */
interface PilotAppContainer : AppContainer {
    val endpointTrustPolicy: EndpointTrustPolicy
    val sessionCoordinator: SessionCoordinator
    val inventoryRepository: DeviceInventoryRepository
    val realtimeTransport: RealtimeSessionTransport
    val pilotAuthorizationGate: PilotAuthorizationGate
    val pilotSessionController: PilotSessionController
    val offlineMockAuthorizationGate: PilotAuthorizationGate
    val offlineMockPilotController: PilotSessionController
    val isLivePilotSupported: Boolean
        get() = BuildConfig.PILOT_INTERNET_CAPABLE

    fun startOfflineMockPilot(
        observationWindowMs: Long = PilotRequestBudgets.MAX_OBSERVATION_WINDOW_MS
    ): GateActivationResult

    fun loadProvisionedLiveAuthorization(): ProvisionedAuthorizationLoadResult =
        ProvisionedAuthorizationLoadResult.Missing("Live pilot authorization is not configured")

    fun activateLivePilot(expectedRecordSha256: String): GateActivationResult =
        GateActivationResult.Rejected(PilotAbortReason.AuthorizationExpiredOrInvalid, "Live pilot not implemented")
}

class DefaultPilotContainer(
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
    private val pilotTestHooks: PilotContainerTestHooks? = null,
    private val robotTransportOverride: RobotTransport? = null,
    private val localStreamSourceOverride: LocalTelemetryStreamSource? = null,
    private val localDiagnosticLoggerOverride: LocalDiagnosticSink? = null,
    private val authGatewayProvider: (() -> AuthGateway)? = null,
    private val inventoryGatewayProvider: (() -> DeviceInventoryGateway)? = null,
    private val realtimeTransportProvider: (() -> RealtimeSessionTransport)? = null,
    private val robotTransportProvider: (() -> RobotTransport)? = null,
    private val localStreamSourceProvider: (() -> LocalTelemetryStreamSource)? = null,
    private val dependencyResolutionObserver: ((AppContainerDependency) -> Unit)? = null
) : PilotAppContainer {

    override val pilotCapabilityBridge: PilotCapabilityBridge by lazy {
        PilotCapabilityBridgeImpl(this)
    }

    private fun <T> resolveDependency(
        dependency: AppContainerDependency,
        delegate: Lazy<T>
    ): T {
        dependencyResolutionObserver?.invoke(dependency)
        return delegate.value
    }

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

    private val authGatewayDelegate = lazy {
        authGatewayOverride ?: authGatewayProvider?.invoke() ?: OfflineFixtureAuthGateway(authClock = authClock)
    }

    private val authGateway: AuthGateway
        get() = resolveDependency(AppContainerDependency.AUTH_GATEWAY, authGatewayDelegate)

    private val inventoryGatewayDelegate = lazy {
        inventoryGatewayOverride ?: inventoryGatewayProvider?.invoke() ?: OfflineFixtureInventoryGateway()
    }

    private val inventoryGateway: DeviceInventoryGateway
        get() = resolveDependency(AppContainerDependency.INVENTORY_GATEWAY, inventoryGatewayDelegate)

    private val realtimeTransportDelegate = lazy {
        realtimeTransportOverride ?: realtimeTransportProvider?.invoke() ?: OfflineFixtureRealtimeTransport()
    }

    override val realtimeTransport: RealtimeSessionTransport
        get() = resolveDependency(AppContainerDependency.REALTIME_TRANSPORT, realtimeTransportDelegate)

    private val sessionCoordinatorDelegate = lazy {
        SessionCoordinator(
            authGateway = authGateway,
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
                realtimeTransport.invalidateForEpoch(
                    expectedEpoch = oldEpoch,
                    reason = connErr
                )
            }
        }
    }

    override val sessionCoordinator: SessionCoordinator
        get() = resolveDependency(AppContainerDependency.SESSION_COORDINATOR, sessionCoordinatorDelegate)

    private val inventoryRepositoryDelegate = lazy {
        DeviceInventoryRepository(
            sessionCoordinator = sessionCoordinator,
            inventoryGateway = inventoryGateway
        )
    }

    override val inventoryRepository: DeviceInventoryRepository
        get() = resolveDependency(AppContainerDependency.INVENTORY_REPOSITORY, inventoryRepositoryDelegate)

    private val robotTransportDelegate = lazy {
        robotTransportOverride ?: robotTransportProvider?.invoke() ?: SimulatorRobotTransport(
            dispatcherProvider = dispatcherProvider,
            clock = clock
        )
    }

    override val robotTransport: RobotTransport
        get() = resolveDependency(AppContainerDependency.ROBOT_TRANSPORT, robotTransportDelegate)

    private val localTelemetrySourceDelegate = lazy {
        localStreamSourceOverride ?: localStreamSourceProvider?.invoke() ?: SyntheticReplayTelemetrySource.fromAssetManager(
            assetManager = context?.assets,
            ioDispatcher = dispatcherProvider.io
        )
    }

    private val localTelemetrySource: LocalTelemetryStreamSource
        get() = resolveDependency(AppContainerDependency.LOCAL_TELEMETRY_SOURCE, localTelemetrySourceDelegate)

    private val localMapRepositoryDelegate = lazy {
        LocalMapRepository(
            streamSource = localTelemetrySource,
            dispatcherProvider = dispatcherProvider,
            clock = clock,
            diagnosticLogger = localDiagnosticLoggerOverride ?: LocalDiagnosticLogger()
        )
    }

    override val localMapRepository: LocalMapRepository
        get() = resolveDependency(AppContainerDependency.LOCAL_MAP_REPOSITORY, localMapRepositoryDelegate)

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
                        DiagnosticEvent(
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

    override fun startOfflineMockPilot(observationWindowMs: Long): GateActivationResult {
        if (pilotAuthorizationGate.isAuthorized ||
            pilotAuthorizationGate.hasInFlightOrIncompleteCleanup() ||
            pilotSessionController.isOperationInFlight.value
        ) {
            return GateActivationResult.Rejected(
                PilotAbortReason.AuthorizationExpiredOrInvalid,
                "Cannot switch to offline mock pilot while live session or cleanup is unresolved"
            )
        }
        if (observationWindowMs !in 1L..PilotRequestBudgets.MAX_OBSERVATION_WINDOW_MS) {
            return GateActivationResult.Rejected(
                PilotAbortReason.AuthorizationExpiredOrInvalid,
                "Mock pilot observation window must be between 1 ms and 30 minutes (1800000 ms)"
            )
        }
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
            budgets = PilotRequestBudgets(observationWindowMs = observationWindowMs),
            authorizedAtElapsedMs = nowMs,
            observationDeadlineElapsedMs = nowMs + observationWindowMs,
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

    private val provisionedAuthStore: ProvisionedPilotAuthorizationStore by lazy {
        pilotTestHooks?.provisionedAuthStoreOverride ?: context?.let { appCtx ->
            FileBackedProvisionedPilotAuthorizationStore(
                File(
                    appCtx.noBackupFilesDir,
                    FileBackedProvisionedPilotAuthorizationStore.DIRECTORY_NAME
                )
            )
        } ?: InMemoryProvisionedPilotAuthorizationStore()
    }

    private val installedApkVerifier: InstalledApkVerifier by lazy {
        val override = pilotTestHooks?.installedApkVerificationOverride
        when {
            override != null -> InstalledApkVerifier { override }
            context != null -> AndroidInstalledApkVerifier(context)
            else -> InstalledApkVerifier {
                InstalledApkVerification(
                    supported = false,
                    actualApkSha256 = null,
                    detail = "UNSUPPORTED_NO_ANDROID_CONTEXT"
                )
            }
        }
    }

    private val liveActivationLock = Any()

    override fun loadProvisionedLiveAuthorization(): ProvisionedAuthorizationLoadResult {
        if (!isLivePilotSupported) {
            return ProvisionedAuthorizationLoadResult.Invalid("Live pilot authorization is disabled on offline build")
        }
        return ExternalPilotAuthorizationLoader.loadForReview(
            store = provisionedAuthStore,
            runtimeAppIdentity = pilotTestHooks?.runtimeAppIdentityOverride ?: runtimeLiveIdentity,
            expectedRuntimeIdentity = pilotTestHooks?.expectedRuntimeIdentityOverride,
            installedApkVerifier = installedApkVerifier,
            allowedAuthOrigins = pilotTestHooks?.allowedAuthOriginsOverride
                ?: PilotAuthorizationGate.CANONICAL_AUTH_ORIGINS,
            allowUnsupportedInstalledApk = pilotTestHooks?.allowUnsupportedInstalledApkForHostTests ?: false
        )
    }

    override fun activateLivePilot(expectedRecordSha256: String): GateActivationResult {
        if (!isLivePilotSupported) {
            return GateActivationResult.Rejected(
                PilotAbortReason.AuthorizationExpiredOrInvalid,
                "Live pilot authorization is disabled on offline build"
            )
        }
        return synchronized(liveActivationLock) {
            val nowMs = authClock.elapsedRealtimeMs()
            val prep = ExternalPilotAuthorizationLoader.prepareActivation(
                store = provisionedAuthStore,
                expectedRecordSha256 = expectedRecordSha256,
                runtimeAppIdentity = pilotTestHooks?.runtimeAppIdentityOverride ?: runtimeLiveIdentity,
                expectedRuntimeIdentity = pilotTestHooks?.expectedRuntimeIdentityOverride,
                installedApkVerifier = installedApkVerifier,
                allowedAuthOrigins = pilotTestHooks?.allowedAuthOriginsOverride
                    ?: PilotAuthorizationGate.CANONICAL_AUTH_ORIGINS,
                nowElapsedMs = nowMs,
                allowUnsupportedInstalledApk = pilotTestHooks?.allowUnsupportedInstalledApkForHostTests ?: false
            )
            when (prep) {
                is ProvisionedActivationPreparation.Rejected ->
                    GateActivationResult.Rejected(prep.reason, prep.safeMessage)
                is ProvisionedActivationPreparation.Ready -> {
                    when (
                        val consumeRes = provisionedAuthStore.reserveAndConsume(
                            authorizationId = prep.context.authorizationId,
                            recordSha256 = prep.recordSha256
                        )
                    ) {
                        is ConsumeAuthorizationResult.AlreadyConsumed ->
                            GateActivationResult.Rejected(
                                PilotAbortReason.AuthorizationExpiredOrInvalid,
                                consumeRes.safeMessage
                            )
                        is ConsumeAuthorizationResult.Failed ->
                            GateActivationResult.Rejected(
                                PilotAbortReason.AuthorizationExpiredOrInvalid,
                                consumeRes.safeMessage
                            )
                        is ConsumeAuthorizationResult.Consumed -> {
                            if (!consumeRes.pendingRecordDeleted) {
                                GateActivationResult.Rejected(
                                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                                    consumeRes.warningMessage
                                        ?: "Durable consumption succeeded, but pending approval record deletion failed"
                                )
                            } else {
                                pilotSessionController.activatePilot(prep.context)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        if (robotTransportDelegate.isInitialized()) {
            (robotTransportDelegate.value as? AutoCloseable)?.close()
        }
        if (realtimeTransportDelegate.isInitialized()) {
            realtimeTransportDelegate.value.close()
        }
        offlineMockPilotController.close()
        pilotSessionController.close()
        if (localMapRepositoryDelegate.isInitialized()) {
            localMapRepositoryDelegate.value.close()
        }
    }
}

private enum class PilotUiWorkflow {
    MOCK,
    LIVE
}

class PilotCapabilityBridgeImpl(
    private val container: PilotAppContainer
) : PilotCapabilityBridge {

    override suspend fun enforceDeadlinesOnResume() {
        container.pilotSessionController.enforceDeadlineIfExpired()
        container.offlineMockPilotController.enforceDeadlineIfExpired()
    }

    @Composable
    override fun Content(
        showLocalMapViewer: Boolean,
        onDismissLocalMapViewer: () -> Unit,
        renderWorkspace: @Composable (
            onOpenPilotSheet: () -> Unit,
            pilotButtonLabel: String,
            unresolvedCleanupNotice: String?,
            hideLocalMapForPilotSheet: Boolean
        ) -> Unit
    ) {
        val mockPilotController = container.offlineMockPilotController
        val livePilotController = container.pilotSessionController
        val isLivePilotSupported = container.isLivePilotSupported

        val livePilotMode by livePilotController.pilotModeState.collectAsStateWithLifecycle()
        val liveOperationInFlight by livePilotController.isOperationInFlight.collectAsStateWithLifecycle()
        val liveLastDisconnect by livePilotController.lastDisconnectResult.collectAsStateWithLifecycle()
        val liveCleanupInFlightOrIncomplete = isLivePilotSupported && (
            container.pilotAuthorizationGate.hasInFlightOrIncompleteCleanup() ||
                liveLastDisconnect?.storageCleared == false
            )
        val liveSessionActive = isLivePilotSupported && (
            container.pilotAuthorizationGate.isAuthorized ||
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
                onDismissLocalMapViewer()
            }
        }
        LaunchedEffect(liveSessionActive) {
            if (liveSessionActive) {
                showPilotSheet = true
                onDismissLocalMapViewer()
            }
        }

        val activeController = if (isLivePilotSupported && activeWorkflow == PilotUiWorkflow.LIVE) {
            livePilotController
        } else {
            mockPilotController
        }
        val activeGate = if (isLivePilotSupported && activeWorkflow == PilotUiWorkflow.LIVE) {
            container.pilotAuthorizationGate
        } else {
            container.offlineMockAuthorizationGate
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
            (container.pilotAuthorizationGate.hasIncompleteCleanup() || liveLastDisconnect?.storageCleared == false)
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
                renderWorkspace(
                    {
                        if (hasUnresolvedLiveSession) {
                            activeWorkflow = PilotUiWorkflow.LIVE
                        }
                        showPilotSheet = true
                    },
                    if (isLivePilotSupported) {
                        "Pilot Readiness (Offline / Live Review)"
                    } else {
                        "Pilot Readiness (Offline / Mock)"
                    },
                    cleanupFailureNotice,
                    showPilotSheet
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
                                    (container.pilotAuthorizationGate.hasIncompleteCleanup() ||
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
                                    if (container.pilotAuthorizationGate.hasIncompleteCleanup() ||
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
                                container.startOfflineMockPilot()
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
                                container.startOfflineMockPilot(observationWindowMs = 60_000L)
                            },
                            onOpenLiveAuthReview = if (isLivePilotSupported) {
                                {
                                    activeWorkflow = PilotUiWorkflow.LIVE
                                    showLiveAuthReview = true
                                    liveAuthValidationError = null
                                    provisionedAuthResult = container.loadProvisionedLiveAuthorization()
                                }
                            } else {
                                null
                            },
                            onRefreshProvisionedAuth = if (isLivePilotSupported) {
                                {
                                    liveAuthValidationError = null
                                    provisionedAuthResult = container.loadProvisionedLiveAuthorization()
                                }
                            } else {
                                null
                            },
                            onCancelLiveAuthReview = {
                                showLiveAuthReview = false
                                liveAuthValidationError = null
                                if (!container.pilotAuthorizationGate.isAuthorized &&
                                    lastObservedOrigins == null &&
                                    lastAbort == null
                                ) {
                                    activeWorkflow = PilotUiWorkflow.MOCK
                                }
                            },
                            onActivateLivePilot = if (isLivePilotSupported) {
                                { expectedSha ->
                                    when (val res = container.activateLivePilot(expectedSha)) {
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
 * Used ONLY when [DefaultPilotContainer] is instantiated in context-less pure-JVM unit tests;
 * performs zero disk writes and never provides software-key cryptographic storage fallback.
 */
private object NonPersistentSessionStore : TokenStore {
    override suspend fun save(record: PersistedSessionRecord): TokenSaveResult =
        TokenSaveResult.Failure("NO_ANDROID_CONTEXT")

    override suspend fun load(): TokenLoadResult = TokenLoadResult.Empty

    override suspend fun clear(): TokenClearResult = TokenClearResult.Success
}
