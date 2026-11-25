package com.weback.robot.security

/**
 * Deny-by-default allowlisted diagnostic event schema (`SEC-305`).
 * Raw credentials, headers, request/response bodies, and WebSocket payloads have no fields here.
 */
data class DiagnosticEvent(
    val category: String,
    val opt: String? = null,
    val httpStatus: Int? = null,
    val latencyMs: Long? = null,
    val safeHostPath: String? = null,
    val sessionEpoch: Long? = null,
    val connectionGeneration: Long? = null,
    val errorCategory: String? = null
)

interface DiagnosticLogger {
    fun log(event: DiagnosticEvent)
}

object NoOpDiagnosticLogger : DiagnosticLogger {
    override fun log(event: DiagnosticEvent) = Unit
}

class InMemoryDiagnosticLogger(
    private val sink: MutableList<String>
) : DiagnosticLogger {

    @Synchronized
    override fun log(event: DiagnosticEvent) {
        val sanitizedPath = event.safeHostPath
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.let { SecretRedactor.redact(it) }
        val fields = buildList {
            add("cat=${SecretRedactor.redact(event.category)}")
            event.opt?.let { add("opt=${SecretRedactor.redact(it)}") }
            event.httpStatus?.let { add("http=$it") }
            event.latencyMs?.let { add("latencyMs=$it") }
            sanitizedPath?.let { add("path=$it") }
            event.sessionEpoch?.let { add("epoch=$it") }
            event.connectionGeneration?.let { add("gen=$it") }
            event.errorCategory?.let { add("err=${SecretRedactor.redact(it)}") }
        }
        sink.add(SecretRedactor.redact(fields.joinToString(" ")))
    }
}
