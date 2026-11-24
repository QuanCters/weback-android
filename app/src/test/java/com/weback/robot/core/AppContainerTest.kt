package com.weback.robot.core

import com.weback.robot.domain.model.RobotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppContainerTest {

    @Test
    fun testContainerInstantiationAndSingletonScoping() {
        val container = DefaultAppContainer()

        assertNotNull(container.robotTransport)
        assertNotNull(container.dispatcherProvider)

        val transport1 = container.robotTransport
        val transport2 = container.robotTransport
        assertSame("RobotTransport must be singleton-scoped in AppContainer", transport1, transport2)

        val dispatchers1 = container.dispatcherProvider
        val dispatchers2 = container.dispatcherProvider
        assertSame("DispatcherProvider must be singleton-scoped in AppContainer", dispatchers1, dispatchers2)
    }

    @Test
    fun testInitialSimulatorTransportState() {
        val container = DefaultAppContainer()
        val state = container.robotTransport.robotState.value

        assertEquals("WeBack Simulator Robot", state.name)
        assertEquals(100, state.batteryPercentage)
        assertEquals(RobotMode.DOCK, state.mode)
        assertTrue(state.isDustbinClean)
        assertTrue(state.isDocked)
    }
}
