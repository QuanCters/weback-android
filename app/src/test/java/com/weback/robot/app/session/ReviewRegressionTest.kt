package com.weback.robot.app.session

import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.BootSessionTimeProvider
import com.weback.robot.core.BootTimeSnapshot
import com.weback.robot.domain.device.DeviceCapabilityClassifier
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.model.DeviceInventoryItem
import com.weback.robot.domain.model.InventoryState
import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.SessionCredentials
import com.weback.robot.domain.model.SessionState
import com.weback.robot.protocol.auth.AuthGateway
import com.weback.robot.protocol.auth.RefreshGrantStrategy
import com.weback.robot.protocol.inventory.DeviceInventoryGateway
import com.weback.robot.protocol.inventory.InventoryFetchResult
import com.weback.robot.protocol.ws.ConnectionConfig
import com.weback.robot.protocol.ws.OkHttpWebSocketTransport
import com.weback.robot.security.TestLoopbackTrustPolicy
import com.weback.robot.security.TokenClearResult
import com.weback.robot.security.TokenLoadResult
import com.weback.robot.security.TokenSaveResult
import com.weback.robot.security.TokenStore
import com.weback.robot.security.UriValidator
import com.weback.robot.testutils.TestDispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewRegressionTest {

    @Test
    fun testSelectDeviceAfterWsConnectDoesNotBreakGenerationAndSendStallSchedulesOnce() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val wsOrigin = UriValidator.NormalizedOrigin("ws", "127.0.0.1", server.port)
            val httpOrigin = UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port)
            val policy = TestLoopbackTrustPolicy(setOf(wsOrigin, httpOrigin))
            var nowMs = 1_000L
            val clock = AuthMonotonicClock { nowMs }
            val dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler))

            val open1 = CountDownLatch(1)
            val open2 = CountDownLatch(1)
            val sockets = mutableListOf<WebSocket>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sockets.add(webSocket)
                    open1.countDown()
                }
            }))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sockets.add(webSocket)
                    open2.countDown()
                }
            }))

            val transport = OkHttpWebSocketTransport(
                trustPolicy = policy,
                authClock = clock,
                dispatchers = dispatchers,
                connectionConfig = ConnectionConfig(reconnectDelayMs = 4_000L, sendStallTimeoutMs = 20_000L, maxReconnectAttempts = 1)
            )
            val session = SessionCredentials(
                jwtToken = "jwt-reg",
                apiUrl = "http://127.0.0.1:${server.port}/api",
                wssUrl = "ws://127.0.0.1:${server.port}/ws",
                regionName = "us-east-1",
                expiresInSeconds = 3_600L,
                issuedAtElapsedMs = 0L
            )
            transport.connect(session, epoch = 1L)
            assertTrue(open1.await(5, TimeUnit.SECONDS))
            val waitConnected = System.currentTimeMillis() + 2_000L
            while (transport.connectionState.value !is ConnectionState.Connected && System.currentTimeMillis() < waitConnected) {
                Thread.sleep(10)
            }
            assertTrue(transport.connectionState.value is ConnectionState.Connected)

            // Critical #2: selectDevice() AFTER connect() MUST NOT invalidate active connectionGeneration
            val laser = DeviceInventoryItem(
                thingName = "laser_after_connect",
                subType = "xs-x8-eu",
                nickname = "Laser",
                isAdmin = true,
                capability = DeviceCapabilityClassifier.classify("xs-x8-eu")
            )
            transport.selectDevice(laser, epoch = 1L)
            sockets[0].send("""{"notify_info":"thing_status_update","thing_name":"laser_after_connect","working_status":"AutoClean","battery_level":55}""")
            val waitTelemetry = System.currentTimeMillis() + 2_000L
            while (transport.robotState.value.batteryPercentage != 55 && System.currentTimeMillis() < waitTelemetry) {
                Thread.sleep(10)
            }
            assertEquals("Telemetry after selectDevice-post-connect must apply", 55, transport.robotState.value.batteryPercentage)
            assertEquals(RobotMode.CLEAN, transport.robotState.value.mode)

            // Important #1: Single send-stall with maxReconnectAttempts=1 MUST schedule 1 reconnect (attempt=1) and NOT double-burn attempts
            nowMs = 25_000L
            assertFalse(transport.sendOutbound("""{"opt":"cmd"}""", epoch = 1L))
            val st = transport.connectionState.value as? ConnectionState.Reconnecting
            assertTrue("Expected Reconnecting(attempt=1), got ${transport.connectionState.value}", st != null && st.attempt == 1)

            // Advancing virtual time by 4_000ms triggers reconnect #2
            testScheduler.advanceTimeBy(4_000L)
            testScheduler.runCurrent()
            assertTrue("Expected second WS connection after 4000ms reconnect", open2.await(5, TimeUnit.SECONDS))

            transport.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testTransientInventoryErrorPreservesLoadedStateAndRefreshAdvancesEpochAndSaveFailureClearsOldBlob() = runTest {
        val time = BootSessionTimeProvider { BootTimeSnapshot("boot_1", 10_000L, 1_700_000_000_000L) }
        var stored: PersistedSessionRecord? = null
        var failNextSave = false
        val store = object : TokenStore {
            override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
                if (failNextSave) return TokenSaveResult.Failure("SIMULATED_ERR")
                stored = record
                return TokenSaveResult.Success
            }
            override suspend fun load(): TokenLoadResult = stored?.let { TokenLoadResult.Loaded(it) } ?: TokenLoadResult.Empty
            override suspend fun clear(): TokenClearResult {
                stored = null
                return TokenClearResult.Success
            }
        }
        val authGateway = object : AuthGateway {
            override suspend fun signIn(credentials: AuthCredentials) = AuthResult.Success(
                SessionCredentials("jwt-${credentials.account}", "http://127.0.0.1:80/api", "ws://127.0.0.1:80/ws", "us", 3_600L, 10_000L)
            )
        }
        var failInventory = false
        val invGateway = object : DeviceInventoryGateway {
            override suspend fun fetchThings(session: SessionCredentials): InventoryFetchResult =
                if (failInventory) {
                    InventoryFetchResult.Failure(com.weback.robot.domain.model.ConnectivityError.TransportTimeout)
                } else {
                    InventoryFetchResult.Success(
                        listOf(
                            DeviceInventoryItem(
                                thingName = "r1",
                                subType = "xs-x8-eu",
                                nickname = "R1",
                                isAdmin = true,
                                capability = DeviceCapabilityClassifier.classify("xs-x8-eu")
                            )
                        )
                    )
                }
        }
        val syntheticRefresh = object : RefreshGrantStrategy {
            override val isEnabled: Boolean = true
            override suspend fun refresh(session: SessionCredentials) =
                AuthResult.Success(session.copy(jwtToken = "jwt-refreshed"))
        }

        val coord = SessionCoordinator(authGateway, store, time, refreshStrategy = syntheticRefresh)
        val repo = DeviceInventoryRepository(coord, invGateway)

        coord.signIn(AuthCredentials("+1", "A", "pw".toCharArray()))
        val epoch1 = coord.currentEpoch()
        val okInv = repo.refreshInventory()
        assertTrue(okInv is InventoryState.Loaded)

        // Important #5: Transient TransportTimeout must preserve prior valid Loaded inventory
        failInventory = true
        val errReturn = repo.refreshInventory()
        assertTrue(errReturn is InventoryState.Error)
        assertTrue("Loaded inventory must remain intact on transient error", repo.inventoryState.value is InventoryState.Loaded)

        // Important #4: Synthetic refresh success must advance sessionEpoch
        coord.refreshIfNeeded(force = true)
        val epochAfterRefresh = coord.currentEpoch()
        assertTrue("Expected refresh to advance epoch > $epoch1, got $epochAfterRefresh", epochAfterRefresh > epoch1)

        // Important #3: Account B signIn save() failure must clear prior Account A persisted blob
        failNextSave = true
        val failedB = coord.signIn(AuthCredentials("+1", "B", "pw".toCharArray()))
        assertTrue(failedB is AuthResult.Failure)
        assertEquals(SessionState.SignedOut, coord.sessionState.value)
        assertEquals(null, stored)
    }

    @Test
    fun testCancellationExceptionPropagatesThroughOkHttpGateways() = runTest {
        val policy = TestLoopbackTrustPolicy(setOf(UriValidator.NormalizedOrigin("http", "127.0.0.1", 8080)))
        val job = kotlinx.coroutines.Job()
        job.cancel(CancellationException("cancelled caller"))
        val cancelledClock = AuthMonotonicClock { throw CancellationException("cancelled") }
        val invGateway = com.weback.robot.protocol.inventory.OkHttpDeviceInventoryGateway(policy, cancelledClock)
        var caughtCancel = false
        try {
            invGateway.fetchThings(SessionCredentials("t", "http://127.0.0.1:8080/api", "ws://127.0.0.1:8080/ws", "us", 100L, 0L))
        } catch (_: CancellationException) {
            caughtCancel = true
        }
        assertTrue(caughtCancel)
    }
}
