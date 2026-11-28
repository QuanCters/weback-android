package com.weback.robot.security

/**
 * Isolates credential encoding before synthetic login envelope serialization.
 * FCT-003 authorizes [Utf8TlsDefault] ONLY; legacy unsalted MD5 transmission is prohibited (`UNK-AUTH-05`).
 */
interface PasswordDigestStrategy {
    val strategyName: String
    fun encodeForTlsPayload(secretChars: CharArray): String
}

object Utf8TlsDefault : PasswordDigestStrategy {
    override val strategyName: String = "UTF8_TLS_DEFAULT"
    override fun encodeForTlsPayload(secretChars: CharArray): String = String(secretChars)
}
