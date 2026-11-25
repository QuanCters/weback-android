package com.weback.robot.app.pilot

import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.BootSessionTimeProvider
import com.weback.robot.core.BootTimeSnapshot
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.pilot.ExpectedCandidateIdentity
import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotAccountScope
import com.weback.robot.domain.pilot.PilotAccountScopeType
import com.weback.robot.domain.pilot.PilotAuthorizationContext
import com.weback.robot.domain.pilot.PilotAuthorizationGate
import com.weback.robot.domain.pilot.PilotEndpointConfig
import com.weback.robot.domain.pilot.PilotExternalApproval
import com.weback.robot.domain.pilot.PilotOperation
import com.weback.robot.domain.pilot.PilotRequestBudgets
import com.weback.robot.domain.pilot.PilotTargetDevice
import com.weback.robot.protocol.SimulatorRobotTransport
import com.weback.robot.protocol.auth.AuthEnvelopeConfig
import com.weback.robot.protocol.auth.OkHttpAuthGateway
import com.weback.robot.protocol.auth.RegionEndpointRouter
import com.weback.robot.protocol.inventory.OkHttpDeviceInventoryGateway
import com.weback.robot.protocol.pilot.PilotCapabilityRestrictedAuthGateway
import com.weback.robot.protocol.pilot.PilotCapabilityRestrictedInventoryGateway
import com.weback.robot.protocol.ws.ConnectionConfig
import com.weback.robot.protocol.ws.OkHttpWebSocketTransport
import com.weback.robot.security.AuthorizedPilotEndpointTrustPolicy
import com.weback.robot.security.OkHttpSecurityGuard
import com.weback.robot.security.TestLoopbackTrustPolicy
import com.weback.robot.security.TokenClearResult
import com.weback.robot.security.TokenLoadResult
import com.weback.robot.security.TokenSaveResult
import com.weback.robot.security.TokenStore
import com.weback.robot.security.UriValidator
import com.weback.robot.app.session.DeviceInventoryRepository
import com.weback.robot.app.session.SessionCoordinator
import com.weback.robot.testutils.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

@OptIn(ExperimentalCoroutinesApi::class)
class PilotBudgetEnforcementTest {

