package com.weback.robot.core

import com.weback.robot.app.local.LocalMapRepository
import com.weback.robot.protocol.RobotTransport
import com.weback.robot.security.DiagnosticLogger

enum class AppContainerDependency {
    ROBOT_TRANSPORT,
    AUTH_GATEWAY,
    INVENTORY_GATEWAY,
    REALTIME_TRANSPORT,
    SESSION_COORDINATOR,
    INVENTORY_REPOSITORY,
    LOCAL_TELEMETRY_SOURCE,
    LOCAL_MAP_REPOSITORY
}

/**
 * Shared variant-neutral manual dependency container owning offline simulator transport,
 * diagnostic logger, local map replay repository, and optional [PilotCapabilityBridge].
 */
interface AppContainer : AutoCloseable {
    val robotTransport: RobotTransport
    val dispatcherProvider: DispatcherProvider
    val clock: MonotonicClock
    val diagnosticLogger: DiagnosticLogger
    val localMapRepository: LocalMapRepository
    val pilotCapabilityBridge: PilotCapabilityBridge?
        get() = null

    override fun close() {}
}
