package com.weback.robot.core

import com.weback.robot.domain.model.RobotCommand
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.protocol.SimulatorRobotTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppContainerTest {

    @Test
    fun testContainerInstantiationAndSingletonScoping() {
        val container = DefaultAppContainer()

        assertNotNull(container.robotTransport)
        assertNotNull(container.dispatcherProvider)
        assertNotNull(container.clock)

        val transport1 = container.robotTransport
        val transport2 = container.robotTransport
        assertSame("RobotTransport must be singleton-scoped in AppContainer", transport1, transport2)

        val dispatchers1 = container.dispatcherProvider
        val dispatchers2 = container.dispatcherProvider
        assertSame("DispatcherProvider must be singleton-scoped in AppContainer", dispatchers1, dispatchers2)

        container.close()
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

        container.close()
    }

    @Test
    fun testContainerCloseStopsActiveSimulatorJobs() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = testDispatcher
            override val io = testDispatcher
            override val default = testDispatcher
        }
        val container = DefaultAppContainer(
            dispatcherProvider = dispatchers,
            clock = MonotonicClock { testScheduler.currentTime }
        )

        val transport = container.robotTransport as SimulatorRobotTransport
        transport.sendCommand(RobotCommand.CLEAN)
        assertTrue(transport.hasActiveSimulationJob())

        container.close()
        assertFalse(transport.hasActiveSimulationJob())
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(100, transport.robotState.value.batteryPercentage)
    }
}
