package com.weback.robot.security

import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotEmergencyDisconnectResult

/**
 * Generates sanitized pilot diagnostic reports strictly from allowlisted metadata and
 * [SecretRedactor]-scrubbed events (`SEC-406`, `REQ-PILOT-408`).
 */
class PilotSanitizedExporter {

    fun exportReport(
        authorizationId: String,
        modeLabel: String,
        rawEvents: List<String>,
        observedApiOrigin: String?,
        observedWssOrigin: String?,
        lastAbortReason: PilotAbortReason?,
        disconnectResult: PilotEmergencyDisconnectResult?,
        sensitiveThingNames: Set<String> = emptySet(),
        sensitiveAliases: Set<String> = emptySet()
    ): String {
        val sb = StringBuilder()
        sb.appendLine("pilot_authorization_id=${scrub(authorizationId, sensitiveThingNames, sensitiveAliases)}")
        sb.appendLine("mode_label=${scrub(modeLabel, sensitiveThingNames, sensitiveAliases)}")
        sb.appendLine("observed_api_origin=${observedApiOrigin?.let { scrub(it, sensitiveThingNames, sensitiveAliases) } ?: "NONE"}")
        sb.appendLine("observed_wss_origin=${observedWssOrigin?.let { scrub(it, sensitiveThingNames, sensitiveAliases) } ?: "NONE"}")
        sb.appendLine("last_abort_reason=${lastAbortReason?.safeCode ?: "NONE"}")
        if (disconnectResult != null) {
            sb.appendLine("network_revoked_immediately=${disconnectResult.networkRevokedImmediately}")
            sb.appendLine("storage_cleared=${disconnectResult.storageCleared}")
            sb.appendLine(
                "incomplete_storage_reason=${
                    disconnectResult.incompleteStorageReason?.let { scrub(it, sensitiveThingNames, sensitiveAliases) } ?: "NONE"
                }"
            )
            sb.appendLine("restored_offline_simulator=${disconnectResult.restoredOfflineSimulator}")
            sb.appendLine("server_side_token_revocation_claimed=${disconnectResult.serverSideTokenRevocationClaimed}")
        }
        sb.appendLine("events_count=${rawEvents.size}")
        for (ev in rawEvents) {
            sb.appendLine("event: ${scrub(ev, sensitiveThingNames, sensitiveAliases)}")
        }
        return sb.toString()
    }

    private fun scrub(
        input: String,
        sensitiveThingNames: Set<String>,
        sensitiveAliases: Set<String>
    ): String {
        var sanitized = SecretRedactor.redact(input)
        for (thing in sensitiveThingNames) {
            if (thing.isNotBlank()) {
                sanitized = sanitized.replace(thing, "[REDACTED_DEVICE]")
            }
        }
        for (alias in sensitiveAliases) {
            if (alias.isNotBlank()) {
                sanitized = sanitized.replace(alias, "[REDACTED_ACCOUNT]")
            }
        }
        return SecretRedactor.redact(sanitized)
    }
}
