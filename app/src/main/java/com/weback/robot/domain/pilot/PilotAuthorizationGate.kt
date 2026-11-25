package com.weback.robot.domain.pilot

sealed interface GateActivationResult {
    data class Activated(val context: PilotAuthorizationContext) : GateActivationResult
    data class Rejected(val reason: PilotAbortReason, val safeMessage: String) : GateActivationResult
}

sealed interface GateCheckResult {
    data class Allowed(
        val context: PilotAuthorizationContext,
        val remainingWindowMs: Long
    ) : GateCheckResult

    data class Denied(
        val reason: PilotAbortReason,
        val safeMessage: String
    ) : GateCheckResult
}

/**
 * Pure Kotlin pilot authorization gate (`SEC-401`, `SEC-404`).
 * - Defaults strictly to disabled (`isAuthorized == false`).
 * - Never persists or restores authorization across process restart.
 * - Rejects incomplete, mismatched, expired, or UI-checkbox-only authorization contexts.
 */
class PilotAuthorizationGate(
    private val allowedAuthOrigins: Set<String> = CANONICAL_AUTH_ORIGINS
) {
    private val lock = Any()
    private var activeContext: PilotAuthorizationContext? = null
    private var lastAbortReason: PilotAbortReason? = null

    val isAuthorized: Boolean
        get() = synchronized(lock) { activeContext != null }

    val currentContext: PilotAuthorizationContext?
        get() = synchronized(lock) { activeContext }

    fun activate(
        context: PilotAuthorizationContext,
        expectedRuntimeIdentity: ExpectedCandidateIdentity,
        nowElapsedMs: Long,
        isUiCheckboxOnly: Boolean = false
    ): GateActivationResult {
        val validationError = validateContext(
            context = context,
            expectedRuntimeIdentity = expectedRuntimeIdentity,
            nowElapsedMs = nowElapsedMs,
            isUiCheckboxOnly = isUiCheckboxOnly
        )
        if (validationError != null) {
            return GateActivationResult.Rejected(validationError.first, validationError.second)
        }
        synchronized(lock) {
            activeContext = context
            lastAbortReason = null
        }
        return GateActivationResult.Activated(context)
    }

    fun authorizeOperation(
        operation: PilotOperation,
        nowElapsedMs: Long
    ): GateCheckResult {
        val ctx = synchronized(lock) { activeContext }
            ?: return GateCheckResult.Denied(
                lastAbortReason ?: PilotAbortReason.AuthorizationExpiredOrInvalid,
                "Pilot authorization is disabled"
            )

        if (nowElapsedMs < ctx.authorizedAtElapsedMs || nowElapsedMs >= ctx.observationDeadlineElapsedMs) {
            revoke(PilotAbortReason.ObservationWindowExpired)
            return GateCheckResult.Denied(
                PilotAbortReason.ObservationWindowExpired,
                "Pilot observation window expired"
            )
        }

        if (operation !in ctx.permittedOperations) {
            return GateCheckResult.Denied(
                PilotAbortReason.AuthorizationExpiredOrInvalid,
                "Operation ${operation.wireOpt} not permitted"
            )
        }

        return GateCheckResult.Allowed(
            context = ctx,
            remainingWindowMs = ctx.observationDeadlineElapsedMs - nowElapsedMs
        )
    }

    fun revoke(reason: PilotAbortReason = PilotAbortReason.UserEmergencyDisconnect) {
        synchronized(lock) {
            activeContext = null
            lastAbortReason = reason
        }
    }

    private fun validateContext(
        context: PilotAuthorizationContext,
        expectedRuntimeIdentity: ExpectedCandidateIdentity,
        nowElapsedMs: Long,
        isUiCheckboxOnly: Boolean
    ): Pair<PilotAbortReason, String>? {
        if (isUiCheckboxOnly || context.uiConsentCheckedOnly) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to
                "UI checkbox alone cannot grant pilot authorization"
        }
        val approval = context.externalApproval
        if (approval == null ||
            approval.approvedByProductOwner.isBlank() ||
            approval.approvedByLeadMaintainer.isBlank() ||
            approval.externalRecordRef.isBlank()
        ) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to
                "External dual human approval record is required"
        }
        if (context.authorizationId.isBlank()) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to "Authorization ID must not be blank"
        }
        if (!HEX_40_REGEX.matches(context.candidateIdentity.candidateCommitSha) ||
            !HEX_64_REGEX.matches(context.candidateIdentity.apkSha256) ||
            context.candidateIdentity != expectedRuntimeIdentity
        ) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to
                "Candidate commit SHA or APK identity mismatch"
        }
        if (context.accountScope.scopeType != PilotAccountScopeType.DEDICATED_ISOLATED_TEST_ACCOUNT ||
            !CALLING_CODE_REGEX.matches(context.accountScope.regionCallingCode.trim()) ||
            context.accountScope.authorizedAccountAlias.isBlank()
        ) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to
                "Dedicated isolated test account scope is required"
        }
        if (context.targetDevice.expectedSubTypeFamily != REQUIRED_SUB_TYPE_FAMILY ||
            context.targetDevice.expectedTargetThingName.isBlank() ||
            context.targetDevice.redactedTag.isBlank()
        ) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to
                "Exact LaserSlam target robot identity is required"
        }
        if (!context.readOnlyMode || context.physicalRobotCommandsPermitted) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to
                "Pilot context must enforce readOnlyMode=true and physicalRobotCommandsPermitted=false"
        }
        if (context.permittedOperations.isEmpty() ||
            !context.permittedOperations.all { it in PilotOperation.entries }
        ) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to
                "Permitted operations must be non-empty and strictly read-only"
        }
        if (context.endpointConfig.approvedAuthOrigin !in allowedAuthOrigins ||
            context.endpointConfig.approvedAuthPath != REQUIRED_AUTH_PATH ||
            !context.endpointConfig.requireProvenanceVerification
        ) {
            return PilotAbortReason.UntrustedOriginRejected to
                "Approved auth origin/path or provenance enforcement invalid"
        }
        val strictPort443 = (allowedAuthOrigins == CANONICAL_AUTH_ORIGINS)
        context.endpointConfig.pinnedApiOrigin?.let { pinnedApi ->
            if (!isValidPinnedOrigin(pinnedApi, expectedScheme = "https", requirePort443 = strictPort443)) {
                return PilotAbortReason.UntrustedOriginRejected to
                    "Pinned API origin violates HTTPS/:443 or hygiene constraints"
            }
        }
        context.endpointConfig.pinnedWssOrigin?.let { pinnedWss ->
            if (!isValidPinnedOrigin(pinnedWss, expectedScheme = "wss", requirePort443 = strictPort443)) {
                return PilotAbortReason.UntrustedOriginRejected to
                    "Pinned WSS origin violates WSS/:443 or hygiene constraints"
            }
        }
        val b = context.budgets
        if (b.maxLoginAttempts != PilotRequestBudgets.MAX_LOGIN_ATTEMPTS ||
            b.maxPasswordRetries != PilotRequestBudgets.MAX_PASSWORD_RETRIES ||
            b.maxCaptchaAutomation != PilotRequestBudgets.MAX_CAPTCHA_AUTOMATION ||
            b.maxInventoryRequests != PilotRequestBudgets.MAX_INVENTORY_REQUESTS ||
            b.maxInitialWsConnections != PilotRequestBudgets.MAX_INITIAL_WS_CONNECTIONS ||
            b.maxWsReconnectAttempts !in 0..PilotRequestBudgets.MAX_WS_RECONNECT_ATTEMPTS ||
            b.httpCallTimeoutMs !in 1L..PilotRequestBudgets.MAX_HTTP_CALL_TIMEOUT_MS ||
            b.observationWindowMs !in 1L..PilotRequestBudgets.MAX_OBSERVATION_WINDOW_MS ||
            b.maxPhysicalRobotCommands != PilotRequestBudgets.MAX_PHYSICAL_ROBOT_COMMANDS
        ) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to
                "Pilot request budgets exceed SEC-404 maximum caps"
        }
        if (context.authorizedAtElapsedMs < 0L ||
            context.observationDeadlineElapsedMs <= context.authorizedAtElapsedMs
        ) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to
                "Invalid monotonic authorization deadline"
        }
        val windowDuration = context.observationDeadlineElapsedMs - context.authorizedAtElapsedMs
        if (windowDuration > b.observationWindowMs) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to
                "Observation deadline exceeds configured window"
        }
        if (nowElapsedMs < context.authorizedAtElapsedMs ||
            nowElapsedMs >= context.observationDeadlineElapsedMs
        ) {
            return PilotAbortReason.ObservationWindowExpired to
                "Pilot authorization window is expired or not yet active"
        }
        return null
    }

    companion object {
        const val REQUIRED_AUTH_PATH = "/prod/oauth"
        const val REQUIRED_SUB_TYPE_FAMILY = "LaserSlam"
        val CANONICAL_AUTH_ORIGINS = setOf(
            "https://user.grit-cloud.cn:443",
            "https://user.grit-cloud.com:443"
        )
        private val HEX_40_REGEX = Regex("^[0-9a-fA-F]{40}$")
        private val HEX_64_REGEX = Regex("^[0-9a-fA-F]{64}$")
        private val CALLING_CODE_REGEX = Regex("^(\\+|00)[0-9]{1,4}$")

        private fun isValidPinnedOrigin(
            rawOrigin: String,
            expectedScheme: String,
            requirePort443: Boolean
        ): Boolean {
            val trimmed = rawOrigin.trim()
            if (trimmed.isEmpty() || trimmed.contains('@') || trimmed.contains('?') || trimmed.contains('#')) {
                return false
            }
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false
            val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
            if (scheme != expectedScheme || host.isBlank() || !uri.userInfo.isNullOrEmpty()) return false
            val port = if (uri.port == -1) 443 else uri.port
            if (port !in 1..65535) return false
            val isLoopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
            if (requirePort443 && port != 443) return false
            if (!requirePort443 && !isLoopback && port != 443) return false
            return true
        }
    }
}
