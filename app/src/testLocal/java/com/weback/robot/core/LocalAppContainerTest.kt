package com.weback.robot.core

import com.weback.robot.domain.model.RobotCommand
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.protocol.SimulatorRobotTransport
import com.weback.robot.protocol.local.SyntheticReplayTelemetrySource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalAppContainerTest {

    @Test
    fun testOfflineContainerInstantiationSingletonScopingAndNullPilotBridge() {
        val container = OfflineAppContainer()
        try {
            assertNull(container.pilotCapabilityBridge)
            assertNotNull(container.robotTransport)
            assertNotNull(container.dispatcherProvider)
            assertNotNull(container.clock)
            assertNotNull(container.diagnosticLogger)
            assertNotNull(container.localMapRepository)

            assertSame(container.robotTransport, container.robotTransport)
            assertSame(container.dispatcherProvider, container.dispatcherProvider)
            assertSame(container.localMapRepository, container.localMapRepository)
        } finally {
            container.close()
        }
    }

    @Test
    fun testInitialSimulatorTransportStateAndCloseStopsActiveJobs() = runTest {
        val initialContainer = OfflineAppContainer()
        try {
            val state = initialContainer.robotTransport.robotState.value
            assertEquals("WeBack Simulator Robot", state.name)
            assertEquals(100, state.batteryPercentage)
            assertEquals(RobotMode.DOCK, state.mode)
            assertTrue(state.isDustbinClean)
            assertTrue(state.isDocked)
        } finally {
            initialContainer.close()
        }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = testDispatcher
            override val io = testDispatcher
            override val default = testDispatcher
        }
        val timedContainer = OfflineAppContainer(
            dispatcherProvider = dispatchers,
            clock = MonotonicClock { testScheduler.currentTime }
        )
        val transport = timedContainer.robotTransport as SimulatorRobotTransport
        transport.sendCommand(RobotCommand.CLEAN)
        assertTrue(transport.hasActiveSimulationJob())

        timedContainer.close()
        assertFalse(transport.hasActiveSimulationJob())
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(100, transport.robotState.value.batteryPercentage)
    }

    @Test
    fun testLazyR1ProviderResolutionOrderAndStructuralAbsenceOfCloudMembers() {
        var robotProviderCalls = 0
        var localSourceProviderCalls = 0
        val resolutions = mutableListOf<AppContainerDependency>()

        val container = OfflineAppContainer(
            robotTransportProvider = {
                robotProviderCalls++
                SimulatorRobotTransport()
            },
            localStreamSourceProvider = {
                localSourceProviderCalls++
                SyntheticReplayTelemetrySource(
                    fixtureBytesProvider = { byteArrayOf() },
                    expectedSha256Hex = null
                )
            },
            dependencyResolutionObserver = { resolutions.add(it) }
        )

        try {
            assertEquals(0, robotProviderCalls)
            assertEquals(0, localSourceProviderCalls)
            assertTrue(resolutions.isEmpty())

            val repo1 = container.localMapRepository
            val repo2 = container.localMapRepository
            assertSame(repo1, repo2)
            assertEquals(1, localSourceProviderCalls)
            assertEquals(0, robotProviderCalls)
            assertEquals(
                listOf(
                    AppContainerDependency.LOCAL_MAP_REPOSITORY,
                    AppContainerDependency.LOCAL_TELEMETRY_SOURCE,
                    AppContainerDependency.LOCAL_MAP_REPOSITORY
                ),
                resolutions
            )

            resolutions.clear()
            val robot1 = container.robotTransport
            val robot2 = container.robotTransport
            assertSame(robot1, robot2)
            assertEquals(1, robotProviderCalls)
            assertEquals(
                listOf(AppContainerDependency.ROBOT_TRANSPORT, AppContainerDependency.ROBOT_TRANSPORT),
                resolutions
            )

            // Verify AppContainer & OfflineAppContainer expose zero cloud or deferred L04B members
            val forbiddenMemberTokens = listOf(
                "AuthGateway",
                "DeviceInventoryGateway",
                "RealtimeSessionTransport",
                "SessionCoordinator",
                "DeviceInventoryRepository",
                "PilotSessionController",
                "PilotAuthorizationGate",
                "EndpointTrustPolicy",
                "TokenStore",
                "ReceiveOnlyTcpSource",
                "LocalLanTrustPolicy"
            )
            val exposedSignatures = listOf(AppContainer::class.java, OfflineAppContainer::class.java)
                .flatMap { clazz ->
                    clazz.declaredFields.map { "${it.name}:${it.type.name}" } +
                        clazz.declaredMethods.map { "${it.name}:${it.returnType.name}" }
                }
            val leaks = exposedSignatures.filter { sig ->
                forbiddenMemberTokens.any { token -> sig.contains(token) }
            }
            assertTrue("Unexpected cloud or live-TCP members on OfflineAppContainer/AppContainer: $leaks", leaks.isEmpty())
        } finally {
            container.close()
        }
    }
}
