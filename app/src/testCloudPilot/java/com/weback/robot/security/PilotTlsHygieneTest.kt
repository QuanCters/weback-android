package com.weback.robot.security

import com.weback.robot.domain.pilot.PilotEndpointConfig
import okhttp3.Dns
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.net.InetAddress
import java.net.Proxy
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocketFactory

class PilotTlsHygieneTest {

    private fun buildSelfSignedServerSocketFactory(): SSLSocketFactory {
        val ksFile = File.createTempFile("pilot-selfsigned-", ".p12")
        ksFile.delete()
        try {
            val password = "changeit".toCharArray()
            val proc = ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "localhost",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-storetype", "PKCS12",
                "-keystore", ksFile.absolutePath,
                "-storepass", String(password),
                "-keypass", String(password),
                "-dname", "CN=127.0.0.1",
                "-ext", "SAN=IP:127.0.0.1"
            ).redirectErrorStream(true).start()
            val exit = proc.waitFor()
            check(exit == 0) { "keytool failed: ${proc.inputStream.bufferedReader().readText()}" }

            val ks = KeyStore.getInstance("PKCS12")
            FileInputStream(ksFile).use { ks.load(it, password) }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, password)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(kmf.keyManagers, null, null)
            return ctx.socketFactory
        } finally {
            ksFile.delete()
        }
    }

    @Test
    fun testPilotClientEnforcesTimeoutNoRetryNoRedirectNoProxyAndFailsOnUntrustedTls() {
        val server = MockWebServer()
        server.useHttps(buildSelfSignedServerSocketFactory(), false)
        server.start(InetAddress.getByName("127.0.0.1"), 0)

        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":1}"""))
            val loopbackOrigin = "https://127.0.0.1:${server.port}"
            val policy = AuthorizedPilotEndpointTrustPolicy(
                endpointConfig = PilotEndpointConfig(
                    approvedAuthOrigin = loopbackOrigin,
                    approvedAuthPath = "/prod/oauth",
                    pinnedApiOrigin = loopbackOrigin
                ),
                allowedAuthOrigins = setOf(loopbackOrigin),
                allowLoopbackForHermeticTests = true
            )
            val loopbackDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
            }
            val client = PilotClientFactory.buildPilotOkHttpClient(
                trustPolicy = policy,
                baseDns = loopbackDns
            )

            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
            assertFalse(client.retryOnConnectionFailure)
            assertEquals(Proxy.NO_PROXY, client.proxy)
            assertEquals(15_000, client.callTimeoutMillis)

            // Untrusted self-signed cert must fail standard platform TLS verification
            var tlsRejected = false
            try {
                client.newCall(
                    Request.Builder().url("https://127.0.0.1:${server.port}/prod/oauth").build()
                ).execute()
            } catch (_: SSLHandshakeException) {
                tlsRejected = true
            }
            assertTrue("Expected untrusted self-signed TLS cert to fail closed", tlsRejected)
        } finally {
            server.shutdown()
        }
    }
}
