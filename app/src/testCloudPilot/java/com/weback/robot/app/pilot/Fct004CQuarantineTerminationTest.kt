package com.weback.robot.app.pilot

import com.weback.robot.app.session.DeviceInventoryRepository
import com.weback.robot.app.session.SessionCoordinator
import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.BootSessionTimeProvider
import com.weback.robot.core.BootTimeSnapshot
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.domain.model.SessionState
import com.weback.robot.domain.pilot.ExpectedCandidateIdentity
import com.weback.robot.domain.pilot.GateActivationResult
import com.weback.robot.domain.pilot.PilotAbortReason
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class Fct004CQuarantineTerminationTest {

    @Test
    fun unknownDynamicOriginRetainsOnlySanitizedSummaryDiscardsTokenRevokesAuthAndBlocksFollowUpRequests() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val nowMs = 10_000L
            val authClock = AuthMonotonicClock { nowMs }
            val dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
            val originHttp = "http://127.0.0.1:${server.port}"
            val loopbackPolicy = TestLoopbackTrustPolicy(
                setOf(UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port))
            )
            val pilotTrust = AuthorizedPilotEndpointTrustPolicy(
                endpointConfig = PilotEndpointConfig(
                    approvedAuthOrigin = originHttp,
                    approvedAuthPath = "/prod/oauth",
                    pinnedApiOrigin = null,
                    pinnedWssOrigin = null
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
            var tokenSaveCount = 0
            val tokenStore = object : TokenStore {
                override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
                    tokenSaveCount++
                    return TokenSaveResult.Success
                }
                override suspend fun load() = TokenLoadResult.Empty
                override suspend fun clear() = TokenClearResult.Success
            }
            val timeProvider = BootSessionTimeProvider {
                BootTimeSnapshot("boot-q", nowMs, 1_700_000_000_000L)
            }
            val coordinator = SessionCoordinator(pilotAuth, tokenStore, timeProvider)
            val invRepo = DeviceInventoryRepository(coordinator, pilotInv)
            val wsDelegate = OkHttpWebSocketTransport(loopbackPolicy, authClock, dispatchers, ConnectionConfig(), client = pilotClient)
            val runtimeId = ExpectedCandidateIdentity(
                candidateCommitSha = "bc7f6d6589b4962c092574d998f6dfce532b17c7",
                applicationId = "com.weback.robot.pilot",
                versionName = "0.1.0-alpha",
                apkSha256 = "f".repeat(64)
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
                activeCallRegistry = activeCalls,
                scope = backgroundScope
            )

            val ctx = PilotAuthorizationContext(
                authorizationId = "FCT004C-QUAR-01",
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
                    pinnedApiOrigin = null,
                    pinnedWssOrigin = null
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
                externalApproval = PilotExternalApproval("PO-Human", "Lead-Human", "EXT-QUAR-01")
            )
            assertTrue(controller.activatePilot(ctx) is GateActivationResult.Activated)

            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"code":1,"data":{"api_url":"https://unknown-api.example.invalid/api","wss_url":"wss://unknown-wss.example.invalid/ws","region_name":"us","jwt_token":"secret-quarantined-jwt","expired_time":3600}}"""
                )
            )
            val authRes = controller.submitCredentials(
                AuthCredentials("+1", "pilot@test.invalid", "SecretPass1!".toCharArray())
            )
            assertTrue(authRes is AuthResult.Failure)

            // SEC-462: Conservative quarantine termination
            assertFalse("Untrusted dynamic origin must revoke gate authorization", gate.isAuthorized)
            assertEquals(PilotModeState.OfflineDefault, controller.pilotModeState.value)
            assertEquals(PilotAbortReason.UntrustedOriginRejected, controller.lastAbortReason.value)
            assertFalse(coordinator.sessionState.value is SessionState.Authenticated)
            assertEquals(0, tokenSaveCount)
            assertEquals(1, server.requestCount)

            val obs = controller.lastObservedOrigins.value
            assertNotNull("Sanitized endpoint observation must be retained", obs)
            assertEquals("https://unknown-api.example.invalid:443", obs!!.apiOrigin)
            assertEquals("wss://unknown-wss.example.invalid:443", obs.wssOrigin)
            assertFalse(obs.provenanceVerified)

            // No automatic retry, no inventory request allowed, cannot repeat login without new auth
            assertFalse(controller.refreshReadOnlyInventory())
            val retryLogin = controller.submitCredentials(
                AuthCredentials("+1", "pilot@test.invalid", "SecretPass1!".toCharArray())
            )
            assertTrue(retryLogin is AuthResult.Failure)
            assertEquals("Subsequent requests must be blocked with 0 extra network calls", 1, server.requestCount)
            wsDelegate.close()
        } finally {
            server.shutdown()
        }
    }
}
