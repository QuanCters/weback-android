package com.weback.robot.domain.pilot

import java.net.URI
import java.util.UUID

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
 * Internally issued, single-use, request-bound admission ticket (`B-SEC-01`, `B-SEC-03`).
 * Bound inside [PilotAuthorizationGate] by [ticketId] and verified against an internal
 * authoritative record so caller-constructed or tampered tickets cannot bypass budgets.
 */
@ConsistentCopyVisibility
data class PilotAdmissionTicket internal constructor(
    val ticketId: String,
    val authorizationId: String,
    val pilotGeneration: Long,
    val operation: PilotOperation,
    val deadlineElapsedMs: Long,
    val environment: PilotEnvironment,
    val httpCallTimeoutMs: Long,
    val maxWsReconnectAttempts: Int,
    val issuedElapsedMs: Long,
    val remainingWindowAtIssueMs: Long,
    val boundAccountIdentifier: String? = null,
    val boundCallingCode: String? = null,
    val boundRequestUrl: String? = null
)

sealed interface AdmissionResult {
    data class Admitted(val ticket: PilotAdmissionTicket) : AdmissionResult
    data class Denied(val reason: PilotAbortReason, val safeMessage: String) : AdmissionResult
}

/**
 * Pure Kotlin pilot authorization gate (`SEC-401`, `SEC-404`, `FIX-401`..`FIX-405`, `B-SEC-01`..`B-SEC-03`).
 * - Defaults strictly to disabled (`isAuthorized == false`).
 * - Never persists or restores authorization across process restart.
 * - Uses injected [PilotClock] (backed by Android `SystemClock.elapsedRealtime()`) exclusively for deadlines.
 * - Issues single-use, request-bound admission tickets verified at intermediate layers and consumed once at dispatch.
 * - Notifies attached expiry cleanup listener so deadline expiry triggers complete generation-fenced teardown.
 */
