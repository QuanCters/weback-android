package com.weback.robot.app.pilot

import com.weback.robot.app.session.DeviceInventoryRepository
import com.weback.robot.app.session.SessionCoordinator
import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.BootSessionTimeProvider
import com.weback.robot.core.BootTimeSnapshot
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.domain.pilot.ExpectedCandidateIdentity
import com.weback.robot.domain.pilot.GateActivationResult
import com.weback.robot.domain.pilot.PilotAccountScope
import com.weback.robot.domain.pilot.PilotAccountScopeType
import com.weback.robot.domain.pilot.PilotAuthorizationContext
import com.weback.robot.domain.pilot.PilotAuthorizationGate
import com.weback.robot.domain.pilot.PilotClock
import com.weback.robot.domain.pilot.PilotEndpointConfig
import com.weback.robot.domain.pilot.PilotExternalApproval
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class Fct004CProtocolEnvelopeTest {

    private val runtimeId = ExpectedCandidateIdentity(
        candidateCommitSha = "bc7f6d6589b4962c092574d998f6dfce532b17c7",
        applicationId = "com.weback.robot.pilot",
        versionName = "0.1.0-alpha",
        apkSha256 = "e".repeat(64)
    )

    private fun noOpTokenStore() = object : TokenStore {
        override suspend fun save(record: PersistedSessionRecord) = TokenSaveResult.Success
        override suspend fun load() = TokenLoadResult.Empty
        override suspend fun clear() = TokenClearResult.Success
    }

    @Test
    fun capturesEvidenceGroundedLoginEnvelopeAndEnforcesSingleAttemptZeroRetryZeroMismatchRequests() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val wsDelegate: OkHttpWebSocketTransport? = null
        try {
            val nowMs = 5_000L
            val authClock = AuthMonotonicClock { nowMs }
            val dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
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
            val router = RegionEndpointRouter(
                cnOverrideUrl = "$originHttp/prod/oauth",
                globalOverrideUrl = "$originHttp/prod/oauth"
            )
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
            val timeProvider = BootSessionTimeProvider {
                BootTimeSnapshot("boot-c", nowMs, 1_700_000_000_000L)
            }
            val coordinator = SessionCoordinator(pilotAuth, noOpTokenStore(), timeProvider)
            val invRepo = DeviceInventoryRepository(coordinator, pilotInv)
            val ws = OkHttpWebSocketTransport(loopbackPolicy, authClock, dispatchers, ConnectionConfig(), client = pilotClient)
            val controller = PilotSessionController(
                gate = gate,
                expectedRuntimeIdentity = runtimeId,
                trustPolicy = pilotTrust,
                sessionCoordinator = coordinator,
                inventoryRepository = invRepo,
                pilotAuthGateway = pilotAuth,
                pilotWsDelegate = ws,
                simulatorTransport = SimulatorRobotTransport(dispatcherProvider = dispatchers),
                authClock = authClock,
                dispatchers = dispatchers,
                activeCallRegistry = activeCalls,
                scope = backgroundScope
            )

            val ctx = PilotAuthorizationContext(
                authorizationId = "FCT004C-ENV-01",
                candidateIdentity = runtimeId,
                accountScope = PilotAccountScope(
                    PilotAccountScopeType.DEDICATED_ISOLATED_TEST_ACCOUNT,
                    "+1",
                    "p***@test.invalid",
                    "pilot@test.invalid"
                ),
                targetDevice = PilotTargetDevice("LaserSlam", "laser_c1", "LASER_C1"),
                permittedOperations = setOf(PilotOperation.LOGIN, PilotOperation.USER_THING_LIST_GET),
                endpointConfig = PilotEndpointConfig(
                    approvedAuthOrigin = originHttp,
                    approvedAuthPath = "/prod/oauth",
                    pinnedApiOrigin = "https://127.0.0.1:${server.port}",
                    pinnedWssOrigin = "wss://127.0.0.1:${server.port}"
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
                externalApproval = PilotExternalApproval("PO-Human", "Lead-Human", "EXT-ENV-01")
            )

            assertTrue(controller.activatePilot(ctx) is GateActivationResult.Activated)

            // 1. Incorrect account or calling code produces ZERO network requests
            val wrongAcct = controller.submitCredentials(
                AuthCredentials("+1", "wrong@test.invalid", "Secret123!".toCharArray())
            )
            assertTrue(wrongAcct is AuthResult.Failure)
            assertEquals("Wrong account must make 0 HTTP requests", 0, server.requestCount)

            val wrongRegion = controller.submitCredentials(
                AuthCredentials("+86", "pilot@test.invalid", "Secret123!".toCharArray())
            )
            assertTrue(wrongRegion is AuthResult.Failure)
            assertEquals("Wrong region calling code must make 0 HTTP requests", 0, server.requestCount)

            // 2. New run: valid login captures exact 6.1.7 RE-grounded envelope and does not retry on failure
            val ctx2 = ctx.copy(authorizationId = "FCT004C-ENV-02")
            assertTrue(controller.activatePilot(ctx2) is GateActivationResult.Activated)
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":500}"""))

            val failRes = controller.submitCredentials(
                AuthCredentials("+1", "pilot@test.invalid", "Utf8Password!123".toCharArray())
            )
            assertTrue(failRes is AuthResult.Failure)
            assertEquals("Failed login must make exactly 1 attempt with 0 automatic retries", 1, server.requestCount)
            assertFalse("Failed login must revoke authorization", gate.isAuthorized)
            // Also attempting another login immediately with exhausted/revoked run makes 0 requests
            assertTrue(
                controller.submitCredentials(
                    AuthCredentials("+1", "pilot@test.invalid", "Utf8Password!123".toCharArray())
                ) is AuthResult.Failure
            )
            assertEquals(1, server.requestCount)

            val recorded = server.takeRequest()
            assertEquals("/prod/oauth", recorded.path)
            val parsed = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            val header = parsed["header"]!!.jsonObject
            val payload = parsed["payload"]!!.jsonObject
            assertEquals("6.1.7", header["api_version"]!!.jsonPrimitive.content)
            assertEquals("WeBack", header["app_name"]!!.jsonPrimitive.content)
            assertEquals("+1", header["calling_code"]!!.jsonPrimitive.content)
            assertEquals("pilot@test.invalid", header["account"]!!.jsonPrimitive.content)
            assertEquals("yugong_app", header["client_id"]!!.jsonPrimitive.content)
            assertEquals("en", header["language"]!!.jsonPrimitive.content)
            assertEquals("login", payload["opt"]!!.jsonPrimitive.content)
            assertEquals("Utf8Password!123", payload["pwd"]!!.jsonPrimitive.content)

            // 3. Challenge response produces 0 retries and 0 CAPTCHA automation
            val ctx3 = ctx.copy(authorizationId = "FCT004C-ENV-03")
            assertTrue(controller.activatePilot(ctx3) is GateActivationResult.Activated)
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":4001,"captcha":true}"""))
            val chalRes = controller.submitCredentials(
                AuthCredentials("+1", "pilot@test.invalid", "Utf8Password!123".toCharArray())
            )
            assertTrue(chalRes is AuthResult.Failure)
            assertEquals(AuthFailureReason.ChallengeRequired, (chalRes as AuthResult.Failure).reason)
            assertEquals(2, server.requestCount)

            // 4. After successful login, a second login attempt in the same authorization run makes 0 requests
            val ctx4 = ctx.copy(authorizationId = "FCT004C-ENV-04")
            assertTrue(controller.activatePilot(ctx4) is GateActivationResult.Activated)
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-once","expired_time":3600}}"""
                )
            )
            assertTrue(
                controller.submitCredentials(
                    AuthCredentials("+1", "pilot@test.invalid", "Utf8Password!123".toCharArray())
                ) is AuthResult.Success
            )
            assertEquals(3, server.requestCount)
            assertTrue(
                controller.submitCredentials(
                    AuthCredentials("+1", "pilot@test.invalid", "Utf8Password!123".toCharArray())
                ) is AuthResult.Failure
            )
            assertEquals("Second login in same run must produce 0 extra HTTP requests", 3, server.requestCount)
            ws.close()
        } finally {
            wsDelegate?.close()
            server.shutdown()
        }
    }
}
