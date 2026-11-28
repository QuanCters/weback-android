package com.weback.robot.app.pilot

import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.BootSessionTimeProvider
import com.weback.robot.core.BootTimeSnapshot
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthFailureReason
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
import com.weback.robot.protocol.pilot.PilotReadOnlyWebSocketTransport
import com.weback.robot.protocol.ws.ConnectionConfig
import com.weback.robot.protocol.ws.OkHttpWebSocketTransport
import com.weback.robot.security.AuthorizedPilotEndpointTrustPolicy
import com.weback.robot.security.InMemoryDiagnosticLogger
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
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class PilotInventoryCapabilityTest {

    @Test
    fun testQuarantinesUnpinnedOriginsAndFiltersNonLaserOrMismatchedTargetDevice() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
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
            val logs = mutableListOf<String>()
            val logger = InMemoryDiagnosticLogger(logs)

            var storedTokenCount = 0
            val tokenStore = object : TokenStore {
                override suspend fun save(record: com.weback.robot.domain.model.PersistedSessionRecord): TokenSaveResult {
                    storedTokenCount++
                    return TokenSaveResult.Success
                }
                override suspend fun load() = TokenLoadResult.Empty
                override suspend fun clear() = TokenClearResult.Success
            }

            val pilotTrust = AuthorizedPilotEndpointTrustPolicy(
                endpointConfig = PilotEndpointConfig(approvedAuthOrigin = originHttp, approvedAuthPath = "/prod/oauth"),
                allowedAuthOrigins = setOf(originHttp),
                allowLoopbackForHermeticTests = true
            )
            val pilotAuth = PilotCapabilityRestrictedAuthGateway(router, loopbackPolicy, authClock, logger, dispatchers = dispatchers, pilotEndpointTrustPolicy = pilotTrust)
            val rawInv = OkHttpDeviceInventoryGateway(loopbackPolicy, authClock, dispatchers, logger)
            val pilotInv = PilotCapabilityRestrictedInventoryGateway(rawInv)
            val coordinator = SessionCoordinator(pilotAuth, tokenStore, timeProvider, logger = logger)
            val inventoryRepo = DeviceInventoryRepository(coordinator, pilotInv)
            val wsDelegate = OkHttpWebSocketTransport(loopbackPolicy, authClock, dispatchers, ConnectionConfig(), logger)
            val gate = PilotAuthorizationGate(allowedAuthOrigins = setOf(originHttp))

            val controller = PilotSessionController(
                gate = gate,
                expectedRuntimeIdentity = runtimeId,
                trustPolicy = pilotTrust,
                sessionCoordinator = coordinator,
                inventoryRepository = inventoryRepo,
                pilotAuthGateway = pilotAuth,
                pilotWsDelegate = wsDelegate,
                simulatorTransport = SimulatorRobotTransport(dispatcherProvider = dispatchers),
                authClock = authClock,
                dispatchers = dispatchers,
                logger = logger,
                scope = backgroundScope
            )

            // 1. Unpinned dynamic origin response from auth -> Quarantined in AwaitingOriginConfirmation
            val unpinnedCtx = PilotAuthorizationContext(
                authorizationId = "FCT004-PILOT-20261125-01",
                candidateIdentity = runtimeId,
                accountScope = PilotAccountScope(
                    PilotAccountScopeType.DEDICATED_ISOLATED_TEST_ACCOUNT,
                    "+1",
                    "pilot***@example.invalid",
                    "pilot@example.invalid"
                ),
                targetDevice = PilotTargetDevice("LaserSlam", "laser_target_01", "LASER_TARGET"),
                permittedOperations = setOf(PilotOperation.LOGIN, PilotOperation.USER_THING_LIST_GET, PilotOperation.WS_TELEMETRY_OBSERVE),
                endpointConfig = PilotEndpointConfig(approvedAuthOrigin = originHttp, pinnedApiOrigin = null, pinnedWssOrigin = null),
                readOnlyMode = true,
                physicalRobotCommandsPermitted = false,
                budgets = PilotRequestBudgets(),
                authorizedAtElapsedMs = 5_000L,
                observationDeadlineElapsedMs = 1_805_000L,
                externalApproval = PilotExternalApproval("PO", "Lead", "REF-1")
            )
            assertTrue(controller.activatePilot(unpinnedCtx) is com.weback.robot.domain.pilot.GateActivationResult.Activated)

            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"code":1,"data":{"api_url":"https://unpinned-api.example.invalid/api","wss_url":"wss://unpinned-wss.example.invalid/ws","region_name":"us","jwt_token":"secret-quarantined-jwt","expired_time":3600}}"""
                )
            )
            controller.submitCredentials(AuthCredentials("+1", "pilot@example.invalid", "pw".toCharArray()))

            // Must be quarantined: SessionCoordinator NOT Authenticated, TokenStore NOT written, zero inventory calls
            assertTrue(controller.pilotModeState.value is PilotModeState.AwaitingOriginConfirmation)
            assertFalse(coordinator.sessionState.value is SessionState.Authenticated)
            assertEquals(0, storedTokenCount)
            assertEquals(1, server.requestCount)

            // 2. Now re-activate with pinned origin matching MockWebServer and verify LaserSlam target check
            controller.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect)
            val pinnedCtx = unpinnedCtx.copy(
                authorizationId = "FCT004-PILOT-20261125-01B",
                endpointConfig = PilotEndpointConfig(
                    approvedAuthOrigin = originHttp,
                    pinnedApiOrigin = "https://127.0.0.1:${server.port}",
                    pinnedWssOrigin = "wss://127.0.0.1:${server.port}"
                )
            )
            assertTrue(controller.activatePilot(pinnedCtx) is com.weback.robot.domain.pilot.GateActivationResult.Activated)
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-ok","expired_time":3600}}"""
                )
            )
            controller.submitCredentials(AuthCredentials("+1", "pilot@example.invalid", "pw".toCharArray()))
            assertTrue(coordinator.sessionState.value is SessionState.Authenticated)

            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"code":1,"data":{"thing_list":[{"thing_name":"gyro_other","sub_type":"q1-gyro","thing_nickname":"Gyro","is_admin":true},{"thing_name":"laser_other","sub_type":"xs-x8-eu","thing_nickname":"OtherLaser","is_admin":true},{"thing_name":"laser_target_01","sub_type":"xs-x8-eu","thing_nickname":"TargetLaser","is_admin":true}]}}"""
                )
            )
            controller.refreshReadOnlyInventory()

            // Trying to observe gyro_other or mismatched laser_other fails; only laser_target_01 succeeds
            assertFalse(controller.selectAndObserveDevice("gyro_other"))
            assertFalse(controller.selectAndObserveDevice("laser_other"))
            assertTrue(controller.selectAndObserveDevice("laser_target_01"))
            wsDelegate.close()
        } finally {
            server.shutdown()
        }
    }
}
