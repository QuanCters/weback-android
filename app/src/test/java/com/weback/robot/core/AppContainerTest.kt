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
        assertNotNull(container.sessionCoordinator)
        assertNotNull(container.inventoryRepository)
        assertNotNull(container.realtimeTransport)
        assertNotNull(container.endpointTrustPolicy)
        assertNotNull(container.diagnosticLogger)

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

    @Test
    fun ac501_3_defaultAppContainerExposesProviderFactoryAndResolutionOracleSeams() {
        var authProviderCalls = 0
        var inventoryProviderCalls = 0
        var realtimeProviderCalls = 0
        var robotProviderCalls = 0
        var localSourceProviderCalls = 0
        val resolutions = mutableListOf<AppContainerDependency>()

        val container = DefaultAppContainer(
            authGatewayProvider = {
                authProviderCalls++
                com.weback.robot.protocol.auth.OfflineFixtureAuthGateway()
            },
            inventoryGatewayProvider = {
                inventoryProviderCalls++
                com.weback.robot.protocol.inventory.OfflineFixtureInventoryGateway()
            },
            realtimeTransportProvider = {
                realtimeProviderCalls++
                com.weback.robot.protocol.ws.OfflineFixtureRealtimeTransport()
            },
            robotTransportProvider = {
                robotProviderCalls++
                SimulatorRobotTransport()
            },
            localStreamSourceProvider = {
                localSourceProviderCalls++
                com.weback.robot.protocol.local.SyntheticReplayTelemetrySource(
                    fixtureBytesProvider = { byteArrayOf() },
                    expectedSha256Hex = null
                )
            },
            dependencyResolutionObserver = { resolutions.add(it) }
        )

        assertEquals(0, authProviderCalls)
        assertEquals(0, inventoryProviderCalls)
        assertEquals(0, realtimeProviderCalls)
        assertEquals(0, robotProviderCalls)
        assertEquals(0, localSourceProviderCalls)
        assertTrue(resolutions.isEmpty())

        // Accessing localMapRepository resolves ONLY local source/repository and ZERO simulator/cloud providers
        val repo1 = container.localMapRepository
        val repo2 = container.localMapRepository
        assertSame(repo1, repo2)
        assertEquals(1, localSourceProviderCalls)
        assertEquals(0, robotProviderCalls)
        assertEquals(0, authProviderCalls)
        assertEquals(0, inventoryProviderCalls)
        assertEquals(0, realtimeProviderCalls)
        assertEquals(
            listOf(
                AppContainerDependency.LOCAL_MAP_REPOSITORY,
                AppContainerDependency.LOCAL_TELEMETRY_SOURCE,
                AppContainerDependency.LOCAL_MAP_REPOSITORY
            ),
            resolutions
        )

        // Cached singleton retrieval on robotTransport triggers a second resolution event while keeping 1 provider creation
        resolutions.clear()
        val robot1 = container.robotTransport
        val robot2 = container.robotTransport
        assertSame(robot1, robot2)
        assertEquals(1, robotProviderCalls)
        assertEquals(
            listOf(AppContainerDependency.ROBOT_TRANSPORT, AppContainerDependency.ROBOT_TRANSPORT),
            resolutions
        )

        // Cloud session/inventory/realtime providers are invoked lazily on resolution
        resolutions.clear()
        assertNotNull(container.inventoryRepository)
        assertNotNull(container.realtimeTransport)
        assertEquals(1, authProviderCalls)
        assertEquals(1, inventoryProviderCalls)
        assertEquals(1, realtimeProviderCalls)
        assertTrue(resolutions.contains(AppContainerDependency.INVENTORY_REPOSITORY))
        assertTrue(resolutions.contains(AppContainerDependency.SESSION_COORDINATOR))
        assertTrue(resolutions.contains(AppContainerDependency.AUTH_GATEWAY))
        assertTrue(resolutions.contains(AppContainerDependency.INVENTORY_GATEWAY))
        assertTrue(resolutions.contains(AppContainerDependency.REALTIME_TRANSPORT))

        container.close()
    }
}
