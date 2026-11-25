package com.weback.robot.domain

import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.domain.model.SessionCredentials
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthModelsTest {

    @Test
    fun testCredentialModelsMaskSecretsInToString() {
        val secretChars = "SuperSecretPass!99".toCharArray()
        val authCreds = AuthCredentials(
            regionCallingCode = "+1",
            account = "synthetic-user@example.invalid",
            secret = secretChars
        )
        val credsString = authCreds.toString()
        assertFalse(credsString.contains("SuperSecretPass!99"))
        assertFalse(credsString.contains("synthetic-user@example.invalid"))
        assertTrue(credsString.contains("[REDACTED]"))

        authCreds.wipeSecret()
        assertTrue(authCreds.copySecretChars().all { it == '\u0000' })
        secretChars.fill('\u0000')
        assertTrue(secretChars.all { it == '\u0000' })

        val session = SessionCredentials(
            jwtToken = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.secretSigValue",
            apiUrl = "http://127.0.0.1:8080/api",
            wssUrl = "ws://127.0.0.1:8080/ws",
            regionName = "us-east-1",
            expiresInSeconds = 3600L,
            issuedAtElapsedMs = 100L,
            priorElapsedMs = 0L,
            accountAliasRedacted = "s***@example.invalid",
            syntheticRefreshToken = "refresh-secret-token-value"
        )
        val sessionString = session.toString()
        assertFalse(sessionString.contains("secretSigValue"))
        assertFalse(sessionString.contains("refresh-secret-token-value"))
        assertTrue(sessionString.contains("[REDACTED]"))

        val persisted = PersistedSessionRecord(
            jwtToken = "eyJpersisted.payload.sigXYZ",
            apiUrl = "http://127.0.0.1:8080/api",
            wssUrl = "ws://127.0.0.1:8080/ws",
            regionName = "us-east-1",
            expiresInSeconds = 3600L,
            issuedAtEpochMs = 1_700_000_000_000L,
            lastSeenEpochMs = 1_700_000_000_000L,
            issuedAtElapsedRealtimeMs = 100L,
            bootGenerationId = "boot_1",
            accountAliasRedacted = "s***@example.invalid",
            syntheticRefreshToken = "refresh-persisted-secret"
        )
        val persistedString = persisted.toString()
        assertFalse(persistedString.contains("sigXYZ"))
        assertFalse(persistedString.contains("refresh-persisted-secret"))
        assertTrue(persistedString.contains("[REDACTED]"))
    }
}
