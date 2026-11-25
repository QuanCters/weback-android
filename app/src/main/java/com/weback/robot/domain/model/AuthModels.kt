package com.weback.robot.domain.model

import java.util.Arrays

enum class AccountRegionType {
    CN,
    GLOBAL
}

/**
 * Ephemeral user login input holding a mutable character secret buffer.
 * Overridden [toString] strictly masks credentials (`[REDACTED]`).
 */
class AuthCredentials(
    val regionCallingCode: String,
    val account: String,
    secret: CharArray
) {
    private val secretBuffer: CharArray = secret.copyOf()

    fun copySecretChars(): CharArray = secretBuffer.copyOf()

    fun wipeSecret() {
        Arrays.fill(secretBuffer, '\u0000')
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AuthCredentials) return false
        return regionCallingCode == other.regionCallingCode &&
            account == other.account &&
            secretBuffer.contentEquals(other.secretBuffer)
    }

    override fun hashCode(): Int {
        var result = regionCallingCode.hashCode()
        result = 31 * result + account.hashCode()
        result = 31 * result + secretBuffer.contentHashCode()
        return result
    }

    override fun toString(): String =
        "AuthCredentials(regionCallingCode='$regionCallingCode', account='[REDACTED]', secret='[REDACTED]')"
}

/**
 * Active in-memory authenticated session. Plaintext passwords are never held here.
 */
data class SessionCredentials(
    val jwtToken: String,
    val apiUrl: String,
    val wssUrl: String,
    val regionName: String,
    val expiresInSeconds: Long,
    val issuedAtElapsedMs: Long,
    val priorElapsedMs: Long = 0L,
    val accountAliasRedacted: String = "",
    val syntheticRefreshToken: String? = null
) {
    override fun toString(): String =
        "SessionCredentials(jwtToken='[REDACTED]', apiUrl='$apiUrl', wssUrl='$wssUrl', " +
            "regionName='$regionName', expiresInSeconds=$expiresInSeconds, " +
            "issuedAtElapsedMs=$issuedAtElapsedMs, priorElapsedMs=$priorElapsedMs, " +
            "accountAliasRedacted='$accountAliasRedacted', " +
            "syntheticRefreshToken=${if (syntheticRefreshToken == null) "null" else "'[REDACTED]'"})"
}

/**
 * Encrypted persistent session record metadata stored via AndroidKeyStore AEAD.
 */
data class PersistedSessionRecord(
    val jwtToken: String,
    val apiUrl: String,
    val wssUrl: String,
    val regionName: String,
    val expiresInSeconds: Long,
    val issuedAtEpochMs: Long,
    val lastSeenEpochMs: Long,
    val issuedAtElapsedRealtimeMs: Long?,
    val bootGenerationId: String?,
    val accountAliasRedacted: String,
    val syntheticRefreshToken: String? = null
) {
    override fun toString(): String =
        "PersistedSessionRecord(jwtToken='[REDACTED]', apiUrl='$apiUrl', wssUrl='$wssUrl', " +
            "regionName='$regionName', expiresInSeconds=$expiresInSeconds, " +
            "issuedAtEpochMs=$issuedAtEpochMs, lastSeenEpochMs=$lastSeenEpochMs, " +
            "issuedAtElapsedRealtimeMs=$issuedAtElapsedRealtimeMs, bootGenerationId=$bootGenerationId, " +
            "accountAliasRedacted='$accountAliasRedacted', " +
            "syntheticRefreshToken=${if (syntheticRefreshToken == null) "null" else "'[REDACTED]'"})"
}

sealed interface AuthFailureReason {
    data object InvalidCredentials : AuthFailureReason
    data object ChallengeRequired : AuthFailureReason
    data object TokenExpiredOrRevoked : AuthFailureReason
    data object UntrustedEndpoint : AuthFailureReason
    data object RedirectRejected : AuthFailureReason
    data object ConfigurationError : AuthFailureReason
    data object StorageFailure : AuthFailureReason
    data object NetworkUnavailable : AuthFailureReason
    data object Timeout : AuthFailureReason
    data class ServerRejected(
        val httpStatus: Int?,
        val serverCode: Int?,
        val safeCategory: String
    ) : AuthFailureReason
    data object MalformedResponse : AuthFailureReason
}

sealed interface AuthResult {
    data class Success(
        val session: SessionCredentials,
        val isDurablePersisted: Boolean = true
    ) : AuthResult

    data class Failure(
        val reason: AuthFailureReason,
        val safeMessage: String
    ) : AuthResult
}

sealed interface LogoutResult {
    data object DurableSuccess : LogoutResult
    data class IncompleteStorageClear(val safeCategory: String) : LogoutResult
}

sealed interface SessionState {
    data object SignedOut : SessionState
    data object Authenticating : SessionState
    data class Authenticated(
        val session: SessionCredentials,
        val epoch: Long
    ) : SessionState

    data class Refreshing(
        val session: SessionCredentials,
        val epoch: Long
    ) : SessionState

    data class ReauthenticationRequired(
        val reason: AuthFailureReason,
        val epoch: Long
    ) : SessionState
}
