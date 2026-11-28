package com.weback.robot.protocol.auth

import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.domain.model.AccountRegionType
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.security.EndpointTrustPolicy
import com.weback.robot.security.InMemoryDiagnosticLogger
import com.weback.robot.security.ProductionEndpointTrustPolicy
import com.weback.robot.security.TestLoopbackTrustPolicy
import com.weback.robot.security.UriValidator
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

class OkHttpAuthGatewayTest {

    @Test
    fun testRegionRouterMapsCnGlobalAndRejectsMalformed() {
        val router = RegionEndpointRouter()
        val cn1 = router.resolve("+86") as RegionResolution.Resolved
        assertEquals("https://user.grit-cloud.cn/prod/oauth", cn1.endpointUrl)
        assertEquals(AccountRegionType.CN, cn1.regionType)

        val cn2 = router.resolve(" 0086 ") as RegionResolution.Resolved
        assertEquals("https://user.grit-cloud.cn/prod/oauth", cn2.endpointUrl)

        val global = router.resolve("+1") as RegionResolution.Resolved
        assertEquals("https://user.grit-cloud.com/prod/oauth", global.endpointUrl)
        assertEquals(AccountRegionType.GLOBAL, global.regionType)

        assertTrue(router.resolve("") is RegionResolution.Rejected)
        assertTrue(router.resolve("us-invalid") is RegionResolution.Rejected)
    }

    @Test
    fun testProductionTrustRemainsDisabledByDefaultInAuthGateway() = runTest {
        val gateway = OkHttpAuthGateway(
            router = RegionEndpointRouter(),
            trustPolicy = ProductionEndpointTrustPolicy,
            envelopeConfig = AuthEnvelopeConfig(protocolApiVersion = "6.1.7", languageTag = "en"),
            authClock = AuthMonotonicClock { 1_000L }
        )
        val creds = AuthCredentials("+1", "user@example.invalid", "pw".toCharArray())
        val result = gateway.signIn(creds)
        assertTrue(result is AuthResult.Failure)
        assertEquals(AuthFailureReason.UntrustedEndpoint, (result as AuthResult.Failure).reason)
    }

    @Test
    fun testMockWebServerLoginEnvelopeContractAndSafeLogging() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val baseOrigin = UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port)
            val loginUrl = "http://127.0.0.1:${server.port}/prod/oauth"
            val apiUrl = "http://127.0.0.1:${server.port}/api"
            val wssUrl = "ws://127.0.0.1:${server.port}/ws"
            val wsOrigin = UriValidator.NormalizedOrigin("ws", "127.0.0.1", server.port)

            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "code": 1,
                      "data": {
                        "api_url": "$apiUrl",
                        "wss_url": "$wssUrl",
                        "region_name": "us-east-1",
                        "jwt_token": "synthetic-jwt-998877",
                        "expired_time": 3600
                      }
                    }
                    """.trimIndent()
                )
            )

            val trustPolicy = TestLoopbackTrustPolicy(setOf(baseOrigin, wsOrigin))
            val logSink = mutableListOf<String>()
            val gateway = OkHttpAuthGateway(
                router = RegionEndpointRouter(globalOverrideUrl = loginUrl),
                trustPolicy = trustPolicy,
                envelopeConfig = AuthEnvelopeConfig(protocolApiVersion = "6.1.7", languageTag = "en"),
                authClock = AuthMonotonicClock { 5_000L },
                logger = InMemoryDiagnosticLogger(logSink)
            )

            val creds = AuthCredentials("+1", "synthetic@example.invalid", "SecretPass!42".toCharArray())
            val res = gateway.signIn(creds)
            assertTrue("Expected AuthResult.Success, got $res", res is AuthResult.Success)
            val session = (res as AuthResult.Success).session
            assertEquals("synthetic-jwt-998877", session.jwtToken)
            assertEquals(3600L, session.expiresInSeconds)
            assertEquals(5_000L, session.issuedAtElapsedMs)

            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/prod/oauth", req.path)
            val bodyJson = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
            val header = bodyJson["header"]!!.jsonObject
            val payload = bodyJson["payload"]!!.jsonObject
            assertEquals("6.1.7", header["api_version"]!!.jsonPrimitive.content)
            assertFalse("Must never leak clean-room app version as protocolApiVersion", header["api_version"]!!.jsonPrimitive.content == "0.1.0-alpha")
            assertEquals("WeBack", header["app_name"]!!.jsonPrimitive.content)
            assertEquals("yugong_app", header["client_id"]!!.jsonPrimitive.content)
            assertEquals("en", header["language"]!!.jsonPrimitive.content)
            assertEquals("login", payload["opt"]!!.jsonPrimitive.content)
            assertEquals("SecretPass!42", payload["pwd"]!!.jsonPrimitive.content)

            // Normal diagnostic logs must not contain password, jwt, or body
            val allLogs = logSink.joinToString("\n")
            assertFalse(allLogs.contains("SecretPass!42"))
            assertFalse(allLogs.contains("synthetic-jwt-998877"))
        } finally {
            server.shutdown()
        }
    }
}
