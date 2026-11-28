package com.weback.robot.app.pilot

import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.BootSessionTimeProvider
import com.weback.robot.core.BootTimeSnapshot
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.model.SessionState
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
import com.weback.robot.security.PilotActiveCallRegistry
import com.weback.robot.security.PilotClientFactory
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
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
class PilotEmergencyCancellationTest {

    @Test
    fun testCancelsDuringLoginInventoryOriginQuarantineHandshakeReconnectDelayAndBlocksLateCallbacks() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val releaseLogin = CountDownLatch(1)
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
            val activeCalls = PilotActiveCallRegistry()
            val router = RegionEndpointRouter(globalOverrideUrl = "$originHttp/prod/oauth")
            val tokenStore = object : TokenStore {
                override suspend fun save(record: com.weback.robot.domain.model.PersistedSessionRecord) = TokenSaveResult.Success
                override suspend fun load() = TokenLoadResult.Empty
                override suspend fun clear() = TokenClearResult.Success
            }
            val pilotTrust = AuthorizedPilotEndpointTrustPolicy(
                PilotEndpointConfig(originHttp, pinnedApiOrigin = "https://127.0.0.1:${server.port}", pinnedWssOrigin = "wss://127.0.0.1:${server.port}"),
                allowedAuthOrigins = setOf(originHttp),
                allowLoopbackForHermeticTests = true
            )
            val pilotClient = PilotClientFactory.buildPilotOkHttpClient(
                trustPolicy = pilotTrust,
                activeCallRegistry = activeCalls
            )
            val ioDispatchers = object : com.weback.robot.core.DispatcherProvider by dispatchers {
                override val io = kotlinx.coroutines.Dispatchers.IO
            }
            val pilotAuth = PilotCapabilityRestrictedAuthGateway(
                router, loopbackPolicy, authClock, dispatchers = ioDispatchers, baseClient = pilotClient, pilotEndpointTrustPolicy = pilotTrust
            )
            val pilotInv = PilotCapabilityRestrictedInventoryGateway(
                OkHttpDeviceInventoryGateway(loopbackPolicy, authClock, dispatchers, client = pilotClient)
            )
            val coordinator = SessionCoordinator(pilotAuth, tokenStore, timeProvider)
            val invRepo = DeviceInventoryRepository(coordinator, pilotInv)
            val wsDelegate = OkHttpWebSocketTransport(
                trustPolicy = loopbackPolicy,
                authClock = authClock,
                dispatchers = dispatchers,
                connectionConfig = ConnectionConfig(reconnectDelayMs = 4_000L, maxReconnectAttempts = 3),
                resetReconnectAttemptOnOpen = false
            )
            val gate = PilotAuthorizationGate(allowedAuthOrigins = setOf(originHttp))
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
                activeCallRegistry = activeCalls,
                scope = backgroundScope
            )
            val ctx = PilotAuthorizationContext(
                authorizationId = "FCT004-PILOT-20261125-05",
                candidateIdentity = runtimeId,
                accountScope = PilotAccountScope(
                    PilotAccountScopeType.DEDICATED_ISOLATED_TEST_ACCOUNT,
                    "+1",
                    "pilot***@example.invalid",
                    "pilot@example.invalid"
                ),
                targetDevice = PilotTargetDevice("LaserSlam", "laser_01", "LASER_01"),
                permittedOperations = setOf(PilotOperation.LOGIN, PilotOperation.USER_THING_LIST_GET, PilotOperation.WS_TELEMETRY_OBSERVE),
                endpointConfig = PilotEndpointConfig(originHttp, pinnedApiOrigin = "https://127.0.0.1:${server.port}", pinnedWssOrigin = "wss://127.0.0.1:${server.port}"),
                readOnlyMode = true,
                physicalRobotCommandsPermitted = false,
                budgets = PilotRequestBudgets(),
                authorizedAtElapsedMs = 5_000L,
                observationDeadlineElapsedMs = 1_805_000L,
                externalApproval = PilotExternalApproval("PO", "Lead", "REF-5")
            )

            // 1. Cancel during delayed login HTTP call (latch-gated dispatcher avoids 5s MockWebServer shutdown stall)
            controller.activatePilot(ctx)
            val defaultDispatcher = server.dispatcher
            val loginArrived = CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    loginArrived.countDown()
                    releaseLogin.await(5, TimeUnit.SECONDS)
                    return MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-late","expired_time":3600}}""")
                }
            }
            val loginJob = async(kotlinx.coroutines.Dispatchers.IO) {
                controller.submitCredentials(AuthCredentials("+1", "pilot@example.invalid", "pw".toCharArray()))
            }
            assertTrue("In-flight login must reach MockWebServer before emergency disconnect", loginArrived.await(2, TimeUnit.SECONDS))
            controller.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect)
            val cancelledLogin = loginJob.await()
            assertTrue("Cancelled in-flight login must fail closed", cancelledLogin is com.weback.robot.domain.model.AuthResult.Failure)
            releaseLogin.countDown()
            server.dispatcher = defaultDispatcher
            assertFalse(gate.isAuthorized)
            assertFalse(coordinator.sessionState.value is SessionState.Authenticated)
            assertEquals(PilotModeState.OfflineDefault, controller.pilotModeState.value)

            // 2. Cancel during AwaitingOriginConfirmation quarantine
            controller.activatePilot(
                ctx.copy(
                    authorizationId = "FCT004-PILOT-20261125-05B",
                    endpointConfig = PilotEndpointConfig(originHttp, pinnedApiOrigin = null, pinnedWssOrigin = null)
                )
            )
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"code":1,"data":{"api_url":"https://dyn.example.invalid/api","wss_url":"wss://dyn.example.invalid/ws","region_name":"us","jwt_token":"jwt-q","expired_time":3600}}""")
            )
            controller.submitCredentials(AuthCredentials("+1", "pilot@example.invalid", "pw".toCharArray()))
            assertTrue(controller.pilotModeState.value is PilotModeState.AwaitingOriginConfirmation)
            controller.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect)
            assertEquals(PilotModeState.OfflineDefault, controller.pilotModeState.value)
            assertFalse(gate.isAuthorized)

            // 3. Cancel during WS reconnect delay -> late timer advance must NOT reconnect!
            controller.activatePilot(ctx.copy(authorizationId = "FCT004-PILOT-20261125-05C"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-ws4","expired_time":3600}}"""))
            controller.submitCredentials(AuthCredentials("+1", "pilot@example.invalid", "pw".toCharArray()))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"thing_list":[{"thing_name":"laser_01","sub_type":"xs-x8-eu","thing_nickname":"LivingLaser","is_admin":true}]}}"""))
            assertTrue(controller.refreshReadOnlyInventory())

            server.enqueue(MockResponse().setResponseCode(500)) // triggers reconnect delay
            server.enqueue(MockResponse().setResponseCode(200)) // must NEVER be reached after emergency disconnect!
            val reqBeforeWs = server.requestCount
            controller.selectAndObserveDevice("laser_01")
            val d1 = System.currentTimeMillis() + 2_000L
            while (wsDelegate.connectionState.value !is ConnectionState.Reconnecting && System.currentTimeMillis() < d1) {
                Thread.sleep(10)
            }
            controller.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect)
            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(1, server.requestCount - reqBeforeWs)
            assertFalse(gate.isAuthorized)
            assertEquals(PilotModeState.OfflineDefault, controller.pilotModeState.value)

            // Sanitized export AFTER disconnect retains authorizationId and scrubs target thingName & account alias
            val reportAfterDisconnect = controller.exportSanitizedReport(
                listOf("event thing_name=laser_01 account=pilot***@example.invalid")
            )
            assertTrue(reportAfterDisconnect.contains("FCT004-PILOT-20261125-05"))
            assertFalse(reportAfterDisconnect.contains("laser_01"))
            assertFalse(reportAfterDisconnect.contains("pilot***@example.invalid"))
            wsDelegate.close()
        } finally {
            releaseLogin.countDown()
            server.shutdown()
        }
    }
}
