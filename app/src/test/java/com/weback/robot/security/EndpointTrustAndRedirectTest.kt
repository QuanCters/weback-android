package com.weback.robot.security

import com.weback.robot.domain.model.AuthFailureReason
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException

class EndpointTrustAndRedirectTest {

    @Test
    fun testProductionPolicyDisabledAndNegativeUriValidation() {
        val disabledProd = ProductionEndpointTrustPolicy
        val prodDecision = disabledProd.authorize("https://user.grit-cloud.com/prod/oauth")
        assertTrue(prodDecision is EndpointTrustDecision.Untrusted)
        assertEquals(AuthFailureReason.UntrustedEndpoint, (prodDecision as EndpointTrustDecision.Untrusted).reason)
        assertFalse(disabledProd.validateResolvedAddresses("127.0.0.1", listOf(InetAddress.getByName("127.0.0.1"))))

        // UriValidator rejects downgrades, userinfo, credential query params, fragments
        assertTrue(UriValidator.validateEndpoint("http://user.grit-cloud.com/prod/oauth") is UriValidator.UriValidationResult.Rejected)
        assertTrue(UriValidator.validateEndpoint("ws://127.0.0.1:8080/ws", allowLoopbackCleartext = false) is UriValidator.UriValidationResult.Rejected)
        assertTrue(UriValidator.validateEndpoint("https://user:secret@user.grit-cloud.com/api") is UriValidator.UriValidationResult.Rejected)
        assertTrue(UriValidator.validateEndpoint("https://user.grit-cloud.com/api?token=abc") is UriValidator.UriValidationResult.Rejected)
        assertTrue(UriValidator.validateEndpoint("https://user.grit-cloud.com/api?pwd=secret") is UriValidator.UriValidationResult.Rejected)
        assertTrue(UriValidator.validateEndpoint("https://user.grit-cloud.com/api#access_token=xyz") is UriValidator.UriValidationResult.Rejected)
    }

    @Test
    fun testLoopbackTrustPolicyRejectsUntrustedOriginsAndPublicDns() {
        val origin = UriValidator.NormalizedOrigin("http", "127.0.0.1", 8443)
        val policy = TestLoopbackTrustPolicy(allowedOrigins = setOf(origin))

        assertTrue(policy.authorize("http://127.0.0.1:8443/api") is EndpointTrustDecision.Trusted)
        assertTrue(policy.authorize("https://untrusted.example.invalid/api") is EndpointTrustDecision.Untrusted)
        assertTrue(policy.authorize("http://127.0.0.1:9999/api") is EndpointTrustDecision.Untrusted)
        assertEquals(Proxy.NO_PROXY, policy.proxy())

        // Must reject non-loopback public IP addresses
        assertFalse(policy.validateResolvedAddresses("127.0.0.1", listOf(InetAddress.getByName("8.8.8.8"))))

        // Client built via OkHttpSecurityGuard must reject external host DNS lookup before network I/O
        val client = OkHttpSecurityGuard.buildHardenedClient(policy)
        var threwUnknownHost = false
        try {
            client.dns.lookup("example.com")
        } catch (_: UnknownHostException) {
            threwUnknownHost = true
        }
        assertTrue("Expected external DNS lookup to fail closed", threwUnknownHost)
    }

    @Test
    fun testHardenedOkHttpClientDisablesAutomaticRedirectsOn3xx() {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "http://127.0.0.1:${server.port}/redirected")
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("SHOULD_NOT_BE_REACHED"))

            val origin = UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port)
            val policy = TestLoopbackTrustPolicy(setOf(origin))
            val client: OkHttpClient = OkHttpSecurityGuard.buildHardenedClient(policy)
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)

            val response = client.newCall(
                Request.Builder()
                    .url(server.url("/auth"))
                    .addHeader("token", "synthetic-jwt")
                    .addHeader("region", "us-east-1")
                    .build()
            ).execute()

            assertEquals(302, response.code)
            assertEquals(1, server.requestCount)
            response.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testMainSourcesContainNoGlobalSslMutationOrTrustAllTlsOrEncryptedPrefs() {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        val mainDir = roots.firstOrNull { it.isDirectory } ?: error("Cannot locate src/main/java")
        val forbidden = listOf(
            "setDefaultSSLSocketFactory",
            "SSLContext.setDefault",
            "EncryptedSharedPreferences",
            "MasterKey",
            "FirebaseAnalytics",
            "com.yugong",
            "X509TrustManager",
            "HostnameVerifier",
            "SecretKeySpec",
            "getSharedPreferences"
        )
        val violations = mutableListOf<String>()
        mainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("//") && !trimmed.startsWith("*")) {
                    forbidden.forEach { token ->
                        if (trimmed.contains(token)) {
                            violations.add("${file.name}:${idx + 1}: $token")
                        }
                    }
                }
            }
        }
        assertTrue("Prohibited security patterns in src/main: $violations", violations.isEmpty())
    }
}
