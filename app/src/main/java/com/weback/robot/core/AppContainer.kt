package com.weback.robot.core

import android.content.Context
import com.weback.robot.app.session.DeviceInventoryRepository
import com.weback.robot.app.session.SessionCoordinator
import com.weback.robot.domain.model.ConnectivityError
import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.protocol.RobotTransport
import com.weback.robot.protocol.SimulatorRobotTransport
import com.weback.robot.protocol.auth.AuthGateway
import com.weback.robot.protocol.auth.DisabledPasswordlessRefreshStrategy
import com.weback.robot.protocol.auth.OfflineFixtureAuthGateway
import com.weback.robot.protocol.inventory.DeviceInventoryGateway
import com.weback.robot.protocol.inventory.OfflineFixtureInventoryGateway
import com.weback.robot.protocol.ws.OfflineFixtureRealtimeTransport
import com.weback.robot.protocol.ws.RealtimeSessionTransport
import com.weback.robot.security.AndroidKeystoreAeadCipherProvider
import com.weback.robot.security.DiagnosticLogger
import com.weback.robot.security.EndpointTrustPolicy
import com.weback.robot.security.KeystoreAeadTokenStore
import com.weback.robot.security.NoOpDiagnosticLogger
import com.weback.robot.security.ProductionEndpointTrustPolicy
import com.weback.robot.security.TokenClearResult
import com.weback.robot.security.TokenLoadResult
import com.weback.robot.security.TokenSaveResult
import com.weback.robot.security.TokenStore
import java.io.File

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
    private val realtimeTransportOverride: RealtimeSessionTransport? = null
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

    override fun close() {
        (robotTransport as? AutoCloseable)?.close()
        realtimeTransport.close()
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
