package com.weback.robot.protocol.auth

import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.security.TestLoopbackTrustPolicy
import com.weback.robot.security.UriValidator
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class OkHttpAuthGatewayNegativeTest {

    @Test
    fun testRejects401_403_500_302_MalformedAndUntrustedUrls() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val baseOrigin = UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port)
            val wsOrigin = UriValidator.NormalizedOrigin("ws", "127.0.0.1", server.port)
            val trustPolicy = TestLoopbackTrustPolicy(setOf(baseOrigin, wsOrigin))
            val loginUrl = "http://127.0.0.1:${server.port}/prod/oauth"
            val gateway = OkHttpAuthGateway(
                router = RegionEndpointRouter(globalOverrideUrl = loginUrl),
                trustPolicy = trustPolicy,
                envelopeConfig = AuthEnvelopeConfig("6.1.7"),
                authClock = AuthMonotonicClock { 1_000L }
            )

            val responses = listOf(
                MockResponse().setResponseCode(401),
                MockResponse().setResponseCode(403),
                MockResponse().setResponseCode(500),
                MockResponse().setResponseCode(302).addHeader("Location", "http://127.0.0.1:${server.port}/other"),
                MockResponse().setResponseCode(200).setBody("not-json"),
                MockResponse().setResponseCode(200).setBody("""{"code":0,"msg":"bad credentials"}"""),
                MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"api_url":"http://127.0.0.1:${server.port}/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"","expired_time":3600}}"""),
                MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"api_url":"https://untrusted.invalid/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"tok","expired_time":3600}}"""),
                MockResponse().setResponseCode(200).setBody("""{"code":1,"data":{"api_url":"http://127.0.0.1:${server.port}/api","wss_url":"ws://127.0.0.1:${server.port}/ws","region_name":"us","jwt_token":"tok","expired_time":0}}""")
            )
            responses.forEach { server.enqueue(it) }

            val results = responses.map {
                gateway.signIn(AuthCredentials("+1", "u@example.invalid", "pw".toCharArray()))
            }

            assertTrue(results.all { it is AuthResult.Failure })
            val reasons = results.map { (it as AuthResult.Failure).reason }
            assertEquals(AuthFailureReason.InvalidCredentials, reasons[0])
            assertEquals(AuthFailureReason.InvalidCredentials, reasons[1])
            assertTrue(reasons[2] is AuthFailureReason.ServerRejected)
            assertEquals(AuthFailureReason.RedirectRejected, reasons[3])
            assertEquals(AuthFailureReason.MalformedResponse, reasons[4])
            assertTrue(reasons[5] is AuthFailureReason.ServerRejected)
            assertEquals(AuthFailureReason.MalformedResponse, reasons[6])
            assertEquals(AuthFailureReason.UntrustedEndpoint, reasons[7])
            assertEquals(AuthFailureReason.MalformedResponse, reasons[8])
        } finally {
            server.shutdown()
        }
    }
}
