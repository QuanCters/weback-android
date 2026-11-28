package com.weback.robot.security

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Validates HTTP(S)/WS(S) endpoints strictly before trust evaluation or credential attachment.
 * Rejects scheme downgrades, embedded userinfo, credential query/fragment parameters, and malformed hosts.
 */
object UriValidator {

    data class NormalizedOrigin(
        val scheme: String,
        val host: String,
        val port: Int
    )

    sealed interface UriValidationResult {
        data class Valid(
            val origin: NormalizedOrigin,
            val normalizedUri: URI
        ) : UriValidationResult

        data class Rejected(
            val reason: String,
            val safeCategory: String
        ) : UriValidationResult
    }

    private val credentialKeys = setOf(
        "token",
        "jwt",
        "jwt_token",
        "auth",
        "authorization",
        "pwd",
        "password",
        "pass",
        "refresh_token",
        "access_token",
        "secret",
        "session",
        "tutk_secret",
        "cookie"
    )

    fun validateEndpoint(
        rawUrl: String,
        allowLoopbackCleartext: Boolean = false
    ): UriValidationResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return UriValidationResult.Rejected("Empty endpoint URL", "MALFORMED_URI")
        }

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return UriValidationResult.Rejected("Invalid URI syntax", "MALFORMED_URI")
        }

        val scheme = uri.scheme?.lowercase()
            ?: return UriValidationResult.Rejected("Missing URI scheme", "MALFORMED_URI")
        val hostRaw = uri.host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]")
            ?: return UriValidationResult.Rejected("Missing URI host", "MALFORMED_URI")
        if (hostRaw.isBlank()) {
            return UriValidationResult.Rejected("Blank URI host", "MALFORMED_URI")
        }

        if (!uri.userInfo.isNullOrEmpty() || !uri.rawUserInfo.isNullOrEmpty()) {
            return UriValidationResult.Rejected("Embedded userinfo prohibited", "USERINFO_REJECTED")
        }

        val isLoopbackHost = hostRaw == "127.0.0.1" || hostRaw == "localhost" || hostRaw == "::1"
        val schemeAllowed = when (scheme) {
            "https", "wss" -> true
            "http", "ws" -> allowLoopbackCleartext && isLoopbackHost
            else -> false
        }
        if (!schemeAllowed) {
            val category = if (scheme == "http" || scheme == "ws") "SCHEME_DOWNGRADE" else "UNSUPPORTED_SCHEME"
            return UriValidationResult.Rejected("Disallowed URI scheme or cleartext host", category)
        }

        val port = when {
            uri.port > 0 -> uri.port
            uri.port == -1 -> if (scheme == "https" || scheme == "wss") 443 else 80
            else -> return UriValidationResult.Rejected("Invalid port", "MALFORMED_URI")
        }
        if (port !in 1..65535) {
            return UriValidationResult.Rejected("Port out of bounds", "MALFORMED_URI")
        }

        if (hasSensitiveParams(uri.rawQuery) || hasSensitiveFragment(uri.rawFragment)) {
            return UriValidationResult.Rejected(
                "Credential-bearing query or fragment rejected",
                "CREDENTIAL_QUERY_REJECTED"
            )
        }

        return UriValidationResult.Valid(
            origin = NormalizedOrigin(scheme = scheme, host = hostRaw, port = port),
            normalizedUri = uri
        )
    }

    private fun hasSensitiveParams(rawQuery: String?): Boolean {
        if (rawQuery.isNullOrBlank()) return false
        val pairs = rawQuery.split('&', ';')
        for (pair in pairs) {
            if (pair.isBlank()) continue
            val decodedPair = safeUrlDecode(pair)
            for (subPart in decodedPair.split('&', ';')) {
                val eqIndex = subPart.indexOf('=')
                val key = (if (eqIndex >= 0) subPart.substring(0, eqIndex) else subPart)
                    .trim()
                    .lowercase()
                if (key in credentialKeys || credentialKeys.any { key.contains(it) }) {
                    return true
                }
            }
        }
        return false
    }

    private fun hasSensitiveFragment(rawFragment: String?): Boolean {
        if (rawFragment.isNullOrBlank()) return false
        val decoded = safeUrlDecode(rawFragment).lowercase()
        return decoded.contains('=') ||
            decoded.contains('?') ||
            credentialKeys.any { decoded.contains(it) }
    }

    private fun safeUrlDecode(value: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        value
    }
}
