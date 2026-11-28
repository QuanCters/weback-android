package com.weback.robot.app.local

import com.weback.robot.domain.map.HoslamFrameKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDiagnosticLoggerTest {

    @Test
    fun diagnosticEventStructurallyExcludesStringsThrowablesAndByteArrays() {
        val fields = LocalDiagnosticEvent::class.java.declaredFields
        assertTrue(fields.isNotEmpty())
        for (field in fields) {
            assertFalse(
                "Prohibited free-form String field '${field.name}' on LocalDiagnosticEvent",
                field.type == String::class.java
            )
            assertFalse(
                "Prohibited Throwable field '${field.name}' on LocalDiagnosticEvent",
                Throwable::class.java.isAssignableFrom(field.type)
            )
            assertFalse(
                "Prohibited ByteArray field '${field.name}' on LocalDiagnosticEvent",
                field.type == ByteArray::class.java
            )
        }
    }

    @Test
    fun loggerEnforcesBoundedRingBufferAndMaxLineLength() {
        val logger = LocalDiagnosticLogger()
        for (idx in 1..260) {
            logger.log(
                LocalDiagnosticEvent(
                    category = LocalDiagnosticCategory.MAP_DECODED,
                    connectionState = LocalMapConnectionState.STREAMING,
                    frameKind = HoslamFrameKind.MAP,
                    frameStartOffset = idx.toLong() * 44L,
                    width = 278,
                    height = 214,
                    mapCount = idx.toLong(),
                    auxCount = 0L,
                    trajCount = 0L,
                    generationId = 1L,
                    elapsedSessionMs = idx.toLong() * 100L
                )
            )
        }

        val entries = logger.snapshotEntries()
        assertEquals(LocalDiagnosticLogger.MAX_ENTRIES, entries.size)
        assertTrue("Oldest entry should be evicted", entries.first().contains("map=61"))
        assertTrue("Latest entry should be retained", entries.last().contains("map=260"))
        assertTrue(entries.all { it.length <= LocalDiagnosticLogger.MAX_ENTRY_CHARS })
        assertTrue(entries.none { it.contains("192.168.") || it.contains("Bearer") })
    }
}
