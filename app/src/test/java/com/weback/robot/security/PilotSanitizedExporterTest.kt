package com.weback.robot.security

import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotEmergencyDisconnectResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PilotSanitizedExporterTest {

    @Test
    fun testExportsAllowlistedDiagnosticsAndScrubsAllSecretsAndPii() {
        val logBuffer = mutableListOf<String>()
        val logger = InMemoryDiagnosticLogger(logBuffer)
        logger.log(
            DiagnosticEvent(
                category = "AUTH_RESPONSE",
                opt = "login",
                httpStatus = 200,
                latencyMs = 42L,
                safeHostPath = "/prod/oauth?token=leak_token_123",
                sessionEpoch = 2L,
                errorCategory = "jwt_token\":\"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig\""
            )
        )
        logBuffer.add("""token: super-secret-header-token""")
        logBuffer.add("""{"pwd":"my-plain-password","thing_name":"secret_laser_thing_99"}""")

        val exporter = PilotSanitizedExporter()
        val report = exporter.exportReport(
            authorizationId = "FCT004-PILOT-20261125-01",
            modeLabel = "MOCK_HERMETIC_OFFLINE",
            rawEvents = logBuffer,
            observedApiOrigin = "https://api-us.grit-cloud.com:443",
            observedWssOrigin = "wss://wss-us.grit-cloud.com:443",
            lastAbortReason = PilotAbortReason.UserEmergencyDisconnect,
            disconnectResult = PilotEmergencyDisconnectResult(
                networkRevokedImmediately = true,
                storageCleared = false,
                incompleteStorageReason = "KEY_DELETE_DENIED",
                restoredOfflineSimulator = true,
                serverSideTokenRevocationClaimed = false
            ),
            sensitiveThingNames = setOf("secret_laser_thing_99"),
            sensitiveAliases = setOf("pilot-user@example.invalid")
        )

        assertTrue(report.contains("FCT004-PILOT-20261125-01"))
        assertTrue(report.contains("MOCK_HERMETIC_OFFLINE"))
        assertTrue(report.contains("server_side_token_revocation_claimed=false"))
        assertTrue(report.contains("KEY_DELETE_DENIED"))
        assertFalse(report.contains("leak_token_123"))
        assertFalse(report.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(report.contains("super-secret-header-token"))
        assertFalse(report.contains("my-plain-password"))
        assertFalse(report.contains("secret_laser_thing_99"))
        assertFalse(report.contains("pilot-user@example.invalid"))
    }
}
