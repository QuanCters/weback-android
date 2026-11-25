package com.weback.robot.core

import com.weback.robot.protocol.RobotTransport
import com.weback.robot.protocol.SimulatorRobotTransport

/**
 * Manual dependency container owning transport, dispatcher, and clock lifecycles.
 */
interface AppContainer : AutoCloseable {
    val robotTransport: RobotTransport
    val dispatcherProvider: DispatcherProvider
    val clock: MonotonicClock
    override fun close() {}
}

class DefaultAppContainer(
    override val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    override val clock: MonotonicClock = SystemMonotonicClock
) : AppContainer {
    override val robotTransport: RobotTransport by lazy {
        SimulatorRobotTransport(
            dispatcherProvider = dispatcherProvider,
            clock = clock
        )
    }

    override fun close() {
        (robotTransport as? AutoCloseable)?.close()
    }
}
