package com.weback.robot.protocol.pilot

import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.CommandResult
import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.model.ConnectivityError
import com.weback.robot.domain.model.RobotCommand
import com.weback.robot.domain.model.SessionCredentials
import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotEndpointConfig
import com.weback.robot.domain.pilot.ReadOnlyPilotGuard
import com.weback.robot.protocol.SimulatorConfig
import com.weback.robot.protocol.SimulatorRobotTransport
import com.weback.robot.protocol.auth.AuthEnvelopeConfig
import com.weback.robot.protocol.auth.OkHttpAuthGateway
import com.weback.robot.protocol.auth.RegionEndpointRouter
import com.weback.robot.protocol.inventory.InventoryFetchResult
import com.weback.robot.protocol.inventory.OkHttpDeviceInventoryGateway
import com.weback.robot.protocol.ws.ConnectionConfig
import com.weback.robot.protocol.ws.OkHttpWebSocketTransport
import com.weback.robot.security.AuthorizedPilotEndpointTrustPolicy
import com.weback.robot.security.PilotClientFactory
import com.weback.robot.testutils.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class PilotStructuralBoundaryTest {

    @Test
    fun testBlocksAllOutboundWsAppFramesAndArbitraryHttpPayloadsWhilePreservingControlFramesAndSimulator() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val receivedTextFrames = CopyOnWriteArrayList<String>()
        val receivedBinaryFrames = CopyOnWriteArrayList<ByteString>()
        val openLatch = CountDownLatch(1)
        val closeLatch = CountDownLatch(1)
        var serverSocket: WebSocket? = null

        try {
            server.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocket = webSocket
                        openLatch.countDown()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        receivedTextFrames.add(text)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        receivedBinaryFrames.add(bytes)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                        closeLatch.countDown()
                    }
                })
            )

            val origin = "https://127.0.0.1:${server.port}"
            val trustPolicy = AuthorizedPilotEndpointTrustPolicy(
                endpointConfig = PilotEndpointConfig(
                    approvedAuthOrigin = origin,
                    approvedAuthPath = "/prod/oauth",
                    pinnedApiOrigin = origin,
                    pinnedWssOrigin = "wss://127.0.0.1:${server.port}"
                ),
                allowedAuthOrigins = setOf(origin),
                allowLoopbackForHermeticTests = true
            )
            // Use http/ws loopback policy for MockWebServer cleartext test transport while testing structural boundary
            val loopbackPolicy = com.weback.robot.security.TestLoopbackTrustPolicy(
                setOf(
                    com.weback.robot.security.UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port),
                    com.weback.robot.security.UriValidator.NormalizedOrigin("ws", "127.0.0.1", server.port)
                )
            )
            val dispatcher = StandardTestDispatcher(testScheduler)
            val dispatchers = TestDispatcherProvider(dispatcher)
            val authClock = AuthMonotonicClock { 1_000L }

            val delegateWs = OkHttpWebSocketTransport(
                trustPolicy = loopbackPolicy,
                authClock = authClock,
                dispatchers = dispatchers,
                connectionConfig = ConnectionConfig(maxReconnectAttempts = 3),
                resetReconnectAttemptOnOpen = false
            )
            val abortReasons = mutableListOf<String>()
            val pilotWs = PilotReadOnlyWebSocketTransport(
                delegate = delegateWs,
                onUnexpectedOutboundAttempt = { detail -> abortReasons.add(detail) }
            )

            val session = SessionCredentials(
                jwtToken = "pilot-jwt",
                apiUrl = "http://127.0.0.1:${server.port}/api",
                wssUrl = "ws://127.0.0.1:${server.port}/ws",
                regionName = "us",
                expiresInSeconds = 3_600L,
                issuedAtElapsedMs = 0L
            )
            pilotWs.connect(session, epoch = 1L)
            assertTrue("Expected WS upgrade open", openLatch.await(5, TimeUnit.SECONDS))
            val deadline = System.currentTimeMillis() + 2_000L
            while (pilotWs.connectionState.value !is ConnectionState.Connected && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }

            // Direct calls to sendOutbound (text) and sendBinaryOutbound (binary) MUST be blocked and trigger abort
            assertFalse(pilotWs.sendOutbound("""{"opt":"send_to_device","topic_payload":{"state":{"working_status":"Clean"}}}""", epoch = 1L))
            assertFalse(pilotWs.sendOutbound("""{"opt":"subscribe"}""", epoch = 1L))
            assertFalse(pilotWs.sendBinaryOutbound(byteArrayOf(1, 2, 3), epoch = 1L))
            assertEquals(3, abortReasons.size)

            // Guarded physical robot command also rejects + aborts
            val guardAborts = mutableListOf<PilotAbortReason>()
            val guard = ReadOnlyPilotGuard(
                isPilotReadOnlyActive = { true },
                onUnexpectedCommandAttempt = { _, r -> guardAborts.add(r) }
            )
            val cmdRes = guard.guardLiveCommand(RobotCommand.CLEAN)
            assertTrue(cmdRes is CommandResult.Rejected)
            assertEquals(listOf(PilotAbortReason.UnexpectedOutboundCommandAttempt), guardAborts)

            // Standard RFC 6455 close control frame STILL works cleanly
            pilotWs.invalidateForEpoch(1L, ConnectivityError.Unauthenticated)
            assertTrue("Expected standard WS Close control frame", closeLatch.await(5, TimeUnit.SECONDS))

            // Independent MockWebServer capture proves ZERO application frames ever hit the wire
            assertTrue("Expected zero outbound WS text frames, got $receivedTextFrames", receivedTextFrames.isEmpty())
            assertTrue("Expected zero outbound WS binary frames, got $receivedBinaryFrames", receivedBinaryFrames.isEmpty())

            // Pilot HTTP operation guard rejects arbitrary, malformed, and send_to_device payloads
            assertTrue(PilotHttpOperationGuard.validateOperationPayload("login", """{"header":{},"payload":{"opt":"login","pwd":"x"}}"""))
            assertTrue(PilotHttpOperationGuard.validateOperationPayload("user_thing_list_get", """{"opt":"user_thing_list_get"}"""))
            assertFalse(PilotHttpOperationGuard.validateOperationPayload("user_thing_list_get", """{"opt":"send_to_device"}"""))
            assertFalse(PilotHttpOperationGuard.validateOperationPayload("login", """{"header":{},"payload":{"opt":"send_to_device"}}"""))
            assertFalse(PilotHttpOperationGuard.validateOperationPayload("user_thing_list_get", """{"opt":"user_thing_list_get","extra":"cmd"}"""))
            assertFalse(PilotHttpOperationGuard.validateOperationPayload("user_thing_list_get", """not-json"""))
            assertFalse(PilotHttpOperationGuard.validateOperationPayload("send_to_device", """{"opt":"send_to_device"}"""))

            val restrictedInv = PilotCapabilityRestrictedInventoryGateway(
                delegate = OkHttpDeviceInventoryGateway(loopbackPolicy, authClock, dispatchers)
            )
            val rawPayloadResult = restrictedInv.executeRawPayload(session, """{"opt":"send_to_device"}""")
            assertTrue(rawPayloadResult is InventoryFetchResult.Failure)
            // Only the 1 WS upgrade request reached MockWebServer; zero unauthorized HTTP requests
            assertEquals(1, server.requestCount)

            // Offline SimulatorRobotTransport remains functional and completely isolated
            val simulator = SimulatorRobotTransport(
                dispatcherProvider = dispatchers,
                config = SimulatorConfig(ackDelayMs = 10L)
            )
            val simResult = simulator.sendCommand(RobotCommand.CLEAN)
            assertTrue(simResult is CommandResult.Acknowledged)
            simulator.close()
            pilotWs.close()
        } finally {
            runCatching { serverSocket?.close(1000, "teardown") }
            server.shutdown()
        }
    }
}