    @Test
    fun testEnforcesOneLoginOneInventoryAndCumulativeReconnectCapWithoutResettingOnOpen() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val sockets = mutableListOf<WebSocket>()
        val clientSockets = CopyOnWriteArrayList<Socket>()
        val trackingSocketFactory = object : SocketFactory() {
            private val delegate = getDefault()
            override fun createSocket(): Socket = delegate.createSocket().also { clientSockets.add(it) }
            override fun createSocket(host: String?, port: Int): Socket = delegate.createSocket(host, port).also { clientSockets.add(it) }
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                delegate.createSocket(host, port, localHost, localPort).also { clientSockets.add(it) }
            override fun createSocket(host: InetAddress?, port: Int): Socket = delegate.createSocket(host, port).also { clientSockets.add(it) }
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                delegate.createSocket(address, port, localAddress, localPort).also { clientSockets.add(it) }
        }
        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val dispatchers = TestDispatcherProvider(dispatcher)
            val authClock = AuthMonotonicClock { 5_000L }
            val timeProvider = BootSessionTimeProvider { BootTimeSnapshot("boot_1", 5_000L, 1_700_000_000_000L) }
            val runtimeId = ExpectedCandidateIdentity(
                "b0ce6ff1b3f65da67673d1e0cf7959b4266250ca",
                "com.weback.robot",
                "0.1.0-alpha",
                "3c933db2160c4407f44b3393dffdd3cf372074e5b06f8b29089ee0408e9fa90e"
            )
            val originHttp = "http://127.0.0.1:${server.port}"
            val loopbackPolicy = TestLoopbackTrustPolicy(
                setOf(
                    UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port),
                    UriValidator.NormalizedOrigin("ws", "127.0.0.1", server.port)
                )
            )
            val router = RegionEndpointRouter(globalOverrideUrl = "$originHttp/prod/oauth")
            val tokenStore = object : TokenStore {
                override suspend fun save(record: com.weback.robot.domain.model.PersistedSessionRecord) = TokenSaveResult.Success
                override suspend fun load() = TokenLoadResult.Empty
                override suspend fun clear() = TokenClearResult.Success
            }
            val pilotAuth = PilotCapabilityRestrictedAuthGateway(
                router, loopbackPolicy, authClock, dispatchers = dispatchers
            )
            val pilotInv = PilotCapabilityRestrictedInventoryGateway(
                OkHttpDeviceInventoryGateway(loopbackPolicy, authClock, dispatchers)
            )
            val coordinator = SessionCoordinator(pilotAuth, tokenStore, timeProvider)
            val invRepo = DeviceInventoryRepository(coordinator, pilotInv)
            // Crucial SEC-404 requirement: resetReconnectAttemptOnOpen = false (cumulative session cap of 3!)
            val wsClient = OkHttpSecurityGuard.buildHardenedClient(loopbackPolicy)
                .newBuilder()
                .socketFactory(trackingSocketFactory)
                .build()
            val wsDelegate = OkHttpWebSocketTransport(
                trustPolicy = loopbackPolicy,
                authClock = authClock,
                dispatchers = dispatchers,
                connectionConfig = ConnectionConfig(reconnectDelayMs = 4_000L, maxReconnectAttempts = 3),
                client = wsClient,
                resetReconnectAttemptOnOpen = false
            )
            val gate = PilotAuthorizationGate(allowedAuthOrigins = setOf(originHttp))
            val pilotTrust = AuthorizedPilotEndpointTrustPolicy(
                PilotEndpointConfig(originHttp, pinnedApiOrigin = "https://127.0.0.1:${server.port}", pinnedWssOrigin = "wss://127.0.0.1:${server.port}"),
                allowedAuthOrigins = setOf(originHttp),
                allowLoopbackForHermeticTests = true
            )
            val controller = PilotSessionController(
                gate = gate,
                expectedRuntimeIdentity = runtimeId,
                trustPolicy = pilotTrust,
                sessionCoordinator = coordinator,
                inventoryRepository = invRepo,
                pilotAuthGateway = pilotAuth,
                pilotWsDelegate = wsDelegate,
                simulatorTransport = SimulatorRobotTransport(dispatcherProvider = dispatchers),
                authClock = authClock,
                dispatchers = dispatchers,
                scope = backgroundScope
            )
            val ctx = PilotAuthorizationContext(
                authorizationId = "FCT004-PILOT-20261125-03",
                candidateIdentity = runtimeId,
                accountScope = PilotAccountScope(PilotAccountScopeType.DEDICATED_ISOLATED_TEST_ACCOUNT, "+1", "pilot***@example.invalid"),
                targetDevice = PilotTargetDevice("LaserSlam", "laser_01", "LASER_01"),
                permittedOperations = setOf(PilotOperation.LOGIN, PilotOperation.USER_THING_LIST_GET, PilotOperation.WS_TELEMETRY_OBSERVE),
                endpointConfig = PilotEndpointConfig(originHttp, pinnedApiOrigin = "https://127.0.0.1:${server.port}", pinnedWssOrigin = "wss://127.0.0.1:${server.port}"),
                readOnlyMode = true,
                physicalRobotCommandsPermitted = false,
                budgets = PilotRequestBudgets(),
                authorizedAtElapsedMs = 5_000L,
                observationDeadlineElapsedMs = 1_805_000L,
                externalApproval = PilotExternalApproval("PO", "Lead", "REF-3")
            )
            controller.activatePilot(ctx)

            // 1. Login succeeds once; second login attempt is blocked by budget (1 login max)
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-b","expired_time":3600}}"""))
            controller.submitCredentials(AuthCredentials("+1", "pilot@example.invalid", "pw".toCharArray()))
            val reqBeforeSecondLogin = server.requestCount
            controller.submitCredentials(AuthCredentials("+1", "pilot@example.invalid", "pw".toCharArray()))
            assertEquals("Second login attempt must be blocked by budget", reqBeforeSecondLogin, server.requestCount)
            assertEquals(PilotAbortReason.BudgetExhausted, controller.lastAbortReason.value)
            assertEquals(com.weback.robot.domain.model.SessionState.SignedOut, coordinator.sessionState.value)

            // Re-activate fresh pilot for inventory + WS cumulative reconnect budget check
            controller.activatePilot(ctx)
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-b2","expired_time":3600}}"""))
            controller.submitCredentials(AuthCredentials("+1", "pilot@example.invalid", "pw".toCharArray()))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"thing_list":[{"thing_name":"laser_01","sub_type":"xs-x8-eu","thing_nickname":"LivingLaser","is_admin":true}]}}"""))
            assertTrue(controller.refreshReadOnlyInventory())
            // Second inventory attempt must fail budget (1 inventory request max)
            assertFalse(controller.refreshReadOnlyInventory())

            // Re-activate for cumulative WS reconnect test (1 initial + max 3 reconnects, NOT reset after successful reconnect!)
            controller.activatePilot(ctx)
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-ws3","expired_time":3600}}"""))
            controller.submitCredentials(AuthCredentials("+1", "pilot@example.invalid", "pw".toCharArray()))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"thing_list":[{"thing_name":"laser_01","sub_type":"xs-x8-eu","thing_nickname":"LivingLaser","is_admin":true}]}}"""))
            assertTrue(controller.refreshReadOnlyInventory())

            // Initial WS fails 500 -> Reconnect #1 SUCCEEDS (opens!), then drops -> Reconnect #2 fails 500 -> Reconnect #3 fails 500 -> Reconnect #4 MUST NOT HAPPEN!
            server.enqueue(MockResponse().setResponseCode(500)) // initial attempt fails
            val reconnect1Open = CountDownLatch(1)
            server.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sockets.add(webSocket)
                        reconnect1Open.countDown()
                    }
                })
            )
            server.enqueue(MockResponse().setResponseCode(500)) // reconnect #2 fails
            server.enqueue(MockResponse().setResponseCode(500)) // reconnect #3 fails
            server.enqueue(MockResponse().setResponseCode(500)) // would be #4 if budget mistakenly reset!

            val reqBeforeWs = server.requestCount
            assertTrue(controller.selectAndObserveDevice("laser_01"))

            // Wait for initial 500 failure -> Reconnecting(attempt=1)
            val d1 = System.currentTimeMillis() + 2_000L
            while (wsDelegate.connectionState.value !is ConnectionState.Reconnecting && System.currentTimeMillis() < d1) {
                Thread.sleep(10)
            }
            assertEquals(1, (wsDelegate.connectionState.value as ConnectionState.Reconnecting).attempt)

            // Advance 4s -> Reconnect #1 opens successfully!
            advanceTimeBy(4_000L)
            runCurrent()
            assertTrue("Expected reconnect #1 to open", reconnect1Open.await(3, TimeUnit.SECONDS))
            val d2 = System.currentTimeMillis() + 2_000L
            while (wsDelegate.connectionState.value !is ConnectionState.Connected && System.currentTimeMillis() < d2) {
                Thread.sleep(10)
            }

            // Simulate abrupt TCP transport drop after successful reconnect #1
            clientSockets.last().close()
            val d3 = System.currentTimeMillis() + 2_000L
            while (wsDelegate.connectionState.value !is ConnectionState.Reconnecting && System.currentTimeMillis() < d3) {
                Thread.sleep(10)
            }
            // Attempt MUST be 2 (cumulative, NOT reset to 1!)
            assertEquals(2, (wsDelegate.connectionState.value as ConnectionState.Reconnecting).attempt)

            // Advance 4s -> Reconnect #2 fails 500 -> Reconnecting(attempt=3)
            advanceTimeBy(4_000L)
            runCurrent()
            val d4 = System.currentTimeMillis() + 2_000L
            while ((wsDelegate.connectionState.value as? ConnectionState.Reconnecting)?.attempt != 3 && System.currentTimeMillis() < d4) {
                Thread.sleep(10)
            }

            // Advance 4s -> Reconnect #3 fails 500 -> MUST stop (Disconnected), total WS HTTP upgrades = 4 (1 initial + 3 reconnects)
            advanceTimeBy(4_000L)
            runCurrent()
            val d5 = System.currentTimeMillis() + 2_000L
            while (wsDelegate.connectionState.value !is ConnectionState.Disconnected && System.currentTimeMillis() < d5) {
                Thread.sleep(10)
            }
            assertEquals(4, server.requestCount - reqBeforeWs)
            wsDelegate.close()
        } finally {
            sockets.forEach { runCatching { it.close(1000, "teardown") } }
            server.shutdown()
        }
    }
}
