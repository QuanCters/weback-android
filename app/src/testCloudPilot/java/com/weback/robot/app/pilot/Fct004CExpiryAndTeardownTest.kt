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
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class Fct004CExpiryAndTeardownTest {

    @Test
    fun enforces15MinMonotonicExpiryGenerationFencedTeardownAndStorageFailureReporting() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            var nowMs = 20_000L
            val authClock = AuthMonotonicClock { nowMs }
            val testDisp = StandardTestDispatcher(testScheduler)
            val dispatchers = object : com.weback.robot.core.DispatcherProvider {
                override val main = testDisp
                override val io = kotlinx.coroutines.Dispatchers.IO
                override val default = testDisp
            }
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
            var failClear = false
            val tokenStore = object : TokenStore {
                var stored: PersistedSessionRecord? = null
                override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
                    stored = record
                    return TokenSaveResult.Success
                }
                override suspend fun load() = stored?.let { TokenLoadResult.Loaded(it) } ?: TokenLoadResult.Empty
                override suspend fun clear(): TokenClearResult {
                    return if (failClear) {
                        TokenClearResult.Failure("DISK_WIPE_FAILED")
                    } else {
                        stored = null
                        TokenClearResult.Success
                    }
                }
            }
            val pilotAuth = PilotCapabilityRestrictedAuthGateway(
                router = RegionEndpointRouter(globalOverrideUrl = "$originHttp/prod/oauth"),
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
            val coordinator = SessionCoordinator(
                pilotAuth,
                tokenStore,
                BootSessionTimeProvider { BootTimeSnapshot("boot-exp", nowMs, 1_700_000_000_000L) }
            )
            val invRepo = DeviceInventoryRepository(coordinator, pilotInv)
            val wsDelegate = OkHttpWebSocketTransport(loopbackPolicy, authClock, dispatchers, ConnectionConfig(), client = pilotClient)
            val runtimeId = ExpectedCandidateIdentity(
                candidateCommitSha = "bc7f6d6589b4962c092574d998f6dfce532b17c7",
                applicationId = "com.weback.robot.pilot",
                versionName = "0.1.0-alpha",
                apkSha256 = "2".repeat(64)
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
                authorizationId = "FCT004C-EXP-01",
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
                externalApproval = PilotExternalApproval("PO-Human", "Lead-Human", "EXT-EXP-01")
            )

            // 1. Late response after revoke cannot restore authorization
            assertTrue(controller.activatePilot(ctx) is GateActivationResult.Activated)
            server.enqueue(
                MockResponse().setResponseCode(200).setBodyDelay(5, TimeUnit.SECONDS).setBody(
                    """{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-late","expired_time":3600}}"""
                )
            )
            val inFlight = async {
                controller.submitCredentials(AuthCredentials("+1", "pilot@test.invalid", "Secret1!".toCharArray()))
            }
            runCurrent()
            Thread.sleep(40)
            val revokeRes = controller.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect)
            assertFalse(revokeRes.serverSideTokenRevocationClaimed)
            assertTrue(revokeRes.storageCleared)
            assertTrue(inFlight.await() is AuthResult.Failure)
            assertFalse(gate.isAuthorized)
            assertEquals(PilotModeState.OfflineDefault, controller.pilotModeState.value)

            // 2. 15-minute monotonic deadline expiry with simulated storage failure reports IncompleteStorageClear
            val ctx2 = ctx.copy(
                authorizationId = "FCT004C-EXP-02",
                authorizedAtElapsedMs = nowMs,
                observationDeadlineElapsedMs = nowMs + 900_000L
            )
            assertTrue(controller.activatePilot(ctx2) is GateActivationResult.Activated)
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-exp","expired_time":3600}}"""
                )
            )
            assertTrue(controller.submitCredentials(AuthCredentials("+1", "pilot@test.invalid", "Secret1!".toCharArray())) is AuthResult.Success)
            assertNotNull(tokenStore.stored)

            failClear = true
            nowMs += 900_000L
            assertTrue(controller.enforceDeadlineIfExpired())
            assertFalse(gate.isAuthorized)
            assertFalse(coordinator.sessionState.value is SessionState.Authenticated)
            assertEquals(PilotModeState.OfflineDefault, controller.pilotModeState.value)
            val expRes = controller.lastDisconnectResult.value!!
            assertFalse(expRes.storageCleared)
            assertEquals("DISK_WIPE_FAILED", expRes.incompleteStorageReason)
            assertFalse(expRes.serverSideTokenRevocationClaimed)
            controller.close()
        } finally {
            server.shutdown()
        }
    }
}