class PilotAuthorizationGate(
    private val allowedAuthOrigins: Set<String> = CANONICAL_AUTH_ORIGINS,
    val allowedEnvironment: PilotEnvironment = PilotEnvironment.LIVE_PILOT,
    private val clock: PilotClock? = null
) {
    private data class IssuedTicketRecord(
        val ticketId: String,
        val authorizationId: String,
        val pilotGeneration: Long,
        val operation: PilotOperation,
        val deadlineElapsedMs: Long,
        val environment: PilotEnvironment,
        val httpCallTimeoutMs: Long,
        val maxWsReconnectAttempts: Int,
        val issuedElapsedMs: Long,
        val remainingWindowAtIssueMs: Long,
        val boundAccountIdentifier: String?,
        val boundCallingCode: String?,
        val initialBoundRequestUrl: String?,
        var boundRequestUrl: String? = initialBoundRequestUrl,
        var consumed: Boolean = false
    )

    private val lock = Any()
    private var activeContext: PilotAuthorizationContext? = null
    private var lastAbortReason: PilotAbortReason? = null
    private var pilotGeneration: Long = 0L
    private var ticketSeq: Long = 0L
    private var lastKnownElapsedMs: Long = 0L
    private val consumedAuthorizationIds: MutableSet<String> = mutableSetOf()
    private val issuedTickets: MutableMap<String, IssuedTicketRecord> = mutableMapOf()
    private var cleanupInFlight: Boolean = false
    private var incompleteCleanupPending: Boolean = false
    private var cleanupTokenSeq: Long = 0L
    private val inFlightCleanupTokens: MutableSet<Long> = mutableSetOf()
    private var loginRequestsAdmitted: Int = 0
    private var inventoryRequestsAdmitted: Int = 0
    private var wsInitialConnectionsAdmitted: Int = 0
    private var wsReconnectsAdmitted: Int = 0

    @Volatile
    private var onWindowExpiredListener: ((Long) -> Unit)? = null

    fun attachOnWindowExpiredListener(listener: ((Long) -> Unit)?) {
        onWindowExpiredListener = listener
    }

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
        nowElapsedMs: Long = resolveClockOrThrow(null),
        isUiCheckboxOnly: Boolean = false
    ): GateActivationResult {
        val validationError = validateContext(
            context = context,
            expectedRuntimeIdentity = expectedRuntimeIdentity,
            runtimeAppIdentity = null,
            nowElapsedMs = nowElapsedMs,
            isUiCheckboxOnly = isUiCheckboxOnly
        )
        if (validationError != null) {
            return GateActivationResult.Rejected(validationError.first, validationError.second)
        }
        return commitActivation(context, nowElapsedMs)
    }

    fun activate(
        context: PilotAuthorizationContext,
        runtimeAppIdentity: RuntimeAppIdentity,
        nowElapsedMs: Long = resolveClockOrThrow(null),
        isUiCheckboxOnly: Boolean = false
    ): GateActivationResult {
        val validationError = validateContext(
            context = context,
            expectedRuntimeIdentity = null,
            runtimeAppIdentity = runtimeAppIdentity,
            nowElapsedMs = nowElapsedMs,
            isUiCheckboxOnly = isUiCheckboxOnly
        )
        if (validationError != null) {
            return GateActivationResult.Rejected(validationError.first, validationError.second)
        }
        return commitActivation(context, nowElapsedMs)
    }

    private fun commitActivation(
        context: PilotAuthorizationContext,
        nowElapsedMs: Long
    ): GateActivationResult {
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
            lastKnownElapsedMs = nowElapsedMs
            pilotGeneration++
            issuedTickets.clear()
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
        nowElapsedMs: Long? = null
    ): GateCheckResult {
        var expiredGenToNotify: Long? = null
        val result = synchronized(lock) {
            val ctx = activeContext
                ?: return@synchronized GateCheckResult.Denied(
                    lastAbortReason ?: PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Pilot authorization is disabled"
                )

            val effectiveNow = effectiveElapsedMsLocked(nowElapsedMs)
            if (effectiveNow < ctx.authorizedAtElapsedMs || effectiveNow >= ctx.observationDeadlineElapsedMs) {
                expiredGenToNotify = pilotGeneration
                revokeLocked(PilotAbortReason.ObservationWindowExpired)
                return@synchronized GateCheckResult.Denied(
                    PilotAbortReason.ObservationWindowExpired,
                    "Pilot observation window expired"
                )
            }

            if (operation !in ctx.permittedOperations) {
                return@synchronized GateCheckResult.Denied(
                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Operation ${operation.wireOpt} not permitted"
                )
            }

            GateCheckResult.Allowed(
                context = ctx,
                remainingWindowMs = ctx.observationDeadlineElapsedMs - effectiveNow
            )
        }
        expiredGenToNotify?.let { onWindowExpiredListener?.invoke(it) }
        return result
    }

    fun acquireAdmission(
        operation: PilotOperation,
        nowElapsedMs: Long? = null,
        requestUrl: String? = null,
        isReconnect: Boolean = false,
        expectedGeneration: Long? = null,
        endpointValidator: ((PilotAuthorizationContext, String) -> Boolean)? = null
    ): AdmissionResult {
        var expiredGenToNotify: Long? = null
        val result = synchronized(lock) {
            val ctx = activeContext
                ?: return@synchronized AdmissionResult.Denied(
                    lastAbortReason ?: PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Pilot authorization is disabled"
                )
            if (expectedGeneration != null && expectedGeneration != pilotGeneration) {
                return@synchronized AdmissionResult.Denied(
                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Pilot generation is stale"
                )
            }
            if (ctx.environment != allowedEnvironment) {
                return@synchronized AdmissionResult.Denied(
                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Pilot environment mismatch"
                )
            }
            val effectiveNow = effectiveElapsedMsLocked(nowElapsedMs)
            if (effectiveNow < ctx.authorizedAtElapsedMs || effectiveNow >= ctx.observationDeadlineElapsedMs) {
                expiredGenToNotify = pilotGeneration
                revokeLocked(PilotAbortReason.ObservationWindowExpired)
                return@synchronized AdmissionResult.Denied(
                    PilotAbortReason.ObservationWindowExpired,
                    "Pilot observation window expired"
                )
            }
            if (operation !in ctx.permittedOperations) {
                return@synchronized AdmissionResult.Denied(
                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Operation ${operation.wireOpt} not permitted"
                )
            }
            if (requestUrl != null && endpointValidator != null && !endpointValidator(ctx, requestUrl)) {
                return@synchronized AdmissionResult.Denied(
                    PilotAbortReason.UntrustedOriginRejected,
                    "Request endpoint rejected by pilot trust policy"
                )
            }

            when (operation) {
                PilotOperation.LOGIN -> {
                    if (loginRequestsAdmitted >= ctx.budgets.maxLoginAttempts) {
                        return@synchronized AdmissionResult.Denied(
                            PilotAbortReason.BudgetExhausted,
                            "Login budget exhausted (max ${ctx.budgets.maxLoginAttempts})"
                        )
                    }
                    loginRequestsAdmitted++
                }
                PilotOperation.USER_THING_LIST_GET -> {
                    if (inventoryRequestsAdmitted >= ctx.budgets.maxInventoryRequests) {
                        return@synchronized AdmissionResult.Denied(
                            PilotAbortReason.BudgetExhausted,
                            "Inventory request budget exhausted (max ${ctx.budgets.maxInventoryRequests})"
                        )
                    }
                    inventoryRequestsAdmitted++
                }
                PilotOperation.WS_TELEMETRY_OBSERVE -> {
                    if (!isReconnect) {
                        if (wsInitialConnectionsAdmitted >= ctx.budgets.maxInitialWsConnections) {
                            return@synchronized AdmissionResult.Denied(
                                PilotAbortReason.BudgetExhausted,
                                "Initial WebSocket connection budget exhausted"
                            )
                        }
                        wsInitialConnectionsAdmitted++
                    } else {
                        if (wsReconnectsAdmitted >= ctx.budgets.maxWsReconnectAttempts) {
                            return@synchronized AdmissionResult.Denied(
                                PilotAbortReason.BudgetExhausted,
                                "Cumulative WebSocket reconnect budget exhausted (max ${ctx.budgets.maxWsReconnectAttempts})"
                            )
                        }
                        wsReconnectsAdmitted++
                    }
                }
            }

            val normalizedBoundUrl = requestUrl?.let { normalizeTicketUrl(it, operation) }
            val boundAcct = if (operation == PilotOperation.LOGIN) {
                PilotAccountScope.normalizeAccount(ctx.accountScope.authorizedAccountIdentifier)
            } else {
                null
            }
            val boundCode = if (operation == PilotOperation.LOGIN) {
                PilotAccountScope.normalizeCallingCode(ctx.accountScope.regionCallingCode)
            } else {
                null
            }
            val remaining = (ctx.observationDeadlineElapsedMs - effectiveNow).coerceAtLeast(0L)
            val ticketId = "adm-$pilotGeneration-${++ticketSeq}-${UUID.randomUUID()}"
            val record = IssuedTicketRecord(
                ticketId = ticketId,
                authorizationId = ctx.authorizationId,
                pilotGeneration = pilotGeneration,
                operation = operation,
                deadlineElapsedMs = ctx.observationDeadlineElapsedMs,
                environment = ctx.environment,
                httpCallTimeoutMs = ctx.budgets.httpCallTimeoutMs,
                maxWsReconnectAttempts = ctx.budgets.maxWsReconnectAttempts,
                issuedElapsedMs = effectiveNow,
                remainingWindowAtIssueMs = remaining,
                boundAccountIdentifier = boundAcct,
                boundCallingCode = boundCode,
                initialBoundRequestUrl = normalizedBoundUrl
            )
            issuedTickets[ticketId] = record

            AdmissionResult.Admitted(
                PilotAdmissionTicket(
                    ticketId = ticketId,
                    authorizationId = record.authorizationId,
                    pilotGeneration = record.pilotGeneration,
                    operation = record.operation,
                    deadlineElapsedMs = record.deadlineElapsedMs,
                    environment = record.environment,
                    httpCallTimeoutMs = record.httpCallTimeoutMs,
                    maxWsReconnectAttempts = record.maxWsReconnectAttempts,
                    issuedElapsedMs = record.issuedElapsedMs,
                    remainingWindowAtIssueMs = record.remainingWindowAtIssueMs,
                    boundAccountIdentifier = record.boundAccountIdentifier,
                    boundCallingCode = record.boundCallingCode,
                    boundRequestUrl = record.initialBoundRequestUrl
                )
            )
        }
        expiredGenToNotify?.let { onWindowExpiredListener?.invoke(it) }
        return result
    }

    /**
     * Non-consuming intermediate verification used by gateway/transport layers before
     * credential serialization or request dispatch (`B-SEC-01`).
     */
    fun verifyTicket(
        ticket: PilotAdmissionTicket,
        nowElapsedMs: Long? = null,
        requestUrl: String? = null,
        endpointValidator: ((PilotAuthorizationContext, String) -> Boolean)? = null,
        accountIdentifier: String? = null,
        regionCallingCode: String? = null
    ): GateCheckResult = evaluateTicketInternal(
        ticket = ticket,
        nowElapsedMs = nowElapsedMs,
        requestUrl = requestUrl,
        observedOperation = null,
        endpointValidator = endpointValidator,
        accountIdentifier = accountIdentifier,
        regionCallingCode = regionCallingCode,
        consume = false
    )

    /**
     * Atomic one-time ticket consumption performed strictly at the network dispatch boundary (`B-SEC-01`, `B-FIX-01`).
     * Prevents ticket replay, forged tickets, field tampering, cross-request substitution, and
     * serialized login account/calling_code mismatch.
     */
    fun consumeTicketForDispatch(
        ticket: PilotAdmissionTicket,
        requestUrl: String,
        observedOperation: PilotOperation,
        nowElapsedMs: Long? = null,
        endpointValidator: ((PilotAuthorizationContext, String) -> Boolean)? = null,
        accountIdentifier: String? = null,
        regionCallingCode: String? = null
    ): GateCheckResult = evaluateTicketInternal(
        ticket = ticket,
        nowElapsedMs = nowElapsedMs,
        requestUrl = requestUrl,
        observedOperation = observedOperation,
        endpointValidator = endpointValidator,
        accountIdentifier = accountIdentifier,
        regionCallingCode = regionCallingCode,
        consume = true
    )

    private fun evaluateTicketInternal(
        ticket: PilotAdmissionTicket,
        nowElapsedMs: Long?,
        requestUrl: String?,
        observedOperation: PilotOperation?,
        endpointValidator: ((PilotAuthorizationContext, String) -> Boolean)?,
        accountIdentifier: String?,
        regionCallingCode: String?,
        consume: Boolean
    ): GateCheckResult {
        var expiredGenToNotify: Long? = null
        val result = synchronized(lock) {
            val ctx = activeContext
                ?: return@synchronized GateCheckResult.Denied(
                    lastAbortReason ?: PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Pilot authorization is disabled"
                )
            val record = issuedTickets[ticket.ticketId]
                ?: return@synchronized GateCheckResult.Denied(
                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Unknown or caller-forged pilot admission ticket"
                )
            if (!ticketMatchesRecord(ticket, record)) {
                return@synchronized GateCheckResult.Denied(
                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Tampered pilot admission ticket fields rejected"
                )
            }
            if (record.consumed) {
                return@synchronized GateCheckResult.Denied(
                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Pilot admission ticket already consumed (replay blocked)"
                )
            }
            if (record.environment != allowedEnvironment ||
                ctx.environment != allowedEnvironment ||
                record.authorizationId != ctx.authorizationId ||
                record.pilotGeneration != pilotGeneration
            ) {
                return@synchronized GateCheckResult.Denied(
                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Pilot admission ticket is stale or mismatched"
                )
            }
            if (record.operation !in ctx.permittedOperations) {
                return@synchronized GateCheckResult.Denied(
                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Operation ${record.operation.wireOpt} not permitted"
                )
            }
            if (observedOperation != null && observedOperation != record.operation) {
                return@synchronized GateCheckResult.Denied(
                    PilotAbortReason.AuthorizationExpiredOrInvalid,
                    "Request operation mismatches admission ticket"
                )
            }
            if (record.operation == PilotOperation.LOGIN &&
                (consume || accountIdentifier != null || regionCallingCode != null)
            ) {
                val normAcct = accountIdentifier?.let { PilotAccountScope.normalizeAccount(it) }
                val normCode = regionCallingCode?.let { PilotAccountScope.normalizeCallingCode(it) }
                if (normAcct.isNullOrBlank() ||
                    normCode.isNullOrBlank() ||
                    normAcct != record.boundAccountIdentifier ||
                    normCode != record.boundCallingCode
                ) {
                    return@synchronized GateCheckResult.Denied(
                        PilotAbortReason.AuthorizationExpiredOrInvalid,
                        "Ticket account binding mismatch"
                    )
                }
            }

            val effectiveNow = effectiveElapsedMsLocked(nowElapsedMs)
            if (effectiveNow < ctx.authorizedAtElapsedMs || effectiveNow >= ctx.observationDeadlineElapsedMs) {
                expiredGenToNotify = pilotGeneration
                revokeLocked(PilotAbortReason.ObservationWindowExpired)
                return@synchronized GateCheckResult.Denied(
                    PilotAbortReason.ObservationWindowExpired,
                    "Pilot observation window expired"
                )
            }

            if (requestUrl != null) {
                val normalizedRequestUrl = normalizeTicketUrl(requestUrl, record.operation)
                if (endpointValidator != null && !endpointValidator(ctx, requestUrl)) {
                    return@synchronized GateCheckResult.Denied(
                        PilotAbortReason.UntrustedOriginRejected,
                        "Request endpoint rejected by pilot trust policy"
                    )
                }
                val boundUrl = record.boundRequestUrl
                if (boundUrl != null && normalizedRequestUrl != boundUrl) {
                    return@synchronized GateCheckResult.Denied(
                        PilotAbortReason.UntrustedOriginRejected,
                        "Request URL mismatches admission ticket binding"
                    )
                }
                if (boundUrl == null) {
                    record.boundRequestUrl = normalizedRequestUrl
                }
            } else if (consume) {
                return@synchronized GateCheckResult.Denied(
                    PilotAbortReason.UntrustedOriginRejected,
                    "Missing request URL at dispatch consumption"
                )
            }

            if (consume) {
                record.consumed = true
            }

            val remaining = (ctx.observationDeadlineElapsedMs - effectiveNow).coerceAtLeast(0L)
            GateCheckResult.Allowed(ctx, remaining)
        }
        expiredGenToNotify?.let { onWindowExpiredListener?.invoke(it) }
        return result
    }

    private fun ticketMatchesRecord(ticket: PilotAdmissionTicket, record: IssuedTicketRecord): Boolean {
        return ticket.ticketId == record.ticketId &&
            ticket.authorizationId == record.authorizationId &&
            ticket.pilotGeneration == record.pilotGeneration &&
            ticket.operation == record.operation &&
            ticket.deadlineElapsedMs == record.deadlineElapsedMs &&
            ticket.environment == record.environment &&
            ticket.httpCallTimeoutMs == record.httpCallTimeoutMs &&
            ticket.maxWsReconnectAttempts == record.maxWsReconnectAttempts &&
            ticket.issuedElapsedMs == record.issuedElapsedMs &&
            ticket.remainingWindowAtIssueMs == record.remainingWindowAtIssueMs &&
            ticket.boundAccountIdentifier == record.boundAccountIdentifier &&
            ticket.boundCallingCode == record.boundCallingCode &&
            ticket.boundRequestUrl == record.initialBoundRequestUrl
    }

    private fun effectiveElapsedMsLocked(explicitNowMs: Long?): Long {
        val resolved = explicitNowMs ?: clock?.elapsedRealtimeMs() ?: lastKnownElapsedMs
        if (resolved > lastKnownElapsedMs) {
            lastKnownElapsedMs = resolved
        }
        return resolved
    }

    private fun resolveClockOrThrow(explicitNowMs: Long?): Long {
        return explicitNowMs ?: clock?.elapsedRealtimeMs() ?: lastKnownElapsedMs
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

    /**
     * Acquires a cleanup barrier token when expiration already revoked the active context inside
     * [PilotAuthorizationGate], ensuring new activations stay blocked until storage cleanup finishes.
     */
    fun beginCleanupForRevokedGeneration(reason: PilotAbortReason = PilotAbortReason.ObservationWindowExpired): Long {
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
        issuedTickets.clear()
        lastAbortReason = reason
    }

    private fun validateContext(
        context: PilotAuthorizationContext,
        expectedRuntimeIdentity: ExpectedCandidateIdentity?,
        runtimeAppIdentity: RuntimeAppIdentity?,
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
                "Pilot authorization environment (${context.environment}) is not permitted on $allowedEnvironment gate"
        }
        if (context.authorizationId.isBlank()) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to "Authorization ID must not be blank"
        }
        if (!HEX_40_REGEX.matches(context.candidateIdentity.candidateCommitSha) ||
            !HEX_64_REGEX.matches(context.candidateIdentity.apkSha256)
        ) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to
                "Candidate commit SHA or APK SHA-256 format invalid"
        }
        if (context.environment == PilotEnvironment.LIVE_PILOT &&
            (context.candidateIdentity.candidateCommitSha == ZERO_40_HEX ||
                context.candidateIdentity.apkSha256 == ZERO_64_HEX)
        ) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to
                "Placeholder all-zero candidate SHA or APK SHA-256 rejected"
        }
        if (context.environment == PilotEnvironment.LIVE_PILOT &&
            listOf(
                context.authorizationId,
                approval.approvedByProductOwner,
                approval.approvedByLeadMaintainer,
                approval.externalRecordRef,
                context.accountScope.authorizedAccountIdentifier,
                context.accountScope.authorizedAccountAlias,
                context.targetDevice.expectedTargetThingName,
                context.targetDevice.redactedTag
            ).any { isPlaceholderOrMockToken(it) }
        ) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to
                "Placeholder or synthetic mock authorization values rejected on LIVE_PILOT"
        }
        if (expectedRuntimeIdentity != null && context.candidateIdentity != expectedRuntimeIdentity) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to
                "Candidate commit SHA or APK identity mismatch"
        }
        if (runtimeAppIdentity != null) {
            if (context.environment == PilotEnvironment.LIVE_PILOT && !runtimeAppIdentity.pilotBuildEnabled) {
                return PilotAbortReason.AuthorizationExpiredOrInvalid to
                    "Live pilot authorization is disabled on non-pilot build"
            }
            if (context.candidateIdentity.applicationId != runtimeAppIdentity.applicationId ||
                context.candidateIdentity.versionName != runtimeAppIdentity.versionName
            ) {
                return PilotAbortReason.AuthorizationExpiredOrInvalid to
                    "Runtime application identity mismatch"
            }
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
        context.endpointConfig.endpointProvenanceRef?.let { provRef ->
            if (isPlaceholderOrMockToken(provRef) || provRef.trim().equals("UI_CONFIRMED", ignoreCase = true)) {
                return PilotAbortReason.UntrustedOriginRejected to
                    "Endpoint provenance reference is invalid or UI-confirmed"
            }
        }
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
            b.maxInitialWsConnections !in 0..PilotRequestBudgets.MAX_INITIAL_WS_CONNECTIONS ||
            b.maxWsReconnectAttempts !in 0..PilotRequestBudgets.MAX_WS_RECONNECT_ATTEMPTS ||
            b.httpCallTimeoutMs !in 1L..PilotRequestBudgets.MAX_HTTP_CALL_TIMEOUT_MS ||
            b.observationWindowMs !in 1L..PilotRequestBudgets.MAX_OBSERVATION_WINDOW_MS ||
            b.maxPhysicalRobotCommands != PilotRequestBudgets.MAX_PHYSICAL_ROBOT_COMMANDS
        ) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to
                "Pilot request budgets exceed SEC-404 maximum caps"
        }
        if (b.maxInitialWsConnections == 0 &&
            (b.maxWsReconnectAttempts != 0 || PilotOperation.WS_TELEMETRY_OBSERVE in context.permittedOperations)
        ) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to
                "Zero-WebSocket pilot budget requires maxWsReconnectAttempts=0 and excludes ws_telemetry_observe"
        }
        if (context.isStrictAuthAndInventoryOnly() &&
            b.observationWindowMs > PilotRequestBudgets.MAX_AUTH_INVENTORY_WINDOW_MS
        ) {
            return PilotAbortReason.AuthorizationExpiredOrInvalid to
                "FCT-004C auth/inventory window must not exceed 15 minutes (900000ms)"
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
        private val ZERO_40_HEX = "0".repeat(40)
        private val ZERO_64_HEX = "0".repeat(64)
        private val CALLING_CODE_REGEX = Regex("^(\\+|00)[0-9]{1,4}$")

        internal fun isPlaceholderOrMockToken(raw: String): Boolean {
            val upper = raw.trim().uppercase()
            return upper.isEmpty() ||
                upper.contains("PLACEHOLDER") ||
                upper.contains("PENDING") ||
                upper == "SYNTHETIC_MOCK_WORKFLOW_ONLY"
        }

        private fun normalizeTicketUrl(rawUrl: String, operation: PilotOperation): String {
            val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return rawUrl.trim()
            val rawScheme = uri.scheme?.lowercase().orEmpty()
            val scheme = if (operation == PilotOperation.WS_TELEMETRY_OBSERVE) {
                when (rawScheme) {
                    "wss" -> "https"
                    "ws" -> "http"
                    else -> rawScheme
                }
            } else {
                rawScheme
            }
            val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]").orEmpty()
            val port = when {
                uri.port in 1..65535 -> uri.port
                rawScheme == "https" || rawScheme == "wss" -> 443
                rawScheme == "http" || rawScheme == "ws" -> 80
                else -> -1
            }
            val path = uri.rawPath.orEmpty()
            return "$scheme://$host:$port$path"
        }

        private fun isValidPinnedOrigin(
            rawOrigin: String,
            expectedScheme: String,
            requirePort443: Boolean
        ): Boolean {
            val trimmed = rawOrigin.trim()
            if (trimmed.isEmpty() || trimmed.contains('@') || trimmed.contains('?') || trimmed.contains('#')) {
                return false
            }
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
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
