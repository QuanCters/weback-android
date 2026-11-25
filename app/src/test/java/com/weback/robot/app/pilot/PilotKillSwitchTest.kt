package com.weback.robot.app.pilot

import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.BootSessionTimeProvider
import com.weback.robot.core.BootTimeSnapshot
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.model.InventoryState
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
import com.weback.robot.security.TestLoopbackTrustPolicy
import com.weback.robot.security.TokenClearResult
import com.weback.robot.security.TokenLoadResult
import com.weback.robot.security.TokenSaveResult
import com.weback.robot.security.TokenStore
import com.weback.robot.security.UriValidator
import com.weback.robot.app.session.DeviceInventoryRepository
import com.weback.robot.app.session.SessionCoordinator
import com.weback.robot.testutils.TestDispatcherProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class PilotKillSwitchTest {

    @Test
    fun testEmergencyDisconnectRevokesNetworkBeforeSlowDiskAndReportsIncompleteStorageHonestly() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val openLatch = CountDownLatch(1)
        var serverWs: WebSocket? = null
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

            val clearGate = CompletableDeferred<Unit>()
            var failClear = true
            val tokenStore = object : TokenStore {
                override suspend fun save(record: com.weback.robot.domain.model.PersistedSessionRecord) = TokenSaveResult.Success
                override suspend fun load() = TokenLoadResult.Empty
                override suspend fun clear(): TokenClearResult {
                    clearGate.await()
                    return if (failClear) TokenClearResult.Failure("DISK_DELETE_DENIED") else TokenClearResult.Success
                }
            }
            val pilotAuth = PilotCapabilityRestrictedAuthGateway(
                router, loopbackPolicy, authClock, dispatchers = dispatchers
            )
            val pilotInv = PilotCapabilityRestrictedInventoryGateway(
                OkHttpDeviceInventoryGateway(loopbackPolicy, authClock, dispatchers)
            )
            val coordinator = SessionCoordinator(pilotAuth, tokenStore, timeProvider)
            val invRepo = DeviceInventoryRepository(coordinator, pilotInv)
            val wsDelegate = OkHttpWebSocketTransport(loopbackPolicy, authClock, dispatchers, ConnectionConfig(), resetReconnectAttemptOnOpen = false)
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
                authorizationId = "FCT004-PILOT-20261125-04",
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
                externalApproval = PilotExternalApproval("PO", "Lead", "REF-4")
            )
            controller.activatePilot(ctx)
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-kill","expired_time":3600}}"""))
            controller.submitCredentials(AuthCredentials("+1", "pilot@example.invalid", "pw".toCharArray()))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"thing_list":[{"thing_name":"laser_01","sub_type":"xs-x8-eu","thing_nickname":"LivingLaser","is_admin":true}]}}"""))
            controller.refreshReadOnlyInventory()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverWs = webSocket
                    openLatch.countDown()
                }
            }))
            assertTrue(controller.selectAndObserveDevice("laser_01"))
            assertTrue(openLatch.await(5, TimeUnit.SECONDS))

            // Start Emergency Disconnect while disk clear() is blocked!
            val disconnectJob = async { controller.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect) }
            testScheduler.runCurrent()

            // BEFORE disk I/O finishes: gate, session, inventory, WS, and offline simulator MUST ALREADY be revoked/restored!
            assertFalse(gate.isAuthorized)
            assertEquals(SessionState.SignedOut, coordinator.sessionState.value)
            assertEquals(InventoryState.Idle, invRepo.inventoryState.value)
            assertNull(invRepo.selectedDevice.value)
            assertTrue(wsDelegate.connectionState.value is ConnectionState.Disconnected)
            assertEquals(PilotModeState.OfflineDefault, controller.pilotModeState.value)

            // Unblock slow failing disk clear() -> must report incomplete storage cleanup honestly and never claim server revocation
            clearGate.complete(Unit)
            val result = disconnectJob.await()
            assertTrue(result.networkRevokedImmediately)
            assertFalse(result.storageCleared)
            assertEquals("DISK_DELETE_DENIED", result.incompleteStorageReason)
            assertTrue(result.restoredOfflineSimulator)
            assertFalse(result.serverSideTokenRevocationClaimed)
            wsDelegate.close()
        } finally {
            runCatching { serverWs?.close(1000, "teardown") }
            server.shutdown()
        }
    }
}
