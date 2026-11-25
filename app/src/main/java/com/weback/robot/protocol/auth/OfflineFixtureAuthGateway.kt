package com.weback.robot.protocol.auth

import com.weback.robot.core.AndroidAuthMonotonicClock
import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.SessionCredentials

/**
 * Deterministic offline fixture authentication gateway with zero live network access.
 */
class OfflineFixtureAuthGateway(
    private val authClock: AuthMonotonicClock = AndroidAuthMonotonicClock,
    private val scriptHandler: (suspend (AuthCredentials) -> AuthResult)? = null
) : AuthGateway {

    override suspend fun signIn(credentials: AuthCredentials): AuthResult {
        scriptHandler?.let { return it(credentials) }

        if (credentials.account.isBlank() || credentials.copySecretChars().isEmpty()) {
            return AuthResult.Failure(AuthFailureReason.InvalidCredentials, "Credentials must not be blank")
        }
        val prefix = credentials.account.trim().take(1).ifEmpty { "u" }
        return AuthResult.Success(
            session = SessionCredentials(
                jwtToken = "offline-fixture-jwt-${authClock.elapsedRealtimeMs()}",
                apiUrl = "https://loopback.offline.invalid/api",
                wssUrl = "wss://loopback.offline.invalid/ws",
                regionName = "offline-fixture",
                expiresInSeconds = 3_600L,
                issuedAtElapsedMs = authClock.elapsedRealtimeMs(),
                priorElapsedMs = 0L,
                accountAliasRedacted = "$prefix***@offline.invalid"
            )
        )
    }
}
