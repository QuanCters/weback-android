package com.weback.robot.protocol.ws

import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.domain.device.DeviceCapabilityClassifier
import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.model.ConnectivityError
import com.weback.robot.domain.model.DeviceInventoryItem
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.SessionCredentials
import com.weback.robot.security.TestLoopbackTrustPolicy
import com.weback.robot.security.UriValidator
import com.weback.robot.testutils.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class WebSocketGenerationIsolationTest {

    @Test
    fun testSupersededGenerationCallbacksAndReconnectTimersAreIgnored() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val wsOrigin = UriValidator.NormalizedOrigin("ws", "127.0.0.1", server.port)
            val httpOrigin = UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port)
            val trustPolicy = TestLoopbackTrustPolicy(setOf(wsOrigin, httpOrigin))
            val dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
            val transport = OkHttpWebSocketTransport(
                trustPolicy = trustPolicy,
                authClock = AuthMonotonicClock { 1_000L },
                dispatchers = dispatchers
            )
            val session = SessionCredentials(
                jwtToken = "jwt-gen-test",
                apiUrl = "http://127.0.0.1:${server.port}/api",
                wssUrl = "ws://127.0.0.1:${server.port}/ws",
                regionName = "us-east-1",
                expiresInSeconds = 3600L,
                issuedAtElapsedMs = 0L
            )
            val robot = DeviceInventoryItem(
                thingName = "laser_gen_1",
                subType = "xs-x8-eu",
                nickname = "Gen Robot",
                isAdmin = true,
                capability = DeviceCapabilityClassifier.classify("xs-x8-eu")
            )
            transport.selectDevice(robot, epoch = 2L)
            transport.connect(session, epoch = 2L)
            val activeGen = transport.currentGeneration()

            // Invalidate epoch 2 -> increments epoch/generation and drops connection
            transport.invalidateForEpoch(expectedEpoch = 2L, reason = ConnectivityError.Unauthenticated)
            assertTrue(transport.connectionState.value is ConnectionState.Disconnected)

            // Superseded telemetry callback from old (epoch=2, generation=activeGen) must be ignored
            transport.onInboundFrameForTest(
                frameEpoch = 2L,
                frameGeneration = activeGen,
                rawText = """{"notify_info":"thing_status_update","thing_name":"laser_gen_1","working_status":"AutoClean","battery_level":42}"""
            )
            assertEquals(100, transport.robotState.value.batteryPercentage)
            assertEquals(RobotMode.DOCK, transport.robotState.value.mode)

            transport.close()
        } finally {
            server.shutdown()
        }
    }
}
