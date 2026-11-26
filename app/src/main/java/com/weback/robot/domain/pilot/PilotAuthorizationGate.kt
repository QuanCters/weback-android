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
 * Per-call admission ticket binding an outbound pilot network request or WebSocket handshake
 * to the exact active authorization run, generation, operation, deadline, and timeout budgets
 * (`FIX-401`, `FIX-403`).
 */
data class PilotAdmissionTicket(
    val authorizationId: String,
    val pilotGeneration: Long,
    val operation: PilotOperation,
    val deadlineElapsedMs: Long,
    val environment: PilotEnvironment,
    val httpCallTimeoutMs: Long,
    val maxWsReconnectAttempts: Int,
    val issuedElapsedMs: Long,
    val remainingWindowAtIssueMs: Long,
    val issuedSystemNanoTime: Long
)

sealed interface AdmissionResult {
    data class Admitted(val ticket: PilotAdmissionTicket) : AdmissionResult
    data class Denied(val reason: PilotAbortReason, val safeMessage: String) : AdmissionResult
}

/**
 * Pure Kotlin pilot authorization gate (`SEC-401`, `SEC-404`, `FIX-401`..`FIX-405`).
 * - Defaults strictly to disabled (`isAuthorized == false`).
 * - Never persists or restores authorization across process restart.
 * - Rejects incomplete, mismatched, expired, replayed, wrong-environment, or UI-checkbox-only contexts.
 * - Blocks new activation while prior run is active or cleanup is in-flight/incomplete.
 */
