package com.weback.robot.core

import android.content.Context
import com.weback.robot.app.local.LocalDiagnosticLogger
import com.weback.robot.app.local.LocalDiagnosticSink
import com.weback.robot.app.local.LocalMapRepository
import com.weback.robot.protocol.RobotTransport
import com.weback.robot.protocol.SimulatorRobotTransport
import com.weback.robot.protocol.local.LocalTelemetryStreamSource
import com.weback.robot.protocol.local.SyntheticReplayTelemetrySource
import com.weback.robot.security.DiagnosticLogger
import com.weback.robot.security.NoOpDiagnosticLogger

/**
 * Offline/local variant [AppContainer] containing zero concrete cloud pilot gateways,
 * token stores, Keystore controllers, or readiness UI (`pilotCapabilityBridge == null`).
 */
class OfflineAppContainer(
    override val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    override val clock: MonotonicClock = SystemMonotonicClock,
    private val context: Context? = null,
    override val diagnosticLogger: DiagnosticLogger = NoOpDiagnosticLogger,
    private val robotTransportOverride: RobotTransport? = null,
    private val localStreamSourceOverride: LocalTelemetryStreamSource? = null,
    private val localDiagnosticLoggerOverride: LocalDiagnosticSink? = null,
    private val robotTransportProvider: (() -> RobotTransport)? = null,
    private val localStreamSourceProvider: (() -> LocalTelemetryStreamSource)? = null,
    private val dependencyResolutionObserver: ((AppContainerDependency) -> Unit)? = null
) : AppContainer {

    override val pilotCapabilityBridge: PilotCapabilityBridge? = null

    private fun <T> resolveDependency(
        dependency: AppContainerDependency,
        delegate: Lazy<T>
    ): T {
        dependencyResolutionObserver?.invoke(dependency)
        return delegate.value
    }

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

    override fun close() {
        if (robotTransportDelegate.isInitialized()) {
            (robotTransportDelegate.value as? AutoCloseable)?.close()
        }
        if (localMapRepositoryDelegate.isInitialized()) {
            localMapRepositoryDelegate.value.close()
        }
    }
}
