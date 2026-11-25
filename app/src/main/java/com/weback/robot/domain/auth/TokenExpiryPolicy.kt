package com.weback.robot.domain.auth

import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.domain.model.SessionCredentials

enum class LifetimeEvaluation {
    VALID,
    PROACTIVE_REFRESH_RECOMMENDED,
    EXPIRED_OR_INVALID
}

enum class RestoreRejectionReason {
    INVALID_RECORD,
    CLOCK_ROLLBACK,
    REBOOT_OR_UNKNOWN_BOOT,
    EXPIRED,
    ARITHMETIC_OVERFLOW
}

sealed interface PersistedRestoreDecision {
    data class Restore(
        val priorElapsedMs: Long,
        val expiresInSeconds: Long
    ) : PersistedRestoreDecision

    data class Reauthenticate(
        val reason: RestoreRejectionReason
    ) : PersistedRestoreDecision
}

/**
 * Pure checked-arithmetic session lifetime evaluator (`IC-301`, `SEC-302`).
 * Never decodes or trusts unverified JWT payload claims (`exp`, `iat`).
 */
object TokenExpiryPolicy {

    fun evaluateInProcess(
        session: SessionCredentials,
        nowElapsedMs: Long
    ): LifetimeEvaluation {
        if (session.expiresInSeconds <= 0L ||
            session.priorElapsedMs < 0L ||
            session.issuedAtElapsedMs < 0L ||
            nowElapsedMs < 0L ||
            nowElapsedMs < session.issuedAtElapsedMs
        ) {
            return LifetimeEvaluation.EXPIRED_OR_INVALID
        }

        val sinceIssued = checkedSub(nowElapsedMs, session.issuedAtElapsedMs)
            ?: return LifetimeEvaluation.EXPIRED_OR_INVALID
        val totalElapsed = checkedAdd(session.priorElapsedMs, sinceIssued)
            ?: return LifetimeEvaluation.EXPIRED_OR_INVALID

        val expiryThresholdMs = checkedMul(session.expiresInSeconds, 1_000L)
            ?: return LifetimeEvaluation.EXPIRED_OR_INVALID
        if (totalElapsed >= expiryThresholdMs) {
            return LifetimeEvaluation.EXPIRED_OR_INVALID
        }

        val refreshThresholdMs = checkedMul(session.expiresInSeconds, 800L)
            ?: return LifetimeEvaluation.EXPIRED_OR_INVALID
        return if (totalElapsed > refreshThresholdMs) {
            LifetimeEvaluation.PROACTIVE_REFRESH_RECOMMENDED
        } else {
            LifetimeEvaluation.VALID
        }
    }

    fun evaluatePersistedRestore(
        record: PersistedSessionRecord,
        nowEpochMs: Long,
        nowElapsedRealtimeMs: Long,
        currentBootGenerationId: String?
    ): PersistedRestoreDecision {
        if (record.expiresInSeconds <= 0L ||
            record.priorElapsedMs < 0L ||
            record.issuedAtEpochMs <= 0L ||
            record.lastSeenEpochMs < record.issuedAtEpochMs ||
            record.jwtToken.isBlank() ||
            record.apiUrl.isBlank() ||
            record.wssUrl.isBlank() ||
            record.regionName.isBlank()
        ) {
            return PersistedRestoreDecision.Reauthenticate(RestoreRejectionReason.INVALID_RECORD)
        }

        if (nowEpochMs <= 0L ||
            nowEpochMs < record.issuedAtEpochMs ||
            nowEpochMs < record.lastSeenEpochMs
        ) {
            return PersistedRestoreDecision.Reauthenticate(RestoreRejectionReason.CLOCK_ROLLBACK)
        }

        val issuedRealtimeMs = record.issuedAtElapsedRealtimeMs
        if (currentBootGenerationId.isNullOrBlank() ||
            record.bootGenerationId.isNullOrBlank() ||
            currentBootGenerationId != record.bootGenerationId ||
            issuedRealtimeMs == null ||
            issuedRealtimeMs < 0L ||
            nowElapsedRealtimeMs < 0L ||
            nowElapsedRealtimeMs < issuedRealtimeMs
        ) {
            return PersistedRestoreDecision.Reauthenticate(RestoreRejectionReason.REBOOT_OR_UNKNOWN_BOOT)
        }

        val sinceIssuedMs = checkedSub(nowElapsedRealtimeMs, issuedRealtimeMs)
            ?: return PersistedRestoreDecision.Reauthenticate(RestoreRejectionReason.ARITHMETIC_OVERFLOW)
        val priorElapsedMs = checkedAdd(record.priorElapsedMs, sinceIssuedMs)
            ?: return PersistedRestoreDecision.Reauthenticate(RestoreRejectionReason.ARITHMETIC_OVERFLOW)
        val expiryThresholdMs = checkedMul(record.expiresInSeconds, 1_000L)
            ?: return PersistedRestoreDecision.Reauthenticate(RestoreRejectionReason.ARITHMETIC_OVERFLOW)

        if (priorElapsedMs >= expiryThresholdMs) {
            return PersistedRestoreDecision.Reauthenticate(RestoreRejectionReason.EXPIRED)
        }

        return PersistedRestoreDecision.Restore(
            priorElapsedMs = priorElapsedMs,
            expiresInSeconds = record.expiresInSeconds
        )
    }

    fun remainingLifetimeMs(
        session: SessionCredentials,
        nowElapsedMs: Long
    ): Long {
        if (evaluateInProcess(session, nowElapsedMs) == LifetimeEvaluation.EXPIRED_OR_INVALID) {
            return 0L
        }
        val sinceIssued = checkedSub(nowElapsedMs, session.issuedAtElapsedMs) ?: return 0L
        val totalElapsed = checkedAdd(session.priorElapsedMs, sinceIssued) ?: return 0L
        val expiryThresholdMs = checkedMul(session.expiresInSeconds, 1_000L) ?: return 0L
        return (expiryThresholdMs - totalElapsed).coerceAtLeast(0L)
    }

    internal fun checkedAdd(a: Long, b: Long): Long? {
        if (b > 0L && a > Long.MAX_VALUE - b) return null
        if (b < 0L && a < Long.MIN_VALUE - b) return null
        return a + b
    }

    internal fun checkedSub(a: Long, b: Long): Long? {
        if (b < 0L && a > Long.MAX_VALUE + b) return null
        if (b > 0L && a < Long.MIN_VALUE + b) return null
        return a - b
    }

    internal fun checkedMul(a: Long, b: Long): Long? {
        if (a == 0L || b == 0L) return 0L
        if ((a == Long.MIN_VALUE && b == -1L) || (b == Long.MIN_VALUE && a == -1L)) return null
        val result = a * b
        return if (result / b != a) null else result
    }
}
