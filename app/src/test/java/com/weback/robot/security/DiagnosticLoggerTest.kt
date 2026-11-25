package com.weback.robot.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLoggerTest {

    @Test
    fun testAllowlistedStructuredDiagnosticLoggerSanitizesOutput() {
        val captured = mutableListOf<String>()
        val logger = InMemoryDiagnosticLogger(captured)

        logger.log(
            DiagnosticEvent(
                category = "AUTH_REQUEST",
                opt = "login",
                httpStatus = 401,
                latencyMs = 42L,
                safeHostPath = "127.0.0.1:8080/prod/oauth?token=shouldStrip&pwd=abc",
                sessionEpoch = 3L,
                connectionGeneration = 1L,
                errorCategory = "TOKEN_EXPIRED"
            )
        )

        assertEquals(1, captured.size)
        val line = captured.single()
        assertTrue(line.contains("AUTH_REQUEST"))
        assertTrue(line.contains("opt=login"))
        assertTrue(line.contains("http=401"))
        assertTrue(line.contains("epoch=3"))
        assertFalse(line.contains("shouldStrip"))
        assertFalse(line.contains("pwd=abc"))
    }
}
