package com.weback.robot.security

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Defense-in-depth log/exception scrubber covering case-insensitive headers, JWTs,
 * nested/malformed JSON secret values, URL-encoded parameters, and embedded userinfo.
 */
object SecretRedactor {

    private const val REDACTED = "[REDACTED]"

    private val jwtRegex = Regex("""\beyJ[A-Za-z0-9_-]{3,}(?:\.[A-Za-z0-9_-]*){1,2}""")
    private val headerRegex = Regex(
        pattern = """(?im)^([ \t]*(?:token|authorization|x-auth-token|set-cookie|cookie)\s*:\s*)(.+)$"""
    )
    private val bearerRegex = Regex("""(?i)\bBearer\s+[A-Za-z0-9._\-+/=]+""")
    private val jsonSecretCompleteRegex = Regex(
        pattern = """("?(?:jwt_token|jwtToken|token|pwd|password|pass|refresh_token|refreshToken|syntheticRefreshToken|access_token|tutk_secret|secret|session)"?\s*:\s*")([^"\\]*(?:\\.[^"\\]*)*)(")""" ,
        option = RegexOption.IGNORE_CASE
    )
    private val jsonSecretTruncatedRegex = Regex(
        pattern = """("?(?:jwt_token|jwtToken|token|pwd|password|pass|refresh_token|refreshToken|syntheticRefreshToken|access_token|tutk_secret|secret|session)"?\s*:\s*")([^"\r\n}]+)$""",
        option = RegexOption.IGNORE_CASE
    )
    private val userInfoRegex = Regex("""([a-zA-Z][a-zA-Z0-9+.-]*://)([^\s/@]+)@""")
    private val encodedUserInfoRegex = Regex("""([a-zA-Z][a-zA-Z0-9+.-]*://)([^\s/?#@]+%3A[^\s/?#@]+)%40""", RegexOption.IGNORE_CASE)
    private val querySecretRegex = Regex(
        pattern = """(?i)((?:token|jwt|jwt_token|auth|authorization|pwd|password|pass|refresh_token|access_token|secret|session)(?:=|%3D))([^&\s"'#]+)"""
    )

    fun redact(input: String): String {
        if (input.isEmpty()) return input
        var scrubbed = input

        // Decode URL-encoded delimiters in a duplicate check pass if %3D/%26/%3A/%40 are present
        scrubbed = encodedUserInfoRegex.replace(scrubbed) { mr -> "${mr.groupValues[1]}$REDACTED@" }
        scrubbed = userInfoRegex.replace(scrubbed) { mr -> "${mr.groupValues[1]}$REDACTED@" }
        scrubbed = querySecretRegex.replace(scrubbed) { mr -> "${mr.groupValues[1]}$REDACTED" }

        // If %26 / %3D wrapped query params survive (e.g. password%3Dsecret%26token%3Dabc), decode and scrub again
        if (scrubbed.contains("%3D", ignoreCase = true) || scrubbed.contains("%26", ignoreCase = true)) {
            val decodedAttempt = try {
                URLDecoder.decode(scrubbed, StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
                scrubbed
            }
            if (decodedAttempt != scrubbed) {
                scrubbed = querySecretRegex.replace(decodedAttempt) { mr -> "${mr.groupValues[1]}$REDACTED" }
                scrubbed = userInfoRegex.replace(scrubbed) { mr -> "${mr.groupValues[1]}$REDACTED@" }
            }
        }

        scrubbed = headerRegex.replace(scrubbed) { mr -> "${mr.groupValues[1]}$REDACTED" }
        scrubbed = bearerRegex.replace(scrubbed, "Bearer $REDACTED")
        scrubbed = jsonSecretCompleteRegex.replace(scrubbed) { mr ->
            "${mr.groupValues[1]}$REDACTED${mr.groupValues[3]}"
        }
        scrubbed = jsonSecretTruncatedRegex.replace(scrubbed) { mr ->
            "${mr.groupValues[1]}$REDACTED"
        }
        scrubbed = jwtRegex.replace(scrubbed, REDACTED)
        return scrubbed
    }

    fun redactThrowable(throwable: Throwable?): String {
        if (throwable == null) return ""
        val parts = mutableListOf<String>()
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 5) {
            val cls = current::class.java.simpleName
            val msg = current.message?.let { redact(it) } ?: ""
            parts.add(if (msg.isBlank()) cls else "$cls: $msg")
            current = current.cause
            depth++
        }
        return parts.joinToString(" <- ")
    }
}
