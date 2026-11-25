package com.weback.robot.protocol.auth

import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.SessionCredentials

data class AuthEnvelopeConfig(
    val protocolApiVersion: String = DEFAULT_PROTOCOL_API_VERSION,
    val languageTag: String = "en",
    val appName: String = "WeBack",
    val clientId: String = "yugong_app"
) {
    init {
        require(protocolApiVersion.isNotBlank()) { "protocolApiVersion must not be blank" }
        require(protocolApiVersion != "0.1.0-alpha") {
            "Clean-room app version must not be substituted for protocolApiVersion"
        }
    }

    companion object {
        // Configurable wire parameter matching legacy 6.1.7 observation (REG-06 / UNK-AUTH-07)
        const val DEFAULT_PROTOCOL_API_VERSION = "6.1.7"
    }
}

interface AuthGateway {
    suspend fun signIn(credentials: AuthCredentials): AuthResult
}

/**
 * Optional passwordless refresh abstraction (`UNK-AUTH-01`, `SEC-302`).
 * Remains disabled in production (`DisabledPasswordlessRefreshStrategy`) unless synthetic tests
 * provide an explicit stub.
 */
interface RefreshGrantStrategy {
    val isEnabled: Boolean
    suspend fun refresh(session: SessionCredentials): AuthResult
}

object DisabledPasswordlessRefreshStrategy : RefreshGrantStrategy {
    override val isEnabled: Boolean = false

    override suspend fun refresh(session: SessionCredentials): AuthResult =
        AuthResult.Failure(
            reason = AuthFailureReason.TokenExpiredOrRevoked,
            safeMessage = "Reauthentication required; no verified passwordless refresh grant is available"
        )
}
