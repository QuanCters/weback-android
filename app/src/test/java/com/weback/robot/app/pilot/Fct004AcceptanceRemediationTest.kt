package com.weback.robot.app.pilot

import com.weback.robot.app.session.DeviceInventoryRepository
import com.weback.robot.app.session.SessionCoordinator
import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.BootSessionTimeProvider
import com.weback.robot.core.BootTimeSnapshot
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.domain.model.SessionState
import com.weback.robot.domain.pilot.ExpectedCandidateIdentity
import com.weback.robot.domain.pilot.GateActivationResult
import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotAccountScope
import com.weback.robot.domain.pilot.PilotAccountScopeType
import com.weback.robot.domain.pilot.PilotAuthorizationContext
import com.weback.robot.domain.pilot.PilotAuthorizationGate
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class Fct004AcceptanceRemediationTest {

    private val runtimeIdentity = ExpectedCandidateIdentity(
        candidateCommitSha = "b0ce6ff1b3f65da67673d1e0cf7959b4266250ca",
        applicationId = "com.weback.robot",
        versionName = "0.1.0-alpha",
        apkSha256 = "3c933db2160c4407f44b3393dffdd3cf372074e5b06f8b29089ee0408e9fa90e"
    )

    private fun buildContext(
        authId: String,
        originHttp: String,
        port: Int,
        accountIdentifier: String = "pilot@example.invalid",
        accountAlias: String = "p***@example.invalid",
        budgets: PilotRequestBudgets = PilotRequestBudgets(),
        nowMs: Long = 5_000L
    ) = PilotAuthorizationContext(
        authorizationId = authId,
        environment = PilotEnvironment.LIVE_PILOT,
        candidateIdentity = runtimeIdentity,
        accountScope = PilotAccountScope(
            scopeType = PilotAccountScopeType.DEDICATED_ISOLATED_TEST_ACCOUNT,
            regionCallingCode = "+1",
            authorizedAccountIdentifier = accountIdentifier,
            authorizedAccountAlias = accountAlias
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
    fun fix401_networkAdmissionBoundaryDeniesUnadmittedDirectAndStaleCallsBeforeMockWebServer() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val originHttp = "http://127.0.0.1:${server.port}"
            val loopbackPolicy = TestLoopbackTrustPolicy(
                setOf(UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port))
            )
            val activeCalls = PilotActiveCallRegistry()
            val gate = PilotAuthorizationGate(allowedAuthOrigins = setOf(originHttp))
            val pilotTrust = AuthorizedPilotEndpointTrustPolicy(
                PilotEndpointConfig(originHttp),
                allowedAuthOrigins = setOf(originHttp),
                allowLoopbackForHermeticTests = true
            )
            val client = PilotClientFactory.buildPilotOkHttpClient(
                trustPolicy = pilotTrust,
                activeCallRegistry = activeCalls,
                admissionGate = gate
            )
            val router = RegionEndpointRouter(globalOverrideUrl = "$originHttp/prod/oauth")
            val pilotAuth = PilotCapabilityRestrictedAuthGateway(
                router = router,
                trustPolicy = loopbackPolicy,
                authClock = AuthMonotonicClock { 5_000L },
                baseClient = client,
                admissionGate = gate
            )

            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1}"""))

            // 1. Direct gateway call without active pilot authorization MUST NOT hit MockWebServer
            val directRes = pilotAuth.signIn(AuthCredentials("+1", "pilot@example.invalid", "pw".toCharArray()))
            assertTrue(directRes is AuthResult.Failure)
            assertEquals("Denied gateway call must never reach MockWebServer", 0, server.requestCount)

            // 2. Direct OkHttpClient call without valid admission ticket MUST NOT hit MockWebServer
            var rawBlocked = false
            try {
                client.newCall(Request.Builder().url("$originHttp/prod/oauth").build()).execute()
            } catch (_: IOException) {
                rawBlocked = true
            }
            assertTrue("Raw OkHttp call without pilot admission must fail closed", rawBlocked)
            assertEquals(0, server.requestCount)

            // 3. Call registered after cancelAll() or revokeAndCancelAll() cannot escape cancellation
            val ctx = buildContext("FCT004-FIX401-01", originHttp, server.port)
            assertTrue(gate.activate(ctx, runtimeIdentity, 5_000L) is GateActivationResult.Activated)
            val gen = gate.currentGeneration()
            activeCalls.revokeAndCancelAll(gen)
            val lateCall = client.newCall(Request.Builder().url("$originHttp/prod/oauth").build())
            assertFalse(activeCalls.registerForGeneration(lateCall, gen))
            assertTrue(lateCall.isCanceled())

            val emptyRegistry = PilotActiveCallRegistry()
            emptyRegistry.cancelAll()
            val afterEmptyCancel = client.newCall(Request.Builder().url("$originHttp/prod/oauth").build())
            assertFalse(emptyRegistry.registerForGeneration(afterEmptyCancel, 1L))
            assertTrue(afterEmptyCancel.isCanceled())

            // 4. Non-loopback OkHttp wss:// -> https:// URL normalization passes WS_TELEMETRY_OBSERVE admission while http/ws stays rejected
            val prodTrust = AuthorizedPilotEndpointTrustPolicy(
                PilotEndpointConfig(
                    approvedAuthOrigin = "https://user.grit-cloud.com:443",
                    pinnedApiOrigin = "https://api-us.grit-cloud.com:443",
                    pinnedWssOrigin = "wss://wss-us.grit-cloud.com:443"
                ),
                allowLoopbackForHermeticTests = false
            )
            val prodCtx = ctx.copy(
                endpointConfig = PilotEndpointConfig(
                    approvedAuthOrigin = "https://user.grit-cloud.com:443",
                    pinnedApiOrigin = "https://api-us.grit-cloud.com:443",
                    pinnedWssOrigin = "wss://wss-us.grit-cloud.com:443"
                )
            )
            val okHttpWssNormalized = Request.Builder().url("wss://wss-us.grit-cloud.com:443/ws").build().url.toString()
            assertTrue(prodTrust.isUrlAuthorizedForOperation(prodCtx, PilotOperation.WS_TELEMETRY_OBSERVE, okHttpWssNormalized))
            assertFalse(prodTrust.isUrlAuthorizedForOperation(prodCtx, PilotOperation.WS_TELEMETRY_OBSERVE, "http://wss-us.grit-cloud.com:443/ws"))
            assertFalse(prodTrust.isUrlAuthorizedForOperation(prodCtx, PilotOperation.WS_TELEMETRY_OBSERVE, "ws://wss-us.grit-cloud.com:443/ws"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun fix402_exactAuthorizedAccountBindingRejectsWrongAccountAndRedactedAliasWithZeroRequests() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val dispatchers = TestDispatcherProvider(dispatcher)
            val authClock = AuthMonotonicClock { 5_000L }
            val timeProvider = BootSessionTimeProvider { BootTimeSnapshot("boot_1", 5_000L, 1_700_000_000_000L) }
            val originHttp = "http://127.0.0.1:${server.port}"
            val loopbackPolicy = TestLoopbackTrustPolicy(
                setOf(
                    UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port),
                    UriValidator.NormalizedOrigin("ws", "127.0.0.1", server.port)
                )
            )
            val router = RegionEndpointRouter(globalOverrideUrl = "$originHttp/prod/oauth")
            val tokenStore = object : TokenStore {
                override suspend fun save(record: PersistedSessionRecord) = TokenSaveResult.Success
                override suspend fun load() = TokenLoadResult.Empty
                override suspend fun clear() = TokenClearResult.Success
            }
            val gate = PilotAuthorizationGate(allowedAuthOrigins = setOf(originHttp))
            val pilotTrust = AuthorizedPilotEndpointTrustPolicy(
                PilotEndpointConfig(originHttp),
                allowedAuthOrigins = setOf(originHttp),
                allowLoopbackForHermeticTests = true
            )
            val pilotAuth = PilotCapabilityRestrictedAuthGateway(router, loopbackPolicy, authClock, dispatchers = dispatchers, admissionGate = gate)
            val pilotInv = PilotCapabilityRestrictedInventoryGateway(OkHttpDeviceInventoryGateway(loopbackPolicy, authClock, dispatchers), admissionGate = gate, authClock = authClock)
            val coordinator = SessionCoordinator(pilotAuth, tokenStore, timeProvider)
            val invRepo = DeviceInventoryRepository(coordinator, pilotInv)
            val wsDelegate = OkHttpWebSocketTransport(loopbackPolicy, authClock, dispatchers, ConnectionConfig(), resetReconnectAttemptOnOpen = false)
            val controller = PilotSessionController(
                gate, runtimeIdentity, pilotTrust, coordinator, invRepo, pilotAuth, wsDelegate,
                SimulatorRobotTransport(dispatcherProvider = dispatchers), authClock, dispatchers, scope = backgroundScope
            )

            // Missing authorizedAccountIdentifier must be rejected by gate
            assertTrue(
                gate.activate(
                    buildContext("FCT004-FIX402-BLANK", originHttp, server.port, accountIdentifier = "  "),
                    runtimeIdentity,
                    5_000L
                ) is GateActivationResult.Rejected
            )

            // Same region (+1), different account -> denied with 0 network requests
            assertTrue(controller.activatePilot(buildContext("FCT004-FIX402-DIFF", originHttp, server.port)) is GateActivationResult.Activated)
            val diffRes = controller.submitCredentials(AuthCredentials("+1", "attacker@example.invalid", "pw".toCharArray()))
            assertTrue(diffRes is AuthResult.Failure)
            assertEquals(0, server.requestCount)

            // Redacted alias mistaken for exact identity -> denied with 0 network requests
            assertTrue(controller.activatePilot(buildContext("FCT004-FIX402-MASK", originHttp, server.port)) is GateActivationResult.Activated)
            val aliasRes = controller.submitCredentials(AuthCredentials("+1", "p***@example.invalid", "pw".toCharArray()))
            assertTrue(aliasRes is AuthResult.Failure)
            assertEquals(0, server.requestCount)

            // Correct exact synthetic account accepted
            assertTrue(controller.activatePilot(buildContext("FCT004-FIX402-OK", originHttp, server.port)) is GateActivationResult.Activated)
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-ok","expired_time":3600}}"""))
            val okRes = controller.submitCredentials(AuthCredentials("+1", "pilot@example.invalid", "pw".toCharArray()))
            assertTrue("Expected Success but got: $okRes", okRes is AuthResult.Success)
            assertEquals(1, server.requestCount)
            wsDelegate.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun fix403_blocksAuthorizationReplayAndEnforcesStricterReconnectAndHttpTimeoutBudgets() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val dispatchers = TestDispatcherProvider(dispatcher)
            val authClock = AuthMonotonicClock { 5_000L }
            val timeProvider = BootSessionTimeProvider { BootTimeSnapshot("boot_1", 5_000L, 1_700_000_000_000L) }
            val originHttp = "http://127.0.0.1:${server.port}"
            val loopbackPolicy = TestLoopbackTrustPolicy(
                setOf(
                    UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port),
                    UriValidator.NormalizedOrigin("ws", "127.0.0.1", server.port)
                )
            )
            val router = RegionEndpointRouter(globalOverrideUrl = "$originHttp/prod/oauth")
            val tokenStore = object : TokenStore {
                override suspend fun save(record: PersistedSessionRecord) = TokenSaveResult.Success
                override suspend fun load() = TokenLoadResult.Empty
                override suspend fun clear() = TokenClearResult.Success
            }
            val gate = PilotAuthorizationGate(allowedAuthOrigins = setOf(originHttp))
            val pilotTrust = AuthorizedPilotEndpointTrustPolicy(
                PilotEndpointConfig(originHttp),
                allowedAuthOrigins = setOf(originHttp),
                allowLoopbackForHermeticTests = true
            )
            val pilotClient = PilotClientFactory.buildPilotOkHttpClient(
                trustPolicy = pilotTrust,
                admissionGate = gate
            )
            val pilotAuth = PilotCapabilityRestrictedAuthGateway(router, loopbackPolicy, authClock, dispatchers = dispatchers, baseClient = pilotClient, admissionGate = gate)
            val pilotInv = PilotCapabilityRestrictedInventoryGateway(OkHttpDeviceInventoryGateway(loopbackPolicy, authClock, dispatchers, client = pilotClient), admissionGate = gate, authClock = authClock)
            val coordinator = SessionCoordinator(pilotAuth, tokenStore, timeProvider)
            val invRepo = DeviceInventoryRepository(coordinator, pilotInv)
            val wsDelegate = OkHttpWebSocketTransport(
                trustPolicy = loopbackPolicy,
                authClock = authClock,
                dispatchers = dispatchers,
                connectionConfig = ConnectionConfig(reconnectDelayMs = 4_000L, maxReconnectAttempts = 3),
                client = pilotClient,
                resetReconnectAttemptOnOpen = false
            )
            val controller = PilotSessionController(
                gate, runtimeIdentity, pilotTrust, coordinator, invRepo, pilotAuth, wsDelegate,
                SimulatorRobotTransport(dispatcherProvider = dispatchers), authClock, dispatchers, scope = backgroundScope
            )

            // 1. Authorization replay blocked
            val replayCtx = buildContext("FCT004-FIX403-REPLAY", originHttp, server.port)
            assertTrue(controller.activatePilot(replayCtx) is GateActivationResult.Activated)
            controller.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect)
            val replayAttempt = controller.activatePilot(replayCtx)
            assertTrue("Reusing consumed authorizationId must fail closed", replayAttempt is GateActivationResult.Rejected)

            // 2. Stricter HTTP timeout (200ms) aborts a delayed MockWebServer login response
            val shortTimeoutCtx = buildContext(
                "FCT004-FIX403-TIMEOUT",
                originHttp,
                server.port,
                budgets = PilotRequestBudgets(httpCallTimeoutMs = 200L)
            )
            assertTrue(controller.activatePilot(shortTimeoutCtx) is GateActivationResult.Activated)
            val defaultDispatcher = server.dispatcher
            val releaseSlowLogin = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    releaseSlowLogin.await(2, TimeUnit.SECONDS)
                    return MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-slow","expired_time":3600}}""")
                }
            }
            val timeoutRes = try {
                controller.submitCredentials(AuthCredentials("+1", "pilot@example.invalid", "pw".toCharArray()))
            } finally {
                releaseSlowLogin.countDown()
                server.dispatcher = defaultDispatcher
            }
            assertTrue(timeoutRes is AuthResult.Failure)

            // 3. Stricter WS reconnect budget (maxWsReconnectAttempts = 1) stops after 1 reconnect (2 total WS upgrades)
            val strictWsCtx = buildContext(
                "FCT004-FIX403-WS1",
                originHttp,
                server.port,
                budgets = PilotRequestBudgets(maxWsReconnectAttempts = 1)
            )
            assertTrue(controller.activatePilot(strictWsCtx) is GateActivationResult.Activated)
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-ws1","expired_time":3600}}"""))
            assertTrue(controller.submitCredentials(AuthCredentials("+1", "pilot@example.invalid", "pw".toCharArray())) is AuthResult.Success)
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"thing_list":[{"thing_name":"laser_01","sub_type":"xs-x8-eu","thing_nickname":"Laser","is_admin":true}]}}"""))
            assertTrue(controller.refreshReadOnlyInventory())

            server.enqueue(MockResponse().setResponseCode(500)) // initial fail
            server.enqueue(MockResponse().setResponseCode(500)) // reconnect #1 fail
            server.enqueue(MockResponse().setResponseCode(500)) // #2 MUST NOT happen!
            val reqBeforeWs = server.requestCount
            assertTrue(controller.selectAndObserveDevice("laser_01"))

            val d1 = System.currentTimeMillis() + 2_000L
            while (wsDelegate.connectionState.value !is ConnectionState.Reconnecting && System.currentTimeMillis() < d1) {
                Thread.sleep(10)
            }
            advanceTimeBy(4_000L)
            runCurrent()
            val d2 = System.currentTimeMillis() + 2_000L
            while (wsDelegate.connectionState.value !is ConnectionState.Disconnected && System.currentTimeMillis() < d2) {
                Thread.sleep(10)
            }
            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals("Expected 1 initial + 1 strict reconnect = 2 WS requests", 2, server.requestCount - reqBeforeWs)
            wsDelegate.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun fix405_staleCleanupAndUnresolvedOldCleanupNeverCorruptOrSignOutNewerPilotRun() = runBlocking {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val run3Release = java.util.concurrent.CountDownLatch(1)
        try {
            val dispatchers = com.weback.robot.core.DefaultDispatcherProvider()
            val authClock = AuthMonotonicClock { 5_000L }
            val timeProvider = BootSessionTimeProvider { BootTimeSnapshot("boot_1", 5_000L, 1_700_000_000_000L) }
            val originHttp = "http://127.0.0.1:${server.port}"
            val loopbackPolicy = TestLoopbackTrustPolicy(
                setOf(
                    UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port),
                    UriValidator.NormalizedOrigin("ws", "127.0.0.1", server.port)
                )
            )
            val router = RegionEndpointRouter(globalOverrideUrl = "$originHttp/prod/oauth")

            var stored: PersistedSessionRecord? = null
            var clearStarted = CompletableDeferred<Unit>()
            var releaseClear = CompletableDeferred<Unit>()
            var blockClear = false
            val tokenStore = object : TokenStore {
                override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
                    stored = record
                    return TokenSaveResult.Success
                }
                override suspend fun load() = stored?.let { TokenLoadResult.Loaded(it) } ?: TokenLoadResult.Empty
                override suspend fun clear(): TokenClearResult {
                    if (blockClear) {
                        clearStarted.complete(Unit)
                        releaseClear.await()
                    }
                    stored = null
                    return TokenClearResult.Success
                }
            }
            val gate = PilotAuthorizationGate(allowedAuthOrigins = setOf(originHttp))
            val pilotTrust = AuthorizedPilotEndpointTrustPolicy(
                PilotEndpointConfig(originHttp),
                allowedAuthOrigins = setOf(originHttp),
                allowLoopbackForHermeticTests = true
            )
            val pilotAuth = PilotCapabilityRestrictedAuthGateway(router, loopbackPolicy, authClock, dispatchers = dispatchers, admissionGate = gate)
            val pilotInv = PilotCapabilityRestrictedInventoryGateway(OkHttpDeviceInventoryGateway(loopbackPolicy, authClock, dispatchers), admissionGate = gate, authClock = authClock)
            val coordinator = SessionCoordinator(pilotAuth, tokenStore, timeProvider)
            val invRepo = DeviceInventoryRepository(coordinator, pilotInv)
            val wsDelegate = OkHttpWebSocketTransport(loopbackPolicy, authClock, dispatchers, ConnectionConfig(), resetReconnectAttemptOnOpen = false)
            val controllerScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + dispatchers.default
            )
            val controller = PilotSessionController(
                gate, runtimeIdentity, pilotTrust, coordinator, invRepo, pilotAuth, wsDelegate,
                SimulatorRobotTransport(dispatcherProvider = dispatchers), authClock, dispatchers, scope = controllerScope, ownsScope = true
            )

            // 1. Attempt new activation while prior Emergency Disconnect cleanup is STILL in-flight -> MUST be rejected!
            assertTrue(controller.activatePilot(buildContext("FCT004-FIX405-R1", originHttp, server.port)) is GateActivationResult.Activated)
            blockClear = true
            val disconnect1 = async(dispatchers.default) { controller.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect) }
            clearStarted.await()

            val activationDuringCleanup = controller.activatePilot(buildContext("FCT004-FIX405-R2", originHttp, server.port))
            assertTrue("Must block new activation while prior cleanup is in-flight", activationDuringCleanup is GateActivationResult.Rejected)

            releaseClear.complete(Unit)
            disconnect1.await()
            blockClear = false

            // 2. Late Run 3 login callback completing AFTER Run 4 activated & authenticated MUST NOT sign out Run 4!
            assertTrue(controller.activatePilot(buildContext("FCT004-FIX405-R3", originHttp, server.port)) is GateActivationResult.Activated)
            val run3Arrived = java.util.concurrent.CountDownLatch(1)
            val reqCounter = java.util.concurrent.atomic.AtomicInteger(0)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    return if (reqCounter.incrementAndGet() == 1) {
                        run3Arrived.countDown()
                        run3Release.await(2, TimeUnit.SECONDS)
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-stale-run3","expired_time":3600}}""")
                    } else {
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"code":1,"data":{"api_url":"$originHttp/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"jwt-fresh-run4","expired_time":3600}}""")
                    }
                }
            }
            val lateRun3Login = async(dispatchers.default) {
                controller.submitCredentials(AuthCredentials("+1", "pilot@example.invalid", "pw".toCharArray()))
            }
            assertTrue("Run 3 login should reach MockWebServer before disconnect", run3Arrived.await(2, TimeUnit.SECONDS))
            // Disconnect Run 3 cleanly while Run 3 response is still in-flight (idempotent second call too!)
            controller.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect)
            controller.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect)

            // Activate & authenticate Run 4
            assertTrue(controller.activatePilot(buildContext("FCT004-FIX405-R4", originHttp, server.port)) is GateActivationResult.Activated)
            val run4Login = controller.submitCredentials(AuthCredentials("+1", "pilot@example.invalid", "pw".toCharArray()))
            assertTrue("Expected Run4 Success but got: $run4Login", run4Login is AuthResult.Success)

            // Release Run 3 response AFTER Run 4 authenticated -> MUST NOT sign out Run 4 or overwrite Run 4 UI state!
            run3Release.countDown()
            val lateRes = lateRun3Login.await()
            assertTrue(lateRes is AuthResult.Failure)
            assertTrue("Run 4 must remain Authenticated", coordinator.sessionState.value is SessionState.Authenticated)
            assertEquals("jwt-fresh-run4", (coordinator.sessionState.value as SessionState.Authenticated).session.jwtToken)
            assertTrue("Run 4 UI state must remain Observing", controller.pilotModeState.value is PilotModeState.Observing)

            // 3. Overlapping cleanup where primary clear fails MUST keep incompleteCleanupPending=true and block reactivation
            controller.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect)
            val gate2 = PilotAuthorizationGate(allowedAuthOrigins = setOf(originHttp))
            var failClear = true
            val clearBarrier = CompletableDeferred<Unit>()
            val clearRelease = CompletableDeferred<Unit>()
            val overlapTokenStore = object : TokenStore {
                override suspend fun save(record: PersistedSessionRecord) = TokenSaveResult.Success
                override suspend fun load() = TokenLoadResult.Empty
                override suspend fun clear(): TokenClearResult {
                    if (failClear) {
                        clearBarrier.complete(Unit)
                        clearRelease.await()
                        return TokenClearResult.Failure("DISK_LOCKED")
                    }
                    return TokenClearResult.Success
                }
            }
            val overlapCoord = SessionCoordinator(pilotAuth, overlapTokenStore, timeProvider)
            val overlapControllerScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + dispatchers.default
            )
            val overlapCtrl = PilotSessionController(
                gate2, runtimeIdentity, pilotTrust, overlapCoord,
                DeviceInventoryRepository(overlapCoord, pilotInv), pilotAuth,
                OkHttpWebSocketTransport(loopbackPolicy, authClock, dispatchers, ConnectionConfig(), resetReconnectAttemptOnOpen = false),
                SimulatorRobotTransport(dispatcherProvider = dispatchers), authClock, dispatchers,
                scope = overlapControllerScope, ownsScope = true
            )
            assertTrue(overlapCtrl.activatePilot(buildContext("FCT004-FIX405-OVERLAP1", originHttp, server.port)) is GateActivationResult.Activated)
            val dPrimary = async(dispatchers.default) { overlapCtrl.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect) }
            clearBarrier.await()
            val dOverlap = async(dispatchers.default) { overlapCtrl.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect) }
            clearRelease.complete(Unit)
            dPrimary.await()
            dOverlap.await()
            assertTrue("Incomplete storage clear must block reactivation even after overlapping no-op disconnect", overlapCtrl.activatePilot(buildContext("FCT004-FIX405-OVERLAP2", originHttp, server.port)) is GateActivationResult.Rejected)

            // Once storage clears cleanly on retry, reactivation succeeds
            failClear = false
            assertTrue(overlapCtrl.emergencyDisconnectAndWipe(PilotAbortReason.UserEmergencyDisconnect).storageCleared)
            assertTrue(overlapCtrl.activatePilot(buildContext("FCT004-FIX405-OVERLAP3", originHttp, server.port)) is GateActivationResult.Activated)

            overlapCtrl.close()
            controller.close()
        } finally {
            run3Release.countDown()
            server.shutdown()
        }
    }
}
