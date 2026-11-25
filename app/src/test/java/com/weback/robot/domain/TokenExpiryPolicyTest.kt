package com.weback.robot.domain

import com.weback.robot.domain.auth.LifetimeEvaluation
import com.weback.robot.domain.auth.PersistedRestoreDecision
import com.weback.robot.domain.auth.RestoreRejectionReason
import com.weback.robot.domain.auth.TokenExpiryPolicy
import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.domain.model.SessionCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenExpiryPolicyTest {

    private fun sampleSession(
        expiresInSeconds: Long = 3_600L,
        issuedAtElapsedMs: Long = 10_000L,
        priorElapsedMs: Long = 0L,
        jwtToken: String = "eyJhbGciOiJub25lIn0.eyJleHAiOjk5OTk5OTk5OTl9.sig"
    ) = SessionCredentials(
        jwtToken = jwtToken,
        apiUrl = "http://127.0.0.1:8080/api",
        wssUrl = "ws://127.0.0.1:8080/ws",
        regionName = "us-east-1",
        expiresInSeconds = expiresInSeconds,
        issuedAtElapsedMs = issuedAtElapsedMs,
        priorElapsedMs = priorElapsedMs,
        accountAliasRedacted = "u***@example.invalid"
    )

    private fun samplePersisted(
        expiresInSeconds: Long = 3_600L,
        issuedAtEpochMs: Long = 1_700_000_000_000L,
        lastSeenEpochMs: Long = 1_700_000_000_000L,
        issuedAtElapsedRealtimeMs: Long? = 10_000L,
        bootGenerationId: String? = "boot_42",
        jwtToken: String = "eyJhbGciOiJub25lIn0.eyJleHAiOjk5OTk5OTk5OTl9.sig"
    ) = PersistedSessionRecord(
        jwtToken = jwtToken,
        apiUrl = "http://127.0.0.1:8080/api",
        wssUrl = "ws://127.0.0.1:8080/ws",
        regionName = "us-east-1",
        expiresInSeconds = expiresInSeconds,
        issuedAtEpochMs = issuedAtEpochMs,
        lastSeenEpochMs = lastSeenEpochMs,
        issuedAtElapsedRealtimeMs = issuedAtElapsedRealtimeMs,
        bootGenerationId = bootGenerationId,
        accountAliasRedacted = "u***@example.invalid"
    )

    @Test
    fun testExact80PercentAnd100PercentBoundariesViaIndependentLiterals() {
        val session = sampleSession(expiresInSeconds = 3_600L, issuedAtElapsedMs = 10_000L)

        // Independent calculations: 3600 * 800 = 2_880_000 ms; 3600 * 1000 = 3_600_000 ms
        assertEquals(
            LifetimeEvaluation.VALID,
            TokenExpiryPolicy.evaluateInProcess(session, nowElapsedMs = 10_000L + 2_880_000L)
        )
        assertEquals(
            LifetimeEvaluation.PROACTIVE_REFRESH_RECOMMENDED,
            TokenExpiryPolicy.evaluateInProcess(session, nowElapsedMs = 10_000L + 2_880_001L)
        )
        assertEquals(
            LifetimeEvaluation.PROACTIVE_REFRESH_RECOMMENDED,
            TokenExpiryPolicy.evaluateInProcess(session, nowElapsedMs = 10_000L + 3_599_999L)
        )
        assertEquals(
            LifetimeEvaluation.EXPIRED_OR_INVALID,
            TokenExpiryPolicy.evaluateInProcess(session, nowElapsedMs = 10_000L + 3_600_000L)
        )
    }

    @Test
    fun testDeepSleepAndPriorElapsedAccumulation() {
        // Prior elapsed lifetime of 2_800_000 ms plus 80_001 ms in current process (> 80%)
        val restoredSession = sampleSession(
            expiresInSeconds = 3_600L,
            issuedAtElapsedMs = 5_000L,
            priorElapsedMs = 2_800_000L
        )
        assertEquals(
            LifetimeEvaluation.VALID,
            TokenExpiryPolicy.evaluateInProcess(restoredSession, nowElapsedMs = 5_000L + 80_000L)
        )
        assertEquals(
            LifetimeEvaluation.PROACTIVE_REFRESH_RECOMMENDED,
            TokenExpiryPolicy.evaluateInProcess(restoredSession, nowElapsedMs = 5_000L + 80_001L)
        )
        assertEquals(
            LifetimeEvaluation.EXPIRED_OR_INVALID,
            TokenExpiryPolicy.evaluateInProcess(restoredSession, nowElapsedMs = 5_000L + 800_000L)
        )
    }

    @Test
    fun testOverflowAndInvalidTimestampsFailClosed() {
        val maxSafeSeconds = Long.MAX_VALUE / 1000L
        val overflowingSeconds = sampleSession(expiresInSeconds = maxSafeSeconds + 1L, issuedAtElapsedMs = 0L)
        assertEquals(
            LifetimeEvaluation.EXPIRED_OR_INVALID,
            TokenExpiryPolicy.evaluateInProcess(overflowingSeconds, nowElapsedMs = 1L)
        )

        val addOverflow = sampleSession(
            expiresInSeconds = 100_000L,
            issuedAtElapsedMs = 100L,
            priorElapsedMs = Long.MAX_VALUE - 50L
        )
        assertEquals(
            LifetimeEvaluation.EXPIRED_OR_INVALID,
            TokenExpiryPolicy.evaluateInProcess(addOverflow, nowElapsedMs = 200L)
        )

        val subOverflow = sampleSession(
            expiresInSeconds = 3_600L,
            issuedAtElapsedMs = -100L
        )
        assertEquals(
            LifetimeEvaluation.EXPIRED_OR_INVALID,
            TokenExpiryPolicy.evaluateInProcess(subOverflow, nowElapsedMs = 0L)
        )

        val clockRegressed = sampleSession(expiresInSeconds = 3_600L, issuedAtElapsedMs = 10_000L)
        assertEquals(
            LifetimeEvaluation.EXPIRED_OR_INVALID,
            TokenExpiryPolicy.evaluateInProcess(clockRegressed, nowElapsedMs = 9_999L)
        )

        val nonPositiveExpiry = sampleSession(expiresInSeconds = 0L, issuedAtElapsedMs = 0L)
        assertEquals(
            LifetimeEvaluation.EXPIRED_OR_INVALID,
            TokenExpiryPolicy.evaluateInProcess(nonPositiveExpiry, nowElapsedMs = 0L)
        )
    }

    @Test
    fun testSameBootProcessRestartPreservesRemainingLifetimeAndIgnoresJwtExpClaim() {
        // Notice jwtToken payload contains exp=9999999999 (year 2286), which MUST be ignored!
        val record = samplePersisted(
            expiresInSeconds = 3_600L,
            issuedAtEpochMs = 1_700_000_000_000L,
            lastSeenEpochMs = 1_700_000_100_000L,
            issuedAtElapsedRealtimeMs = 10_000L,
            bootGenerationId = "boot_42"
        )

        val decision = TokenExpiryPolicy.evaluatePersistedRestore(
            record = record,
            nowEpochMs = 1_700_001_000_000L,
            nowElapsedRealtimeMs = 1_010_000L,
            currentBootGenerationId = "boot_42"
        )
        assertTrue(decision is PersistedRestoreDecision.Restore)
        val restored = decision as PersistedRestoreDecision.Restore
        assertEquals(1_000_000L, restored.priorElapsedMs)

        // If elapsed realtime exceeded 3600s during the same boot, fail closed even if JWT exp is far future
        val expiredDecision = TokenExpiryPolicy.evaluatePersistedRestore(
            record = record,
            nowEpochMs = 1_700_004_000_000L,
            nowElapsedRealtimeMs = 3_610_000L,
            currentBootGenerationId = "boot_42"
        )
        assertEquals(
            PersistedRestoreDecision.Reauthenticate(RestoreRejectionReason.EXPIRED),
            expiredDecision
        )
    }

    @Test
    fun testRebootUnknownBootAndClockRollbackFailClosed() {
        val base = samplePersisted()

        // Device rebooted (bootGenerationId changed)
        assertEquals(
            PersistedRestoreDecision.Reauthenticate(RestoreRejectionReason.REBOOT_OR_UNKNOWN_BOOT),
            TokenExpiryPolicy.evaluatePersistedRestore(
                record = base,
                nowEpochMs = 1_700_000_050_000L,
                nowElapsedRealtimeMs = 5_000L,
                currentBootGenerationId = "boot_43"
            )
        )

        // Unknown current boot ID
        assertEquals(
            PersistedRestoreDecision.Reauthenticate(RestoreRejectionReason.REBOOT_OR_UNKNOWN_BOOT),
            TokenExpiryPolicy.evaluatePersistedRestore(
                record = base,
                nowEpochMs = 1_700_000_050_000L,
                nowElapsedRealtimeMs = 50_000L,
                currentBootGenerationId = null
            )
        )

        // Clock rollback below lastSeenEpochMs
        val laterSeen = base.copy(lastSeenEpochMs = 1_700_000_200_000L)
        assertEquals(
            PersistedRestoreDecision.Reauthenticate(RestoreRejectionReason.CLOCK_ROLLBACK),
            TokenExpiryPolicy.evaluatePersistedRestore(
                record = laterSeen,
                nowEpochMs = 1_700_000_199_999L,
                nowElapsedRealtimeMs = 20_000L,
                currentBootGenerationId = "boot_42"
            )
        )

        // Same bootGenerationId but elapsedRealtime moved backwards -> fail closed
        assertEquals(
            PersistedRestoreDecision.Reauthenticate(RestoreRejectionReason.REBOOT_OR_UNKNOWN_BOOT),
            TokenExpiryPolicy.evaluatePersistedRestore(
                record = base,
                nowEpochMs = 1_700_000_050_000L,
                nowElapsedRealtimeMs = 9_999L,
                currentBootGenerationId = "boot_42"
            )
        )
    }
}
