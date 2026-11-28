package com.weback.robot.app.local

import com.weback.robot.core.AppContainerDependency
import com.weback.robot.core.DefaultAppContainer
import com.weback.robot.protocol.SimulatorRobotTransport
import com.weback.robot.protocol.auth.OfflineFixtureAuthGateway
import com.weback.robot.protocol.inventory.OfflineFixtureInventoryGateway
import com.weback.robot.protocol.local.SyntheticReplayTelemetrySource
import com.weback.robot.protocol.ws.OfflineFixtureRealtimeTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [DefaultAppContainer] provider-factory and cached/lazy resolution oracle seams
 * inside Task 4's authorized `app/local` test package (`AC-501.3` / `TEST-LOCAL-513`).
 */
class LocalAppContainerNonFallbackTest {

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
                OfflineFixtureAuthGateway()
            },
            inventoryGatewayProvider = {
                inventoryProviderCalls++
                OfflineFixtureInventoryGateway()
            },
            realtimeTransportProvider = {
                realtimeProviderCalls++
                OfflineFixtureRealtimeTransport()
            },
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
