package com.weback.robot.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {

    @Test
    fun testRedactsHeadersOpaqueTokensJwtsNestedAndMalformedJsonUrlEncodedAndExceptions() {
        val sampleJwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyMSJ9.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        val samples = listOf(
            "Token: opaque-secret-value-123\nAUTHORIZATION: Bearer topSecretBearer999",
            "Set-Cookie: session_id=cookieSecret777; HttpOnly\nCookie: sid=anotherCookie888",
            """{"header":{"client_id":"yugong_app"},"payload":{"opt":"login","pwd":"RawPassword!23"},"data":{"jwt_token":"$sampleJwt","nested":{"refresh_token":"rt-998877"}}}""",
            // Malformed/truncated JSON still containing secret key/value
            """{"jwt_token":"truncated-secret-token-abc""",
            "https://user_alice:Pass%40123@example.invalid/auth?password%3DmySecretPwd%26token%3DurlTok445",
            "jwt_token=$sampleJwt&pwd=plainQueryPass"
        )

        val leakedTokens = listOf(
            "opaque-secret-value-123",
            "topSecretBearer999",
            "cookieSecret777",
            "anotherCookie888",
            "RawPassword!23",
            sampleJwt,
            "rt-998877",
            "truncated-secret-token-abc",
            "Pass%40123",
            "mySecretPwd",
            "urlTok445",
            "plainQueryPass"
        )

        for (raw in samples) {
            val redacted = SecretRedactor.redact(raw)
            for (secret in leakedTokens) {
                assertFalse("Leaked secret '$secret' in: $redacted", redacted.contains(secret))
            }
            assertTrue("Expected redaction placeholder in: $redacted", redacted.contains("[REDACTED]"))
        }

        val exception = IllegalStateException("Auth failed for token=inlineSecret999&pwd=badPwd", RuntimeException("Authorization: Bearer nestedExcJwt"))
        val scrubbed = SecretRedactor.redactThrowable(exception)
        assertFalse(scrubbed.contains("inlineSecret999"))
        assertFalse(scrubbed.contains("badPwd"))
        assertFalse(scrubbed.contains("nestedExcJwt"))
        assertTrue(scrubbed.contains("[REDACTED]"))
    }
}