class PilotAuthorizationGate(
    private val allowedAuthOrigins: Set<String> = CANONICAL_AUTH_ORIGINS,
    val allowedEnvironment: PilotEnvironment = PilotEnvironment.LIVE_PILOT
) {
    private val lock = Any()
    private var activeContext: PilotAuthorizationContext? = null
    private var lastAbortReason: PilotAbortReason? = null
    private var pilotGeneration: Long = 0L
    private val consumedAuthorizationIds: MutableSet<String> = mutableSetOf()
    private var cleanupInFlight: Boolean = false
    private var incompleteCleanupPending: Boolean = false
    private var cleanupTokenSeq: Long = 0L
    private val inFlightCleanupTokens: MutableSet<Long> = mutableSetOf()
    private var loginRequestsAdmitted: Int = 0
    private var inventoryRequestsAdmitted: Int = 0
    private var wsInitialConnectionsAdmitted: Int = 0
    private var wsReconnectsAdmitted: Int = 0

    val isAuthorized: Boolean
        get() = synchronized(lock) { activeContext != null }

    val currentContext: PilotAuthorizationContext?
        get() = synchronized(lock) { activeContext }

    fun currentGeneration(): Long = synchronized(lock) { pilotGeneration }

    fun hasInFlightOrIncompleteCleanup(): Boolean = synchronized(lock) {
        cleanupInFlight || incompleteCleanupPending
    }

    fun hasIncompleteCleanup(): Boolean = synchronized(lock) {
        incompleteCleanupPending
    }

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
            if (activeContext != null) {
                return GateActivationResult.Rejected(
                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "An active pilot session must finish cleanup before new activation"
                )
            }
            if (cleanupInFlight || incompleteCleanupPending) {
                return GateActivationResult.Rejected(
                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Prior pilot cleanup is still in progress or incomplete"
                )
            }
            val normalizedAuthId = context.authorizationId.trim()
            if (normalizedAuthId in consumedAuthorizationIds) {
                return GateActivationResult.Rejected(
                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Authorization ID already consumed and cannot be replayed"
                )
            }
            consumedAuthorizationIds.add(normalizedAuthId)
            activeContext = context
            lastAbortReason = null
            pilotGeneration++
            loginRequestsAdmitted = 0
            inventoryRequestsAdmitted = 0
            wsInitialConnectionsAdmitted = 0
            wsReconnectsAdmitted = 0
        }
        return GateActivationResult.Activated(context)
    }

    fun verifyAccountCredentials(regionCallingCode: String, account: String): Pair<Boolean, String> {
        synchronized(lock) {
            val ctx = activeContext
                ?: return false to "Pilot authorization is disabled"
            if (PilotAccountScope.normalizeCallingCode(regionCallingCode) !=
                PilotAccountScope.normalizeCallingCode(ctx.accountScope.regionCallingCode)
            ) {
                return false to "Region calling code mismatches authorized account scope"
            }
            if (PilotAccountScope.normalizeAccount(account) !=
                PilotAccountScope.normalizeAccount(ctx.accountScope.authorizedAccountIdentifier)
            ) {
                return false to "Account identifier mismatches authorized test account"
            }
            return true to ""
        }
    }

    fun authorizeOperation(
        operation: PilotOperation,
        nowElapsedMs: Long
    ): GateCheckResult {
        synchronized(lock) {
            val ctx = activeContext
                ?: return GateCheckResult.Denied(
                    lastAbortReason ?: PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Pilot authorization is disabled"
                )

            if (nowElapsedMs < ctx.authorizedAtElapsedMs || nowElapsedMs >= ctx.observationDeadlineElapsedMs) {
                revokeLocked(PilotAbortReason.ObservationWindowExpired)
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
    }

    fun acquireAdmission(
        operation: PilotOperation,
        nowElapsedMs: Long,
        requestUrl: String? = null,
        isReconnect: Boolean = false,
        expectedGeneration: Long? = null,
        endpointValidator: ((PilotAuthorizationContext, String) -> Boolean)? = null
    ): AdmissionResult {
        synchronized(lock) {
            val ctx = activeContext
                ?: return AdmissionResult.Denied(
                    lastAbortReason ?: PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Pilot authorization is disabled"
                )
            if (expectedGeneration != null && expectedGeneration != pilotGeneration) {
                return AdmissionResult.Denied(
                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Pilot generation is stale"
                )
            }
            if (ctx.environment != allowedEnvironment) {
                return AdmissionResult.Denied(
                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Pilot environment mismatch"
                )
            }
            if (nowElapsedMs < ctx.authorizedAtElapsedMs || nowElapsedMs >= ctx.observationDeadlineElapsedMs) {
                revokeLocked(PilotAbortReason.ObservationWindowExpired)
                return AdmissionResult.Denied(
                    PilotAbortReason.ObservationWindowExpired,
                    "Pilot observation window expired"
                )
            }
            if (operation !in ctx.permittedOperations) {
                return AdmissionResult.Denied(
                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Operation ${operation.wireOpt} not permitted"
                )
            }
            if (requestUrl != null && endpointValidator != null && !endpointValidator(ctx, requestUrl)) {
                return AdmissionResult.Denied(
                    PilotAbortReason.UntrustedOriginRejected,
                    "Request endpoint rejected by pilot trust policy"
                )
            }

            when (operation) {
                PilotOperation.LOGIN -> {
                    if (loginRequestsAdmitted >= ctx.budgets.maxLoginAttempts) {
                        return AdmissionResult.Denied(
                            PilotAbortReason.BudgetExhausted,
                            "Login budget exhausted (max ${ctx.budgets.maxLoginAttempts})"
                        )
                    }
                    loginRequestsAdmitted++
                }
                PilotOperation.USER_THING_LIST_GET -> {
                    if (inventoryRequestsAdmitted >= ctx.budgets.maxInventoryRequests) {
                        return AdmissionResult.Denied(
                            PilotAbortReason.BudgetExhausted,
                            "Inventory request budget exhausted (max ${ctx.budgets.maxInventoryRequests})"
                        )
                    }
                    inventoryRequestsAdmitted++
                }
                PilotOperation.WS_TELEMETRY_OBSERVE -> {
                    if (!isReconnect) {
                        if (wsInitialConnectionsAdmitted >= ctx.budgets.maxInitialWsConnections) {
                            return AdmissionResult.Denied(
                                PilotAbortReason.BudgetExhausted,
                                "Initial WebSocket connection budget exhausted"
                            )
                        }
                        wsInitialConnectionsAdmitted++
                    } else {
                        if (wsReconnectsAdmitted >= ctx.budgets.maxWsReconnectAttempts) {
                            return AdmissionResult.Denied(
                                PilotAbortReason.BudgetExhausted,
                                "Cumulative WebSocket reconnect budget exhausted (max ${ctx.budgets.maxWsReconnectAttempts})"
                            )
                        }
                        wsReconnectsAdmitted++
                    }
                }
            }

            return AdmissionResult.Admitted(
                PilotAdmissionTicket(
                    authorizationId = ctx.authorizationId,
                    pilotGeneration = pilotGeneration,
                    operation = operation,
                    deadlineElapsedMs = ctx.observationDeadlineElapsedMs,
                    environment = ctx.environment,
                    httpCallTimeoutMs = ctx.budgets.httpCallTimeoutMs,
                    maxWsReconnectAttempts = ctx.budgets.maxWsReconnectAttempts,
                    issuedElapsedMs = nowElapsedMs,
                    remainingWindowAtIssueMs = (ctx.observationDeadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L),
                    issuedSystemNanoTime = System.nanoTime()
                )
            )
        }
    }

    fun verifyTicket(
        ticket: PilotAdmissionTicket,
        nowElapsedMs: Long? = null,
        requestUrl: String? = null,
        endpointValidator: ((PilotAuthorizationContext, String) -> Boolean)? = null
    ): GateCheckResult {
        synchronized(lock) {
            val ctx = activeContext
                ?: return GateCheckResult.Denied(
                    lastAbortReason ?: PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Pilot authorization is disabled"
                )
            if (ticket.environment != allowedEnvironment ||
                ctx.environment != allowedEnvironment ||
                ticket.authorizationId != ctx.authorizationId ||
                ticket.pilotGeneration != pilotGeneration
            ) {
                return GateCheckResult.Denied(
                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Pilot admission ticket is stale or mismatched"
                )
            }
            if (ticket.operation !in ctx.permittedOperations) {
                return GateCheckResult.Denied(
                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Operation ${ticket.operation.wireOpt} not permitted"
                )
            }
            val elapsedSinceIssueMs = ((System.nanoTime() - ticket.issuedSystemNanoTime) / 1_000_000L).coerceAtLeast(0L)
            val realTimeExpired = elapsedSinceIssueMs >= ticket.remainingWindowAtIssueMs
            val clockExpired = nowElapsedMs != null &&
                (nowElapsedMs < ctx.authorizedAtElapsedMs || nowElapsedMs >= ctx.observationDeadlineElapsedMs)
            if (realTimeExpired || clockExpired) {
                revokeLocked(PilotAbortReason.ObservationWindowExpired)
                return GateCheckResult.Denied(
                    PilotAbortReason.ObservationWindowExpired,
                    "Pilot observation window expired"
                )
            }
            if (requestUrl != null && endpointValidator != null && !endpointValidator(ctx, requestUrl)) {
                return GateCheckResult.Denied(
                    PilotAbortReason.UntrustedOriginRejected,
                    "Request endpoint rejected by pilot trust policy"
                )
            }
            val remaining = if (nowElapsedMs != null) {
                (ctx.observationDeadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)
            } else {
                (ticket.remainingWindowAtIssueMs - elapsedSinceIssueMs).coerceAtLeast(0L)
            }
            return GateCheckResult.Allowed(ctx, remaining)
        }
    }

    fun revoke(reason: PilotAbortReason = PilotAbortReason.UserEmergencyDisconnect) {
        synchronized(lock) {
            revokeLocked(reason)
        }
    }

    fun beginCleanup(reason: PilotAbortReason = PilotAbortReason.UserEmergencyDisconnect): Long {
        synchronized(lock) {
            revokeLocked(reason)
            val token = ++cleanupTokenSeq
            inFlightCleanupTokens.add(token)
            cleanupInFlight = true
            return token
        }
    }

    fun completeCleanup(cleanupToken: Long, storageCleared: Boolean?) {
        synchronized(lock) {
            inFlightCleanupTokens.remove(cleanupToken)
            cleanupInFlight = inFlightCleanupTokens.isNotEmpty()
            when (storageCleared) {
                true -> incompleteCleanupPending = false
                false -> incompleteCleanupPending = true
                null -> Unit
            }
        }
    }

    private fun revokeLocked(reason: PilotAbortReason) {
        if (activeContext != null) {
            activeContext = null
            pilotGeneration++
        }
        lastAbortReason = reason
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
        if (context.environment != allowedEnvironment) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to
                "Pilot authorization environment (${context.environment}) is not permitted on ${allowedEnvironment} gate"
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
            !CALLING_CODE_REGEX.matches(PilotAccountScope.normalizeCallingCode(context.accountScope.regionCallingCode)) ||
            context.accountScope.authorizedAccountAlias.isBlank() ||
            PilotAccountScope.normalizeAccount(context.accountScope.authorizedAccountIdentifier).isBlank()
        ) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to
                "Dedicated isolated test account scope and exact identifier are required"
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
