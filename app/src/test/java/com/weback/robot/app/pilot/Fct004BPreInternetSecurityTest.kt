package com.weback.robot.app.pilot

import com.weback.robot.app.session.DeviceInventoryRepository
import com.weback.robot.app.session.SessionCoordinator
import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.BootSessionTimeProvider
import com.weback.robot.core.BootTimeSnapshot
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.domain.model.SessionState
import com.weback.robot.domain.pilot.AdmissionResult
import com.weback.robot.domain.pilot.ExpectedCandidateIdentity
import com.weback.robot.domain.pilot.GateActivationResult
import com.weback.robot.domain.pilot.GateCheckResult
import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotAccountScope
import com.weback.robot.domain.pilot.PilotAccountScopeType
import com.weback.robot.domain.pilot.PilotAdmissionTicket
import com.weback.robot.domain.pilot.PilotAuthorizationContext
import com.weback.robot.domain.pilot.PilotAuthorizationGate
import com.weback.robot.domain.pilot.PilotClock
import com.weback.robot.domain.pilot.PilotEndpointConfig
import com.weback.robot.domain.pilot.PilotEnvironment
import com.weback.robot.domain.pilot.PilotExternalApproval
import com.weback.robot.domain.pilot.PilotModeState
import com.weback.robot.domain.pilot.PilotOperation
import com.weback.robot.domain.pilot.PilotRequestBudgets
import com.weback.robot.domain.pilot.PilotTargetDevice
import com.weback.robot.protocol.SimulatorRobotTransport
import com.weback.robot.protocol.auth.RegionEndpointRouter
import com.weback.robot.protocol.inventory.OkHttpDeviceInventoryGateway
import com.weback.robot.protocol.pilot.PilotAuthProbeOutcome
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class Fct004BPreInternetSecurityTest {

    private val runtimeIdentity = ExpectedCandidateIdentity(
        candidateCommitSha = "ed8b8c584fd45d9750be33945579ecbbbd93be20",
        applicationId = "com.weback.robot.pilot",
        versionName = "0.1.0-alpha",
        apkSha256 = "b".repeat(64)
    )

    private fun buildContext(
        authId: String,
        originHttp: String,
        port: Int,
        accountIdentifier: String = "pilot-a@example.invalid",
        nowMs: Long = 10_000L,
        budgets: PilotRequestBudgets = PilotRequestBudgets()
    ) = PilotAuthorizationContext(
        authorizationId = authId,
        environment = PilotEnvironment.LIVE_PILOT,
        candidateIdentity = runtimeIdentity,
        accountScope = PilotAccountScope(
            scopeType = PilotAccountScopeType.DEDICATED_ISOLATED_TEST_ACCOUNT,
            regionCallingCode = "+1",
            authorizedAccountAlias = "p***@example.invalid",
            authorizedAccountIdentifier = accountIdentifier
        ),
        targetDevice = PilotTargetDevice("LaserSlam", "laser_01", "LASER_01"),
        permittedOperations = setOf(
            PilotOperation.LOGIN,
            PilotOperation.USER_THING_LIST_GET,
            PilotOperation.WS_TELEMETRY_OBSERVE
        ),
        endpointConfig = PilotEndpointConfig(
            approvedAuthOrigin = originHttp,
            approvedAuthPath = "/prod/oauth",
            pinnedApiOrigin = "https://127.0.0.1:$port",
            pinnedWssOrigin = "wss://127.0.0.1:$port"
        ),
        readOnlyMode = true,
        physicalRobotCommandsPermitted = false,
        budgets = budgets,
        authorizedAtElapsedMs = nowMs,
        observationDeadlineElapsedMs = nowMs + budgets.observationWindowMs,
        externalApproval = PilotExternalApproval("PO", "Lead", "REF-$authId")
    )

    @Test
    fun bSec01_forgedAndTamperedAdmissionTicketsRejectedFailClosed() {
        var nowMs = 10_000L
        val gate = PilotAuthorizationGate(
            allowedAuthOrigins = setOf("https://127.0.0.1:8443"),
            clock = PilotClock { nowMs }
        )
        val ctx = buildContext(
            authId = "FCT004B-BSEC01-FORGE",
            originHttp = "https://127.0.0.1:8443",
            port = 8443,
            budgets = PilotRequestBudgets(httpCallTimeoutMs = 500L)
        )
        assertTrue(gate.activate(ctx, runtimeIdentity, nowMs) is GateActivationResult.Activated)

        val admitted = gate.acquireAdmission(
            operation = PilotOperation.LOGIN,
            nowElapsedMs = nowMs,
            requestUrl = "https://127.0.0.1:8443/prod/oauth"
        ) as AdmissionResult.Admitted
        val realTicket = admitted.ticket

        // Caller-created forged ticket with unknown ticketId
        val forgedTicket = PilotAdmissionTicket(
            ticketId = "forged-caller-id",
            authorizationId = realTicket.authorizationId,
            pilotGeneration = realTicket.pilotGeneration,
            operation = PilotOperation.LOGIN,
            deadlineElapsedMs = realTicket.deadlineElapsedMs,
            environment = PilotEnvironment.LIVE_PILOT,
            httpCallTimeoutMs = 15_000L,
            maxWsReconnectAttempts = 3,
            issuedElapsedMs = nowMs,
            remainingWindowAtIssueMs = 1_800_000L,
            boundAccountIdentifier = realTicket.boundAccountIdentifier,
            boundCallingCode = realTicket.boundCallingCode,
            boundRequestUrl = realTicket.boundRequestUrl
        )
        assertTrue(gate.verifyTicket(forgedTicket, nowMs) is GateCheckResult.Denied)
        assertTrue(
            gate.consumeTicketForDispatch(
                forgedTicket,
                "https://127.0.0.1:8443/prod/oauth",
                PilotOperation.LOGIN,
                nowMs
            ) is GateCheckResult.Denied
        )

        // Modified ticket fields via copy() to bypass timeout, operation, deadline, or generation
        val tamperedTimeout = realTicket.copy(httpCallTimeoutMs = 15_000L)
        val tamperedOp = realTicket.copy(operation = PilotOperation.USER_THING_LIST_GET)
        val tamperedGen = realTicket.copy(pilotGeneration = realTicket.pilotGeneration + 1L)
        val tamperedDeadline = realTicket.copy(deadlineElapsedMs = realTicket.deadlineElapsedMs + 999_999L)
        assertTrue(gate.verifyTicket(tamperedTimeout, nowMs) is GateCheckResult.Denied)
        assertTrue(gate.verifyTicket(tamperedOp, nowMs) is GateCheckResult.Denied)
        assertTrue(gate.verifyTicket(tamperedGen, nowMs) is GateCheckResult.Denied)
        assertTrue(gate.verifyTicket(tamperedDeadline, nowMs) is GateCheckResult.Denied)
    }

    @Test
    fun bSec01_singleUseTicketBlocksReplayAcrossMockWebServerAndDistinguishesIntermediateVerify() {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            var nowMs = 10_000L
            val authClock = AuthMonotonicClock { nowMs }
            val originHttp = "http://127.0.0.1:${server.port}"
            val gate = PilotAuthorizationGate(
                allowedAuthOrigins = setOf(originHttp),
                clock = PilotClock { authClock.elapsedRealtimeMs() }
            )
            val pilotTrust = AuthorizedPilotEndpointTrustPolicy(
                PilotEndpointConfig(
                    approvedAuthOrigin = originHttp,
                    pinnedApiOrigin = "https://127.0.0.1:${server.port}",
                    pinnedWssOrigin = "wss://127.0.0.1:${server.port}"
                ),
                allowedAuthOrigins = setOf(originHttp),
                allowLoopbackForHermeticTests = true
            )
            val activeCalls = PilotActiveCallRegistry()
            val client = PilotClientFactory.buildPilotOkHttpClient(
                trustPolicy = pilotTrust,
                activeCallRegistry = activeCalls,
                admissionGate = gate,
                authClock = authClock
            )

            assertTrue(
                gate.activate(
                    buildContext("FCT004B-BSEC01-REPLAY", originHttp, server.port, nowMs = nowMs),
                    runtimeIdentity,
                    nowMs
                ) is GateActivationResult.Activated
            )

            val loginTicket = (
                gate.acquireAdmission(
                    operation = PilotOperation.LOGIN,
                    nowElapsedMs = nowMs,
                    requestUrl = "$originHttp/prod/oauth"
                ) as AdmissionResult.Admitted
            ).ticket

            // Intermediate verification must succeed repeatedly without consuming the ticket
            assertTrue(gate.verifyTicket(loginTicket, nowMs, "$originHttp/prod/oauth") is GateCheckResult.Allowed)
            assertTrue(gate.verifyTicket(loginTicket, nowMs, "$originHttp/prod/oauth") is GateCheckResult.Allowed)

            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1}"""))

            val loginBody = """{"header":{},"payload":{"opt":"login","pwd":"synthetic"}}"""
            val req = Request.Builder()
                .url("$originHttp/prod/oauth")
                .post(loginBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .tag(PilotAdmissionTicket::class.java, loginTicket)
                .build()

            // First dispatch consumes ticket and hits MockWebServer once
            client.newCall(req).execute().use { resp ->
                assertEquals(200, resp.code)
            }
            assertEquals(1, server.requestCount)

            // Replaying the exact same ticket must fail closed with ZERO extra MockWebServer requests
            var replayBlocked = false
            try {
                client.newCall(req).execute().close()
            } catch (ioe: IOException) {
                replayBlocked = true
            }
            assertTrue("Ticket replay must be rejected at dispatch", replayBlocked)
            assertEquals("Ticket replay must produce zero additional HTTP requests", 1, server.requestCount)

            // Unconsumed ticket bound to LOGIN cannot be used for arbitrary command payload
            val invTicket = (
                gate.acquireAdmission(
                    operation = PilotOperation.USER_THING_LIST_GET,
                    nowElapsedMs = nowMs,
                    requestUrl = "$originHttp/api"
                ) as AdmissionResult.Admitted
            ).ticket
            val rogueReq = Request.Builder()
                .url("$originHttp/api")
                .post("""{"opt":"send_to_device"}""".toRequestBody("application/json; charset=utf-8".toMediaType()))
                .tag(PilotAdmissionTicket::class.java, invTicket)
                .build()
            var rogueBlocked = false
            try {
                client.newCall(rogueReq).execute().close()
            } catch (_: IOException) {
                rogueBlocked = true
            }
            assertTrue(rogueBlocked)
            assertEquals(1, server.requestCount)

            // WS ticket: first upgrade succeeds, replay fails closed with zero extra MockWebServer requests
            val wsOpenLatch = CountDownLatch(1)
            val wsReplayFailLatch = CountDownLatch(1)
            var serverWs: WebSocket? = null
            server.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverWs = webSocket
                        wsOpenLatch.countDown()
                    }
                })
            )
            server.enqueue(MockResponse().setResponseCode(500))

            val wsTicket = (
                gate.acquireAdmission(
                    operation = PilotOperation.WS_TELEMETRY_OBSERVE,
                    nowElapsedMs = nowMs,
                    requestUrl = "ws://127.0.0.1:${server.port}/ws",
                    isReconnect = false
                ) as AdmissionResult.Admitted
            ).ticket
            val wsReq = Request.Builder()
                .url("ws://127.0.0.1:${server.port}/ws")
                .tag(PilotAdmissionTicket::class.java, wsTicket)
                .build()
            val ws1 = client.newWebSocket(wsReq, object : WebSocketListener() {})
            assertTrue(wsOpenLatch.await(5, TimeUnit.SECONDS))
            assertEquals(2, server.requestCount)

            client.newWebSocket(wsReq, object : WebSocketListener() {
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    wsReplayFailLatch.countDown()
                }
            })
            assertTrue(wsReplayFailLatch.await(5, TimeUnit.SECONDS))
            assertEquals("WS ticket replay must not reach MockWebServer", 2, server.requestCount)

            // Generation-fenced revocation invalidates unconsumed reconnect ticket
            val reconnectTicket = (
                gate.acquireAdmission(
                    operation = PilotOperation.WS_TELEMETRY_OBSERVE,
                    nowElapsedMs = nowMs,
                    requestUrl = "ws://127.0.0.1:${server.port}/ws",
                    isReconnect = true
                ) as AdmissionResult.Admitted
            ).ticket
            gate.revoke(PilotAbortReason.UserEmergencyDisconnect)
            assertTrue(gate.verifyTicket(reconnectTicket, nowMs) is GateCheckResult.Denied)

            ws1.cancel()
            runCatching { serverWs?.close(1000, "teardown") }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun bSec02_directAuthGatewayInvocationWithDifferentAccountInSameRegionMakesZeroNetworkRequests() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val nowMs = 10_000L
            val authClock = AuthMonotonicClock { nowMs }
            val dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
            val originHttp = "http://127.0.0.1:${server.port}"
            val loopbackPolicy = TestLoopbackTrustPolicy(
                setOf(
                    UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port),
                    UriValidator.NormalizedOrigin("ws", "127.0.0.1", server.port)
                )
            )
            val router = RegionEndpointRouter(globalOverrideUrl = "$originHttp/prod/oauth")
            val gate = PilotAuthorizationGate(
                allowedAuthOrigins = setOf(originHttp),
                clock = PilotClock { authClock.elapsedRealtimeMs() }
            )
            val pilotTrust = AuthorizedPilotEndpointTrustPolicy(
                PilotEndpointConfig(originHttp),
                allowedAuthOrigins = setOf(originHttp),
                allowLoopbackForHermeticTests = true
            )
            val client = PilotClientFactory.buildPilotOkHttpClient(
                trustPolicy = pilotTrust,
                admissionGate = gate,
                authClock = authClock
            )
            val pilotAuth = PilotCapabilityRestrictedAuthGateway(
                router = router,
                trustPolicy = loopbackPolicy,
                authClock = authClock,
                dispatchers = dispatchers,
                baseClient = client,
                pilotEndpointTrustPolicy = pilotTrust,
                admissionGate = gate
            )

            assertTrue(
                gate.activate(
                    buildContext(
                        authId = "FCT004B-BSEC02-01",
                        originHttp = originHttp,
                        port = server.port,
                        accountIdentifier = "authorized-pilot@example.invalid",
                        nowMs = nowMs
                    ),
                    runtimeIdentity,
                    nowMs
                ) is GateActivationResult.Activated
            )

            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-ok","expired_time":3600}}"""
                )
            )

            // Direct gateway invocation with a DIFFERENT account in the SAME +1 region
            val rogueSecret = "WrongAccountSecret1!".toCharArray()
            val directWrong = pilotAuth.signIn(AuthCredentials("+1", "other-account@example.invalid", rogueSecret))
            assertTrue(directWrong is AuthResult.Failure)
            assertEquals("Direct gateway call with wrong account must send 0 HTTP requests", 0, server.requestCount)

            val probeWrong = pilotAuth.probeLogin(AuthCredentials("+1", "other-account@example.invalid", "Secret2!".toCharArray()))
            assertTrue(probeWrong is PilotAuthProbeOutcome.Failed)
            assertEquals(0, server.requestCount)

            // Exact authorized account in +1 succeeds
            val directOk = pilotAuth.signIn(AuthCredentials("+1", "authorized-pilot@example.invalid", "GoodSecret1!".toCharArray()))
            assertTrue("Expected authorized account success, got $directOk", directOk is AuthResult.Success)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun bSec03_absoluteMonotonicClockAndDeepSleepTriggersFullGenerationFencedCleanup() = runTest {
        val gateFile = listOf(
            File("src/main/java/com/weback/robot/domain/pilot/PilotAuthorizationGate.kt"),
            File("app/src/main/java/com/weback/robot/domain/pilot/PilotAuthorizationGate.kt")
        ).first { it.isFile }
        val gateSource = gateFile.readText()
        assertFalse("PilotAuthorizationGate must not rely on System.nanoTime()", gateSource.contains("nanoTime"))

        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val openLatch = CountDownLatch(1)
        var serverWs: WebSocket? = null
        try {
            var nowMs = 10_000L
            val authClock = AuthMonotonicClock { nowMs }
            val timeProvider = BootSessionTimeProvider { BootTimeSnapshot("boot_1", nowMs, 1_700_000_000_000L) }
            val dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
            val originHttp = "http://127.0.0.1:${server.port}"
            val loopbackPolicy = TestLoopbackTrustPolicy(
                setOf(
                    UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port),
                    UriValidator.NormalizedOrigin("ws", "127.0.0.1", server.port)
                )
            )
            val router = RegionEndpointRouter(globalOverrideUrl = "$originHttp/prod/oauth")
            var persisted: PersistedSessionRecord? = null
            val tokenStore = object : TokenStore {
                override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
                    persisted = record
                    return TokenSaveResult.Success
                }

                override suspend fun load(): TokenLoadResult =
                    persisted?.let { TokenLoadResult.Loaded(it) } ?: TokenLoadResult.Empty

                override suspend fun clear(): TokenClearResult {
                    persisted = null
                    return TokenClearResult.Success
                }
            }
            val gate = PilotAuthorizationGate(
                allowedAuthOrigins = setOf(originHttp),
                clock = PilotClock { authClock.elapsedRealtimeMs() }
            )
            val pilotTrust = AuthorizedPilotEndpointTrustPolicy(
                PilotEndpointConfig(
                    approvedAuthOrigin = originHttp,
                    pinnedApiOrigin = "https://127.0.0.1:${server.port}",
                    pinnedWssOrigin = "wss://127.0.0.1:${server.port}"
                ),
                allowedAuthOrigins = setOf(originHttp),
                allowLoopbackForHermeticTests = true
            )
            val activeCalls = PilotActiveCallRegistry()
            val pilotClient = PilotClientFactory.buildPilotOkHttpClient(
                trustPolicy = pilotTrust,
                activeCallRegistry = activeCalls,
                admissionGate = gate,
                authClock = authClock
            )
            val pilotAuth = PilotCapabilityRestrictedAuthGateway(
                router, loopbackPolicy, authClock, dispatchers = dispatchers,
                baseClient = pilotClient, pilotEndpointTrustPolicy = pilotTrust, admissionGate = gate
            )
            val pilotInv = PilotCapabilityRestrictedInventoryGateway(
                OkHttpDeviceInventoryGateway(loopbackPolicy, authClock, dispatchers, client = pilotClient),
                admissionGate = gate,
                authClock = authClock,
                pilotEndpointTrustPolicy = pilotTrust
            )
            val coordinator = SessionCoordinator(pilotAuth, tokenStore, timeProvider)
            val invRepo = DeviceInventoryRepository(coordinator, pilotInv)
            val wsDelegate = OkHttpWebSocketTransport(
                loopbackPolicy, authClock, dispatchers, ConnectionConfig(), client = pilotClient,
                resetReconnectAttemptOnOpen = false
            )
            val controller = PilotSessionController(
                gate = gate,
                expectedRuntimeIdentity = runtimeIdentity,
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

            val run1 = buildContext("FCT004B-BSEC03-SLEEP-1", originHttp, server.port, nowMs = 10_000L, budgets = PilotRequestBudgets(observationWindowMs = 60_000L))
            assertTrue(controller.activatePilot(run1) is GateActivationResult.Activated)

            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-sleep","expired_time":3600}}"""))
            assertTrue(controller.submitCredentials(AuthCredentials("+1", "pilot-a@example.invalid", "pw".toCharArray())) is AuthResult.Success)
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"thing_list":[{"thing_name":"laser_01","sub_type":"xs-x8-eu","thing_nickname":"Laser","is_admin":true}]}}"""))
            assertTrue(controller.refreshReadOnlyInventory())
            server.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverWs = webSocket
                        openLatch.countDown()
                    }
                })
            )
            assertTrue(controller.selectAndObserveDevice("laser_01"))
            assertTrue(openLatch.await(5, TimeUnit.SECONDS))

            // Simulate Android CPU deep sleep: SystemClock.elapsedRealtime jumps past deadline
            // WITHOUT advancing coroutine delay() uptime!
            nowMs = 70_000L

            // Pre-network admission fails closed AND triggers complete generation-fenced cleanup
            val preNetworkDenied = gate.authorizeOperation(PilotOperation.USER_THING_LIST_GET)
            assertTrue(preNetworkDenied is GateCheckResult.Denied)
            testScheduler.runCurrent()

            assertFalse(gate.isAuthorized)
            assertEquals(PilotModeState.OfflineDefault, controller.pilotModeState.value)
            assertEquals(PilotAbortReason.ObservationWindowExpired, controller.lastAbortReason.value)
            assertTrue(wsDelegate.connectionState.value is ConnectionState.Disconnected)
            assertFalse(coordinator.sessionState.value is SessionState.Authenticated)
            assertTrue(tokenStore.load() is TokenLoadResult.Empty)

            // Activate Run 2, verify enforceDeadlineIfExpired() also performs full cleanup on deep sleep,
            // and confirm stale Run 1 expiry/disconnect cannot corrupt Run 3
            val run1Gen = gate.currentGeneration() - 1L
            nowMs = 75_000L
            val run2 = buildContext("FCT004B-BSEC03-RUN-2", originHttp, server.port, nowMs = 75_000L, budgets = PilotRequestBudgets(observationWindowMs = 60_000L))
            assertTrue(controller.activatePilot(run2) is GateActivationResult.Activated)
            nowMs = 135_000L
            assertTrue(controller.enforceDeadlineIfExpired())
            assertFalse(gate.isAuthorized)
            assertEquals(PilotAbortReason.ObservationWindowExpired, controller.lastAbortReason.value)

            nowMs = 140_000L
            val run3 = buildContext("FCT004B-BSEC03-RUN-3", originHttp, server.port, nowMs = 140_000L, budgets = PilotRequestBudgets(observationWindowMs = 60_000L))
            assertTrue(controller.activatePilot(run3) is GateActivationResult.Activated)
            controller.emergencyDisconnectAndWipe(PilotAbortReason.ObservationWindowExpired, expectedGeneration = run1Gen)
            assertTrue(gate.isAuthorized)
            assertTrue(controller.pilotModeState.value is PilotModeState.AuthorizedReady)

            wsDelegate.close()
        } finally {
            runCatching { serverWs?.close(1000, "teardown") }
            server.shutdown()
        }
    }
}
