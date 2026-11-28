package com.weback.robot.app.local

/**
 * Ring-buffer diagnostic sink interface scoped strictly to privacy-safe [LocalDiagnosticEvent] values.
 */
interface LocalDiagnosticSink {
    fun log(event: LocalDiagnosticEvent)
    fun snapshotEntries(): List<String>
    fun clear()
}

/**
 * In-memory bounded ring-buffer logger enforcing `<= 200` entries and `<= 512` characters per entry.
 * Formats strictly allowlisted enums and numeric counters without touching shared cloud `SecretRedactor`.
 */
class LocalDiagnosticLogger(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxEntryChars: Int = MAX_ENTRY_CHARS
) : LocalDiagnosticSink {

    init {
        require(maxEntries > 0) { "maxEntries must be positive, got $maxEntries" }
        require(maxEntryChars > 0) { "maxEntryChars must be positive, got $maxEntryChars" }
    }

    private val entries = ArrayDeque<String>(minOf(maxEntries, 32))

    @Synchronized
    override fun log(event: LocalDiagnosticEvent) {
        val formatted = buildString(192) {
            append("cat=").append(event.category.name)
            append(" state=").append(event.connectionState.name)
            append(" gen=").append(event.generationId)
            append(" t=").append(event.elapsedSessionMs)
            append(" map=").append(event.mapCount)
            append(" aux=").append(event.auxCount)
            append(" traj=").append(event.trajCount)
            event.frameKind?.let { append(" kind=").append(it.name) }
            event.frameStartOffset?.let { append(" off=").append(it) }
            if (event.width != null && event.height != null) {
                append(" dim=").append(event.width).append('x').append(event.height)
            }
            if (event.codecErrorCode != LocalCodecErrorCode.NONE) {
                append(" codec=").append(event.codecErrorCode.name)
            }
            event.streamErrorCode?.let { append(" stream=").append(it.name) }
            if (event.terminalReason != LocalMapTerminalReason.NONE) {
                append(" reason=").append(event.terminalReason.name)
            }
        }
        val bounded = if (formatted.length <= maxEntryChars) {
            formatted
        } else {
            formatted.substring(0, maxEntryChars)
        }
        if (entries.size >= maxEntries) {
            entries.removeFirst()
        }
        entries.addLast(bounded)
    }

    @Synchronized
    override fun snapshotEntries(): List<String> = entries.toList()

    @Synchronized
    override fun clear() {
        entries.clear()
    }

    companion object {
        const val MAX_ENTRIES: Int = 200
        const val MAX_ENTRY_CHARS: Int = 512
    }
}
