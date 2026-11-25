package com.weback.robot.core

import com.weback.robot.protocol.RobotTransport
import com.weback.robot.protocol.SimulatorRobotTransport

interface AppContainer {
    val robotTransport: RobotTransport
    val dispatcherProvider: DispatcherProvider
}

class DefaultAppContainer : AppContainer {
    override val dispatcherProvider: DispatcherProvider by lazy {
        DefaultDispatcherProvider()
    }

    override val robotTransport: RobotTransport by lazy {
        SimulatorRobotTransport()
    }
}
