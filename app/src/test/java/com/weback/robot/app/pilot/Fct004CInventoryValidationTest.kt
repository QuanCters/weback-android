package com.weback.robot.app.pilot

import com.weback.robot.app.session.DeviceInventoryRepository
import com.weback.robot.app.session.SessionCoordinator
import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.BootSessionTimeProvider
import com.weback.robot.core.BootTimeSnapshot
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.ControlCompatibility
import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.domain.model.SessionState
import com.weback.robot.domain.pilot.ExpectedCandidateIdentity
import com.weback.robot.domain.pilot.GateActivationResult
import com.weback.robot.domain.pilot.PilotAccountScope
import com.weback.robot.domain.pilot.PilotAccountScopeType
import com.weback.robot.domain.pilot.PilotAuthorizationContext
import com.weback.robot.domain.pilot.PilotAuthorizationGate
import com.weback.robot.domain.pilot.PilotClock
import com.weback.robot.domain.pilot.PilotEndpointConfig
import com.weback.robot.domain.pilot.PilotExternalApproval
import com.weback.robot.domain.pilot.PilotModeState
import com.weback.robot.domain.pilot.PilotOperation
import com.weback.robot.domain.pilot.PilotRequestBudgets
import com.weback.robot.domain.pilot.PilotTargetDevice
import com.weback.robot.protocol.SimulatorRobotTransport
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
import com.weback.robot.testutils.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class Fct004CInventoryValidationTest {

    private val runtimeId = ExpectedCandidateIdentity(
        candidateCommitSha = "bc7f6d6589b4962c092574d998f6dfce532b17c7",
        applicationId = "com.weback.robot.pilot",
        versionName = "0.1.0-alpha",
        apkSha256 = "1".repeat(64)
    )

    private fun setupHarness(
        server: MockWebServer,
        nowMs: Long,
        scope: kotlinx.coroutines.CoroutineScope,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler
    ): Triple<PilotSessionController, PilotAuthorizationGate, SessionCoordinator> {
        val authClock = AuthMonotonicClock { nowMs }
        val dispatchers = TestDispatcherProvider(StandardTestDispatcher(scheduler))
        val originHttp = "http://127.0.0.1:${server.port}"
        val loopbackPolicy = TestLoopbackTrustPolicy(
            setOf(
                UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port),
                UriValidator.NormalizedOrigin("ws", "127.0.0.1", server.port)
            )
        )
        val pilotTrust = AuthorizedPilotEndpointTrustPolicy(
            endpointConfig = PilotEndpointConfig(
                approvedAuthOrigin = originHttp,
                approvedAuthPath = "/prod/oauth",
                pinnedApiOrigin = "https://127.0.0.1:${server.port}",
                pinnedWssOrigin = "wss://127.0.0.1:${server.port}"
            ),
            allowedAuthOrigins = setOf(originHttp),
            allowLoopbackForHermeticTests = true
        )
        val gate = PilotAuthorizationGate(
            allowedAuthOrigins = setOf(originHttp),
            clock = PilotClock { nowMs }
        )
        val activeCalls = PilotActiveCallRegistry()
        val pilotClient = PilotClientFactory.buildPilotOkHttpClient(
            trustPolicy = pilotTrust,
            activeCallRegistry = activeCalls,
            admissionGate = gate,
            authClock = authClock
        )
        val router = RegionEndpointRouter(globalOverrideUrl = "$originHttp/prod/oauth")
        val pilotAuth = PilotCapabilityRestrictedAuthGateway(
            router = router,
            trustPolicy = loopbackPolicy,
            authClock = authClock,
            dispatchers = dispatchers,
            baseClient = pilotClient,
            pilotEndpointTrustPolicy = pilotTrust,
            admissionGate = gate
        )
        val pilotInv = PilotCapabilityRestrictedInventoryGateway(
            OkHttpDeviceInventoryGateway(loopbackPolicy, authClock, dispatchers, client = pilotClient),
            admissionGate = gate,
            authClock = authClock,
            pilotEndpointTrustPolicy = pilotTrust
        )
        val tokenStore = object : TokenStore {
            override suspend fun save(record: PersistedSessionRecord) = TokenSaveResult.Success
            override suspend fun load() = TokenLoadResult.Empty
            override suspend fun clear() = TokenClearResult.Success
        }
        val coordinator = SessionCoordinator(
            pilotAuth,
            tokenStore,
            BootSessionTimeProvider { BootTimeSnapshot("boot-i", nowMs, 1_700_000_000_000L) }
        )
        val invRepo = DeviceInventoryRepository(coordinator, pilotInv)
        val wsDelegate = OkHttpWebSocketTransport(loopbackPolicy, authClock, dispatchers, ConnectionConfig(), client = pilotClient)
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
            scope = scope
        )
        return Triple(controller, gate, coordinator)
    }

    private fun strictContext(authId: String, port: Int, nowMs: Long) = PilotAuthorizationContext(
        authorizationId = authId,
        candidateIdentity = runtimeId,
        accountScope = PilotAccountScope(
            PilotAccountScopeType.DEDICATED_ISOLATED_TEST_ACCOUNT,
            "+1",
            "p***@test.invalid",
            "pilot@test.invalid"
        ),
        targetDevice = PilotTargetDevice("LaserSlam", "laser_target_01", "LASER_TARGET_01"),
        permittedOperations = setOf(PilotOperation.LOGIN, PilotOperation.USER_THING_LIST_GET),
        endpointConfig = PilotEndpointConfig(
            approvedAuthOrigin = "http://127.0.0.1:$port",
            approvedAuthPath = "/prod/oauth",
            pinnedApiOrigin = "https://127.0.0.1:$port",
            pinnedWssOrigin = "wss://127.0.0.1:$port"
        ),
        readOnlyMode = true,
        physicalRobotCommandsPermitted = false,
        budgets = PilotRequestBudgets(
            maxInitialWsConnections = 0,
            maxWsReconnectAttempts = 0,
            observationWindowMs = 900_000L
        ),
        authorizedAtElapsedMs = nowMs,
        observationDeadlineElapsedMs = nowMs + 900_000L,
        externalApproval = PilotExternalApproval("PO-Human", "Lead-Human", "EXT-$authId")
    )

    @Test
    fun preApprovedOriginPermitsOneTargetInventoryQueryWithZeroWsAndExposesTargetOnly() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val nowMs = 10_000L
        val (controller, gate, _) = setupHarness(server, nowMs, backgroundScope, testScheduler)
        try {
            val originHttp = "http://127.0.0.1:${server.port}"
            assertTrue(controller.activatePilot(strictContext("FCT004C-INV-OK", server.port, nowMs)) is GateActivationResult.Activated)

            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-inv-ok","expired_time":3600}}"""
                )
            )
            val loginRes = controller.submitCredentials(AuthCredentials("+1", "pilot@test.invalid", "Secret1!".toCharArray()))
            assertTrue(loginRes is AuthResult.Success)
            assertTrue(controller.pilotModeState.value is PilotModeState.AuthenticatedInventoryReady)

            // Inventory contains unsupported devices + unauthorized laser device + authorized target
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"code":1,"data":{"thing_list":[
                        {"thing_name":"gyro_other_secret","sub_type":"yw-vs01","thing_nickname":"GyroOther","is_admin":false},
                        {"thing_name":"laser_other_secret","sub_type":"xs-x8-eu","thing_nickname":"OtherLaser","is_admin":true},
                        {"thing_name":"laser_target_01","sub_type":"xs-x8-eu","thing_nickname":"Living Laser","is_admin":true,"thing_status":{"battery_level":91,"working_status":"charging","error_info":0}}
                    ]}}"""
                )
            )
            val targetSummary = controller.fetchAndValidateTargetInventory()
            assertNotNull("Authorized target summary must be returned", targetSummary)
            assertEquals("LASER_TARGET_01", targetSummary!!.thingNameRedacted)
            assertEquals("Living Laser", targetSummary.nickname)
            assertEquals("xs-x8-eu-", targetSummary.normalizedSubType)
            assertEquals(ControlCompatibility.MvpSupportedShadowWs, targetSummary.compatibility)
            assertTrue(controller.pilotModeState.value is PilotModeState.InventoryVerified)

            // Strictly 2 HTTP calls total (1 login + 1 inventory), 0 WS connections, 0 physical robot commands
            assertEquals(2, server.requestCount)
            // Second inventory attempt BEFORE any abort must fail due to inventory budget exhaustion (0 extra requests)
            assertNull("Second inventory attempt must be rejected with 0 extra requests", controller.fetchAndValidateTargetInventory())
            assertEquals(2, server.requestCount)
            assertFalse(gate.isAuthorized)

            // Re-activate fresh run and verify selectAndObserveDevice MUST fail closed in FCT-004C with 0 WS upgrades
            assertTrue(controller.activatePilot(strictContext("FCT004C-INV-NO-WS", server.port, nowMs)) is GateActivationResult.Activated)
            assertFalse("selectAndObserveDevice MUST fail closed in FCT-004C", controller.selectAndObserveDevice("laser_target_01"))
            testScheduler.runCurrent()
            assertEquals(2, server.requestCount)
            assertFalse(gate.isAuthorized)

            // Re-activate fresh run and verify physical robot command is rejected and revokes authorization with 0 frames
            assertTrue(controller.activatePilot(strictContext("FCT004C-INV-NO-CMD", server.port, nowMs)) is GateActivationResult.Activated)
            assertTrue(
                "Physical robot commands must be rejected in FCT-004C",
                controller.dispatchPhysicalCommandInPilot(com.weback.robot.domain.model.RobotCommand.CLEAN)
                    is com.weback.robot.domain.model.CommandResult.Rejected
            )
            testScheduler.runCurrent()
            assertEquals(2, server.requestCount)
            assertFalse(gate.isAuthorized)
        } finally {
            controller.close()
            server.shutdown()
        }
    }

    @Test
    fun missingMismatchedUnsupportedOrMalformedTargetTerminatesRunSafely() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val nowMs = 10_000L
        val (controller, gate, coordinator) = setupHarness(server, nowMs, backgroundScope, testScheduler)
        try {
            val originHttp = "http://127.0.0.1:${server.port}"
            val badInventories = listOf(
                // Missing target (empty)
                """{"code":1,"data":{"thing_list":[]}}""",
                // Mismatched thing_name
                """{"code":1,"data":{"thing_list":[{"thing_name":"wrong_laser","sub_type":"xs-x8-eu","thing_nickname":"Wrong","is_admin":true}]}}""",
                // Unsupported capability (visual/gyro instead of LaserSlam)
                """{"code":1,"data":{"thing_list":[{"thing_name":"laser_target_01","sub_type":"yw-vs01","thing_nickname":"Gyro","is_admin":true}]}}""",
                // Malformed payload
                """{"code":1,"data":{"thing_list":"invalid"}}"""
            )
            badInventories.forEachIndexed { idx, invBody ->
                assertTrue(
                    controller.activatePilot(strictContext("FCT004C-INV-BAD-$idx", server.port, nowMs)) is GateActivationResult.Activated
                )
                server.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        """{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-bad-$idx","expired_time":3600}}"""
                    )
                )
                assertTrue(
                    controller.submitCredentials(AuthCredentials("+1", "pilot@test.invalid", "Secret1!".toCharArray())) is AuthResult.Success
                )
                server.enqueue(MockResponse().setResponseCode(200).setBody(invBody))
                val summary = controller.fetchAndValidateTargetInventory()
                assertNull("Invalid inventory case $idx must return null", summary)
                assertFalse("Invalid inventory case $idx must revoke authorization", gate.isAuthorized)
                assertFalse("Invalid inventory case $idx must sign out session", coordinator.sessionState.value is SessionState.Authenticated)
                assertEquals(PilotModeState.OfflineDefault, controller.pilotModeState.value)
            }
        } finally {
            controller.close()
            server.shutdown()
        }
    }
}
