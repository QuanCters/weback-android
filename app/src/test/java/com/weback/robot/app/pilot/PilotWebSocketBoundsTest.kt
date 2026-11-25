package com.weback.robot.app.pilot

import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.BootSessionTimeProvider
import com.weback.robot.core.BootTimeSnapshot
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.pilot.ExpectedCandidateIdentity
import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotAccountScope
import com.weback.robot.domain.pilot.PilotAccountScopeType
import com.weback.robot.domain.pilot.PilotAuthorizationContext
import com.weback.robot.domain.pilot.PilotAuthorizationGate
import com.weback.robot.domain.pilot.PilotEndpointConfig
import com.weback.robot.domain.pilot.PilotExternalApproval
import com.weback.robot.domain.pilot.PilotModeState
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class PilotWebSocketBoundsTest {

    @Test
    fun testReadOnlyWsHydrationAndAutomaticDisconnectOnMonotonicObservationDeadline() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val openLatch = CountDownLatch(1)
        var serverWs: WebSocket? = null
        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val dispatchers = TestDispatcherProvider(dispatcher)
            var nowMs = 10_000L
            val authClock = AuthMonotonicClock { nowMs }
            val timeProvider = BootSessionTimeProvider { BootTimeSnapshot("boot_1", nowMs, 1_700_000_000_000L) }
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
            val wsDelegate = OkHttpWebSocketTransport(
                trustPolicy = loopbackPolicy,
                authClock = authClock,
                dispatchers = dispatchers,
                connectionConfig = ConnectionConfig(maxReconnectAttempts = 3),
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
                authorizationId = "FCT004-PILOT-20261125-02",
                candidateIdentity = runtimeId,
                accountScope = PilotAccountScope(PilotAccountScopeType.DEDICATED_ISOLATED_TEST_ACCOUNT, "+1", "pilot***@example.invalid"),
                targetDevice = PilotTargetDevice("LaserSlam", "laser_01", "LASER_01"),
                permittedOperations = setOf(PilotOperation.LOGIN, PilotOperation.USER_THING_LIST_GET, PilotOperation.WS_TELEMETRY_OBSERVE),
                endpointConfig = PilotEndpointConfig(originHttp, pinnedApiOrigin = "https://127.0.0.1:${server.port}", pinnedWssOrigin = "wss://127.0.0.1:${server.port}"),
                readOnlyMode = true,
                physicalRobotCommandsPermitted = false,
                budgets = PilotRequestBudgets(observationWindowMs = 60_000L),
                authorizedAtElapsedMs = 10_000L,
                observationDeadlineElapsedMs = 70_000L,
                externalApproval = PilotExternalApproval("PO", "Lead", "REF-2")
            )
            controller.activatePilot(ctx)

            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-ws","expired_time":3600}}"""))
            controller.submitCredentials(AuthCredentials("+1", "pilot@example.invalid", "pw".toCharArray()))

            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"thing_list":[{"thing_name":"laser_01","sub_type":"xs-x8-eu","thing_nickname":"LivingLaser","is_admin":true}]}}"""))
            controller.refreshReadOnlyInventory()

            server.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverWs = webSocket
                        openLatch.countDown()
                        webSocket.send("""{"notify_info":"thing_status_update","thing_name":"laser_01","working_status":"AutoClean","battery_level":72}""")
                    }
                })
            )
            assertTrue(controller.selectAndObserveDevice("laser_01"))
            assertTrue(openLatch.await(5, TimeUnit.SECONDS))

            val waitUntil = System.currentTimeMillis() + 2_000L
            while (wsDelegate.robotState.value.batteryPercentage != 72 && System.currentTimeMillis() < waitUntil) {
                Thread.sleep(10)
            }
            assertEquals(72, wsDelegate.robotState.value.batteryPercentage)
            assertEquals(RobotMode.CLEAN, wsDelegate.robotState.value.mode)

            // Advance virtual monotonic clock past observationDeadlineElapsedMs -> auto Emergency Disconnect
            nowMs = 70_000L
            advanceTimeBy(60_000L)
            runCurrent()

            assertFalse(gate.isAuthorized)
            assertEquals(PilotModeState.OfflineDefault, controller.pilotModeState.value)
            assertEquals(PilotAbortReason.ObservationWindowExpired, controller.lastAbortReason.value)
            assertTrue(wsDelegate.connectionState.value is ConnectionState.Disconnected)
            wsDelegate.close()
        } finally {
            runCatching { serverWs?.close(1000, "teardown") }
            server.shutdown()
        }
    }
}
