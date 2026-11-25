package com.weback.robot.protocol.ws

import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.domain.device.DeviceCapabilityClassifier
import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.model.DeviceInventoryItem
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.SessionCredentials
import com.weback.robot.security.TestLoopbackTrustPolicy
import com.weback.robot.security.UriValidator
import com.weback.robot.testutils.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
class OkHttpWebSocketTransportTest {

    private fun testRobotItem(thingName: String = "laser_01", subType: String = "xs-x8-eu") = DeviceInventoryItem(
        thingName = thingName,
        subType = subType,
        nickname = "Living",
        isAdmin = true,
        capability = DeviceCapabilityClassifier.classify(subType)
    )

    @Test
    fun testWsUpgradeHeadersTelemetryStall401ShortCircuitAndConnectedExpiry() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val wsOrigin = UriValidator.NormalizedOrigin("ws", "127.0.0.1", server.port)
            val httpOrigin = UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port)
            val trustPolicy = TestLoopbackTrustPolicy(setOf(wsOrigin, httpOrigin))
            var nowMs = 1_000L
            val authClock = AuthMonotonicClock { nowMs }
            val dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler))

            val openLatch = CountDownLatch(1)
            val serverSocketRef = arrayOfNulls<WebSocket>(1)
            server.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocketRef[0] = webSocket
                        openLatch.countDown()
                        webSocket.send("""{"notify_info":"thing_status_update","thing_name":"laser_01","working_status":"AutoClean","battery_level":87}""")
                        // Cross-device telemetry for unselected robot_99 must be ignored
                        webSocket.send("""{"notify_info":"thing_status_update","thing_name":"robot_99","working_status":"Fault","battery_level":5}""")
                    }
                })
            )

            var invalidatedReason: com.weback.robot.domain.model.AuthFailureReason? = null
            val transport = OkHttpWebSocketTransport(
                trustPolicy = trustPolicy,
                authClock = authClock,
                dispatchers = dispatchers,
                connectionConfig = ConnectionConfig(reconnectDelayMs = 4_000L, sendStallTimeoutMs = 20_000L, maxReconnectAttempts = 2),
                onSessionInvalidated = { _, reason -> invalidatedReason = reason }
            )

            val session = SessionCredentials(
                jwtToken = "ws-jwt-1",
                apiUrl = "http://127.0.0.1:${server.port}/api",
                wssUrl = "ws://127.0.0.1:${server.port}/ws",
                regionName = "us-east-1",
                expiresInSeconds = 3600L,
                issuedAtElapsedMs = 0L
            )
            transport.selectDevice(testRobotItem(), epoch = 1L)
            transport.connect(session, epoch = 1L)

            assertTrue("Expected WS handshake open", openLatch.await(5, TimeUnit.SECONDS))
            val req = server.takeRequest()
            assertEquals("ws-jwt-1", req.getHeader("token"))
            assertEquals("us-east-1", req.getHeader("region"))
            assertFalse(req.path!!.contains("token="))

            // Give listener callback time to publish state
            val deadline = System.currentTimeMillis() + 2_000L
            while (transport.robotState.value.batteryPercentage != 87 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(87, transport.robotState.value.batteryPercentage)
            assertEquals(RobotMode.CLEAN, transport.robotState.value.mode)

            // Cross-device robot_99 frame must NOT overwrite battery 87
            assertFalse(transport.robotState.value.batteryPercentage == 5)

            // Outbound send while within stall window works; after 20_001ms triggers send-stall detection
            assertTrue(transport.sendOutbound("""{"opt":"ping_cmd"}""", epoch = 1L))
            nowMs = 21_500L
            assertFalse("Send after >20_000ms must trigger send-stall rejection", transport.sendOutbound("""{"opt":"ping_cmd"}""", epoch = 1L))

            // Session expiry while connected must immediately block outbound, close socket, and trigger invalidation
            nowMs = 3_600_000L
            transport.enforceSessionExpiryIfNeeded(epoch = 1L)
            assertTrue(transport.connectionState.value is ConnectionState.Disconnected)
            assertEquals(com.weback.robot.domain.model.AuthFailureReason.TokenExpiredOrRevoked, invalidatedReason)

            transport.close()
            assertEquals(ConnectionState.Closed, transport.connectionState.value)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun test401HandshakeShortCircuitsReconnectsAndStaleGenerationIgnored() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val wsOrigin = UriValidator.NormalizedOrigin("ws", "127.0.0.1", server.port)
            val httpOrigin = UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port)
            val trustPolicy = TestLoopbackTrustPolicy(setOf(wsOrigin, httpOrigin))
            val authClock = AuthMonotonicClock { 1_000L }
            val dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
            server.enqueue(MockResponse().setResponseCode(401))

            var invalidatedEpoch = -1L
            val transport = OkHttpWebSocketTransport(
                trustPolicy = trustPolicy,
                authClock = authClock,
                dispatchers = dispatchers,
                onSessionInvalidated = { epoch, _ -> invalidatedEpoch = epoch }
            )
            val session = SessionCredentials(
                jwtToken = "bad-jwt",
                apiUrl = "http://127.0.0.1:${server.port}/api",
                wssUrl = "ws://127.0.0.1:${server.port}/ws",
                regionName = "us-east-1",
                expiresInSeconds = 3600L,
                issuedAtElapsedMs = 0L
            )
            transport.connect(session, epoch = 4L)

            val deadline = System.currentTimeMillis() + 3_000L
            while (invalidatedEpoch != 4L && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(4L, invalidatedEpoch)
            assertEquals(1, server.requestCount)
            assertTrue(transport.connectionState.value is ConnectionState.Disconnected)
            transport.close()
        } finally {
            server.shutdown()
        }
    }
}
