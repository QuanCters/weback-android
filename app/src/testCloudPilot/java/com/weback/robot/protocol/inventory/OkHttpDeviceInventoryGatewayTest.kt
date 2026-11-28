package com.weback.robot.protocol.inventory

import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.domain.model.ConnectivityError
import com.weback.robot.domain.model.ControlCompatibility
import com.weback.robot.domain.model.InventoryState
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.SessionCredentials
import com.weback.robot.security.TestLoopbackTrustPolicy
import com.weback.robot.security.UriValidator
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class OkHttpDeviceInventoryGatewayTest {

    @Test
    fun testFetchInventoryHeadersPayloadEmptyAndBoundaryHydration() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val origin = UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port)
            val trustPolicy = TestLoopbackTrustPolicy(setOf(origin))
            var nowMs = 1_000L
            val clock = AuthMonotonicClock { nowMs }
            val session = SessionCredentials(
                jwtToken = "jwt-inv-123",
                apiUrl = "http://127.0.0.1:${server.port}/api",
                wssUrl = "ws://127.0.0.1:${server.port}/ws",
                regionName = "eu-central-1",
                expiresInSeconds = 3600L,
                issuedAtElapsedMs = 0L
            )

            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "code": 1,
                      "data": {
                        "thing_list": [
                          {
                            "thing_name": "robot_01",
                            "sub_type": "xs-x8-eu",
                            "thing_nickname": "Living Room",
                            "is_admin": true,
                            "thing_status": {
                              "connected": true,
                              "working_status": "Pause",
                              "battery_level": 140
                            }
                          },
                          {
                            "thing_name": "plug_01",
                            "sub_type": "sock-pw-1",
                            "thing_nickname": "Wall Plug",
                            "is_admin": false,
                            "thing_status": {
                              "battery_level": -10
                            }
                          }
                        ]
                      }
                    }
                    """.trimIndent()
                )
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"thing_list":[]}}"""))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://127.0.0.1:${server.port}/evil"))

            val gateway = OkHttpDeviceInventoryGateway(trustPolicy = trustPolicy, authClock = clock)
            val ok = gateway.fetchThings(session) as InventoryFetchResult.Success
            assertEquals(2, ok.items.size)
            assertEquals("robot_01", ok.items[0].thingName)
            assertEquals(ControlCompatibility.MvpSupportedShadowWs, ok.items[0].capability.compatibility)
            assertEquals(100, ok.items[0].initialState?.batteryPercentage)
            assertEquals(RobotMode.PAUSED, ok.items[0].initialState?.mode)
            assertEquals(0, ok.items[1].initialState?.batteryPercentage)
            assertFalse(ok.items[1].capability.compatibility.isMvpControlSupported)

            val req = server.takeRequest()
            assertEquals("jwt-inv-123", req.getHeader("token"))
            assertEquals("eu-central-1", req.getHeader("region"))
            val reqJson = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
            assertEquals("user_thing_list_get", reqJson["opt"]!!.jsonPrimitive.content)

            val emptyRes = gateway.fetchThings(session) as InventoryFetchResult.Success
            assertTrue(emptyRes.items.isEmpty())

            val redirectRes = gateway.fetchThings(session) as InventoryFetchResult.Failure
            assertEquals(ConnectivityError.RedirectRejected, redirectRes.error)

            // Pre-send expiry check must block before HTTP request is dispatched
            nowMs = 3_600_000L
            val expiredRes = gateway.fetchThings(session) as InventoryFetchResult.Failure
            assertEquals(ConnectivityError.TokenExpired, expiredRes.error)
            assertEquals(3, server.requestCount)

            // Post-response expiry check: token is valid before send, expires while HTTP response is in flight
            nowMs = 3_599_000L
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"thing_list":[]}}""").apply {
                    setBodyDelay(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            )
            val tickingClock = object : AuthMonotonicClock {
                private var calls = 0
                override fun elapsedRealtimeMs(): Long {
                    calls++
                    return if (calls <= 2) 3_599_000L else 3_600_000L
                }
            }
            val inFlightGateway = OkHttpDeviceInventoryGateway(trustPolicy = trustPolicy, authClock = tickingClock)
            val inFlightExpired = inFlightGateway.fetchThings(session) as InventoryFetchResult.Failure
            assertEquals(ConnectivityError.TokenExpired, inFlightExpired.error)
        } finally {
            server.shutdown()
        }
    }

    private fun assertFalse(condition: Boolean) = org.junit.Assert.assertFalse(condition)
}
