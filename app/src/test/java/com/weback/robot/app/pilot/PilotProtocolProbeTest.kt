package com.weback.robot.app.pilot

import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotAccountScope
import com.weback.robot.domain.pilot.PilotAccountScopeType
import com.weback.robot.domain.pilot.PilotAuthorizationContext
import com.weback.robot.domain.pilot.PilotEndpointConfig
import com.weback.robot.domain.pilot.PilotExternalApproval
import com.weback.robot.domain.pilot.PilotModeState
import com.weback.robot.domain.pilot.PilotOperation
import com.weback.robot.domain.pilot.PilotRequestBudgets
import com.weback.robot.domain.pilot.PilotTargetDevice
import com.weback.robot.protocol.auth.AuthEnvelopeConfig
import com.weback.robot.protocol.auth.OkHttpAuthGateway
import com.weback.robot.protocol.auth.RegionEndpointRouter
import com.weback.robot.protocol.pilot.PilotAuthProbeOutcome
import com.weback.robot.protocol.pilot.PilotCapabilityRestrictedAuthGateway
import com.weback.robot.security.DiagnosticLogger
import com.weback.robot.security.InMemoryDiagnosticLogger
import com.weback.robot.security.TestLoopbackTrustPolicy
import com.weback.robot.security.UriValidator
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PilotProtocolProbeTest {

    @Test
    fun testCaptchaAbortsWithoutAutomationAndUtf8RejectionAbortsWithoutMd5Fallback() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val logs = mutableListOf<String>()
            val logger: DiagnosticLogger = InMemoryDiagnosticLogger(logs)
            val origin = UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port)
            val policy = TestLoopbackTrustPolicy(setOf(origin))
            val router = RegionEndpointRouter(
                globalOverrideUrl = "http://127.0.0.1:${server.port}/prod/oauth"
            )
            val pilotAuth = PilotCapabilityRestrictedAuthGateway(
                router = router,
                trustPolicy = policy,
                authClock = AuthMonotonicClock { 1_000L },
                logger = logger
            )

            // 1. CAPTCHA challenge response -> ChallengeRequired, zero retry, zero CAPTCHA automation
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"code":4001,"msg":"get_image_captcha_required","captcha":true}""")
            )
            val captchaRes = pilotAuth.probeLogin(
                AuthCredentials("+1", "pilot@example.invalid", "SuperSecret123!".toCharArray())
            )
            assertTrue(captchaRes is PilotAuthProbeOutcome.Failed)
            assertEquals(AuthFailureReason.ChallengeRequired, (captchaRes as PilotAuthProbeOutcome.Failed).reason)
            assertEquals(1, server.requestCount)

            // 2. Password rejection -> ProtocolMismatchAbort/AuthRejected, ZERO MD5 fallback request
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"code":-1,"msg":"invalid_password_format"}""")
            )
            val rejectRes = pilotAuth.probeLogin(
                AuthCredentials("+1", "pilot@example.invalid", "SuperSecret123!".toCharArray())
            )
            assertTrue(rejectRes is PilotAuthProbeOutcome.Failed)
            assertEquals(2, server.requestCount)
            val req2Body = server.takeRequest().let { server.takeRequest().body.readUtf8() }
            assertTrue("Expected raw UTF-8 payload, not MD5 fallback", req2Body.contains("SuperSecret123!"))
            assertFalse("Must never emit raw request/response bodies to diagnostics", logs.any { it.contains("SuperSecret123!") || it.contains("invalid_password_format") })
        } finally {
            server.shutdown()
        }
    }
}
