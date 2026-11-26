package com.weback.robot.app.session

import com.weback.robot.core.BootSessionTimeProvider
import com.weback.robot.domain.auth.LifetimeEvaluation
import com.weback.robot.domain.auth.PersistedRestoreDecision
import com.weback.robot.domain.auth.SessionStateMachine
import com.weback.robot.domain.auth.TokenExpiryPolicy
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.LogoutResult
import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.domain.model.SessionCredentials
import com.weback.robot.domain.model.SessionState
import com.weback.robot.protocol.auth.AuthGateway
import com.weback.robot.protocol.auth.DisabledPasswordlessRefreshStrategy
import com.weback.robot.protocol.auth.RefreshGrantStrategy
import com.weback.robot.security.DiagnosticEvent
import com.weback.robot.security.DiagnosticLogger
import com.weback.robot.security.NoOpDiagnosticLogger
import com.weback.robot.security.TokenClearResult
import com.weback.robot.security.TokenLoadResult
import com.weback.robot.security.TokenSaveResult
import com.weback.robot.security.TokenStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinates single-flight authentication, dual-clock lifetime enforcement, `sessionEpoch`
 * fencing, explicit account replacement, and serialized persistent storage commits (`IC-302`, `SEC-304`).
 *
 * Locking discipline:
 * - [commitLock] is held strictly for atomic in-memory state/epoch updates (never across I/O or delays).
 * - [persistenceMutex] serializes ALL `TokenStore` save/load/clear side effects in monotonic order
 *   alongside `sessionEpoch` validation without blocking invalidations behind network delays.
 * - `signOut()` and `invalidateForEpoch()` wrap BOTH `persistenceMutex` acquisition and storage
 *   cleanup inside [NonCancellable] so cancellation while waiting for the lock cannot drop cleanup,
 *   without placing any network I/O inside a non-cancellable block.
 */
class SessionCoordinator(
    private val authGateway: AuthGateway,
    private val tokenStore: TokenStore,
    private val timeProvider: BootSessionTimeProvider,
    private val refreshStrategy: RefreshGrantStrategy = DisabledPasswordlessRefreshStrategy,
    private val logger: DiagnosticLogger = NoOpDiagnosticLogger
) {
    private val stateMachine = SessionStateMachine()
    private val commitLock = Any()
    private val persistenceMutex = Mutex()
    private val refreshMutex = Mutex()

    private val _sessionState = MutableStateFlow<SessionState>(stateMachine.initialState)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private var activeEpoch: Long = 1L
    private var committedStorageEpoch: Long = 0L
    private var requiresPriorStorageClearBeforeActivation: Boolean = false
    private var inFlightSignInKey: String? = null
    private var inFlightSignInEpoch: Long = 0L
    private var inFlightSignInDeferred: CompletableDeferred<AuthResult>? = null
    private var inFlightRefreshDeferred: CompletableDeferred<AuthResult>? = null

    private val epochListeners = mutableListOf<(Long) -> Unit>()
    private val invalidationListeners = mutableListOf<(Long, AuthFailureReason) -> Unit>()

    fun currentEpoch(): Long = synchronized(commitLock) { activeEpoch }

    fun registerOnEpochAdvanced(listener: (Long) -> Unit) {
        synchronized(commitLock) {
            epochListeners.add(listener)
        }
    }

    fun registerOnSessionInvalidated(listener: (Long, AuthFailureReason) -> Unit) {
        synchronized(commitLock) {
            invalidationListeners.add(listener)
        }
    }

    private fun notifyEpochListeners(newEpoch: Long, snapshotListeners: List<(Long) -> Unit>) {
        snapshotListeners.forEach { runCatching { it(newEpoch) } }
    }

    private fun notifyInvalidationListeners(
        invalidatedEpoch: Long,
        reason: AuthFailureReason,
        snapshotListeners: List<(Long, AuthFailureReason) -> Unit>
    ) {
        snapshotListeners.forEach { runCatching { it(invalidatedEpoch, reason) } }
    }

    /**
     * Explicitly switches/replaces the active account session, revoking prior in-memory authorization
     * and wiping prior persistent session storage before initiating or activating the replacement login.
     */
    suspend fun switchAccount(credentials: AuthCredentials): AuthResult = signIn(credentials)

    suspend fun signIn(credentials: AuthCredentials): AuthResult {
        val accountKey = "${credentials.regionCallingCode.trim()}|${credentials.account.trim()}"

        // Collapse concurrent signIn calls for the exact same account into one dispatch,
        // while allowing a different account (Account A -> B replacement) to supersede cleanly.
        val (joinExisting, attemptEpoch, myDeferred, listenersToNotify, previousEpochToInvalidate) = synchronized(commitLock) {
            val existing = inFlightSignInDeferred
            if (existing != null && !existing.isCompleted && inFlightSignInKey == accountKey) {
                SignInAdmission(existing, inFlightSignInEpoch, null, emptyList(), null)
            } else {
                val hasActiveOrPrior = activeEpoch > 1L || _sessionState.value !is SessionState.SignedOut
                val prevEpoch = if (hasActiveOrPrior) activeEpoch else null
                val newEpoch = ++activeEpoch
                _sessionState.value = SessionState.Authenticating
                inFlightRefreshDeferred = null
                val deferred = CompletableDeferred<AuthResult>()
                inFlightSignInKey = accountKey
                inFlightSignInEpoch = newEpoch
                inFlightSignInDeferred = deferred
                SignInAdmission(null, newEpoch, deferred, epochListeners.toList(), prevEpoch)
            }
        }

        // Notify listeners strictly OUTSIDE commitLock to avoid AB-BA deadlocks
        if (listenersToNotify.isNotEmpty()) {
            notifyEpochListeners(attemptEpoch, listenersToNotify)
        }
        if (previousEpochToInvalidate != null) {
            val invListeners = synchronized(commitLock) { invalidationListeners.toList() }
            notifyInvalidationListeners(previousEpochToInvalidate, AuthFailureReason.TokenExpiredOrRevoked, invListeners)
        }

        if (joinExisting != null) {
            return joinExisting.await()
        }

        val deferred = myDeferred!!
        return try {
            // Invalidate any prior account storage BEFORE attempting replacement auth or activating a new session
            val preAuthClearError = clearPriorStorageBeforeReplacement(attemptEpoch)
            if (preAuthClearError != null) {
                credentials.wipeSecret()
                deferred.complete(preAuthClearError)
                return preAuthClearError
            }

            val gatewayResult = try {
                authGateway.signIn(credentials)
            } finally {
                credentials.wipeSecret()
            }

            val finalResult = when (gatewayResult) {
                is AuthResult.Failure -> {
                    synchronized(commitLock) {
                        if (activeEpoch == attemptEpoch) {
                            _sessionState.value = SessionState.SignedOut
                            inFlightSignInDeferred = null
                        }
                    }
                    gatewayResult
                }
                is AuthResult.Success -> commitAuthenticatedSession(
                    session = gatewayResult.session,
                    capturedEpoch = attemptEpoch,
                    advanceEpochOnCommit = false
                )
            }
            deferred.complete(finalResult)
            finalResult
        } catch (t: Throwable) {
            val err = AuthResult.Failure(AuthFailureReason.MalformedResponse, "Sign-in interrupted")
            synchronized(commitLock) {
                if (activeEpoch == attemptEpoch && _sessionState.value == SessionState.Authenticating) {
                    _sessionState.value = SessionState.SignedOut
                }
                if (inFlightSignInDeferred === deferred) {
                    inFlightSignInDeferred = null
                }
            }
            deferred.complete(err)
            if (t is kotlinx.coroutines.CancellationException) throw t
            err
        } finally {
            synchronized(commitLock) {
                if (inFlightSignInDeferred === deferred) {
                    inFlightSignInDeferred = null
                    inFlightSignInKey = null
                }
            }
        }
    }

    private suspend fun clearPriorStorageBeforeReplacement(attemptEpoch: Long): AuthResult.Failure? {
        return withContext(NonCancellable) {
            persistenceMutex.withLock {
                val (stillCurrent, mustWipe) = synchronized(commitLock) {
                    val current = activeEpoch == attemptEpoch
                    val dirtyOrCommitted = requiresPriorStorageClearBeforeActivation || committedStorageEpoch in 1 until attemptEpoch
                    current to (current && dirtyOrCommitted)
                }
                if (!stillCurrent) {
                    return@withLock AuthResult.Failure(
                        AuthFailureReason.TokenExpiredOrRevoked,
                        "Session superseded prior to replacement storage cleanup"
                    )
                }
                if (!mustWipe) {
                    return@withLock null
                }
                when (val clearRes = tokenStore.clear()) {
                    TokenClearResult.Success -> {
                        synchronized(commitLock) {
                            requiresPriorStorageClearBeforeActivation = false
                            if (committedStorageEpoch < attemptEpoch) {
                                committedStorageEpoch = 0L
                            }
                        }
                        null
                    }
                    is TokenClearResult.Failure -> {
                        synchronized(commitLock) {
                            requiresPriorStorageClearBeforeActivation = true
                            if (activeEpoch == attemptEpoch) {
                                _sessionState.value = SessionState.SignedOut
                            }
                        }
                        logger.log(
                            DiagnosticEvent(
                                category = "REPLACEMENT_CLEAR_FAILED",
                                sessionEpoch = attemptEpoch,
                                errorCategory = clearRes.safeCategory
                            )
                        )
                        AuthResult.Failure(
                            AuthFailureReason.StorageFailure,
                            "Prior session cleanup failed (${clearRes.safeCategory})"
                        )
                    }
                }
            }
        }
    }

    private data class SignInAdmission(
        val existing: CompletableDeferred<AuthResult>?,
        val attemptEpoch: Long,
        val deferred: CompletableDeferred<AuthResult>?,
        val epochListenersSnapshot: List<(Long) -> Unit>,
        val previousEpoch: Long?
    )

    private enum class CommitOutcome {
        COMMITTED,
        EXPIRED_AFTER_SAVE,
        SUPERSEDED_OR_CANCELLED
    }

    private suspend fun commitAuthenticatedSession(
        session: SessionCredentials,
        capturedEpoch: Long,
        advanceEpochOnCommit: Boolean = false
    ): AuthResult {
        val preSnap = timeProvider.snapshot()
        if (TokenExpiryPolicy.evaluateInProcess(session, preSnap.elapsedRealtimeMs) == LifetimeEvaluation.EXPIRED_OR_INVALID) {
            synchronized(commitLock) {
                if (activeEpoch == capturedEpoch) {
                    _sessionState.value = SessionState.ReauthenticationRequired(
                        AuthFailureReason.TokenExpiredOrRevoked,
                        capturedEpoch
                    )
                }
            }
            return AuthResult.Failure(AuthFailureReason.TokenExpiredOrRevoked, "Received expired or invalid session")
        }

        return persistenceMutex.withLock {
            // Check ownership & expiry BEFORE touching disk inside serialized persistence worker (IC-302, FIX-303)
            val preWriteValid = synchronized(commitLock) { activeEpoch == capturedEpoch } && currentCoroutineContext().isActive
            if (!preWriteValid) {
                return@withLock AuthResult.Failure(
                    AuthFailureReason.TokenExpiredOrRevoked,
                    "Session superseded before durable persistence"
                )
            }

            val writeSnap = timeProvider.snapshot()
            if (writeSnap.wallEpochMs <= 0L ||
                TokenExpiryPolicy.evaluateInProcess(session, writeSnap.elapsedRealtimeMs) == LifetimeEvaluation.EXPIRED_OR_INVALID
            ) {
                val shouldWipePrior = synchronized(commitLock) {
                    if (activeEpoch == capturedEpoch) {
                        _sessionState.value = stateMachine.toReauthenticationRequired(
                            epoch = capturedEpoch,
                            reason = AuthFailureReason.TokenExpiredOrRevoked
                        )
                    }
                    requiresPriorStorageClearBeforeActivation || committedStorageEpoch in 1 until capturedEpoch
                }
                if (shouldWipePrior) {
                    withContext(NonCancellable) {
                        if (tokenStore.clear() is TokenClearResult.Success) {
                            synchronized(commitLock) { requiresPriorStorageClearBeforeActivation = false }
                        }
                    }
                }
                return@withLock AuthResult.Failure(
                    AuthFailureReason.TokenExpiredOrRevoked,
                    "Session expired or invalid prior to persistence"
                )
            }

            // Preserve original token lifetime anchor (`session.issuedAtElapsedMs` + `session.priorElapsedMs`)
            // rather than overwriting elapsed lifetime with persistence-time snapshot (`FIX-303`).
            val record = PersistedSessionRecord(
                jwtToken = session.jwtToken,
                apiUrl = session.apiUrl,
                wssUrl = session.wssUrl,
                regionName = session.regionName,
                expiresInSeconds = session.expiresInSeconds,
                issuedAtEpochMs = writeSnap.wallEpochMs,
                lastSeenEpochMs = writeSnap.wallEpochMs,
                issuedAtElapsedRealtimeMs = session.issuedAtElapsedMs,
                bootGenerationId = writeSnap.bootGenerationId,
                accountAliasRedacted = session.accountAliasRedacted,
                syntheticRefreshToken = session.syntheticRefreshToken,
                priorElapsedMs = session.priorElapsedMs
            )

            val saveResult = withContext(NonCancellable) {
                tokenStore.save(record)
            }

            if (saveResult is TokenSaveResult.Failure) {
                val shouldWipePriorBlob = synchronized(commitLock) {
                    if (activeEpoch == capturedEpoch) {
                        _sessionState.value = SessionState.SignedOut
                    }
                    requiresPriorStorageClearBeforeActivation || committedStorageEpoch in 1 until capturedEpoch
                }
                if (shouldWipePriorBlob) {
                    withContext(NonCancellable) {
                        when (tokenStore.clear()) {
                            TokenClearResult.Success -> synchronized(commitLock) {
                                requiresPriorStorageClearBeforeActivation = false
                                if (committedStorageEpoch < capturedEpoch) committedStorageEpoch = 0L
                            }
                            is TokenClearResult.Failure -> synchronized(commitLock) {
                                requiresPriorStorageClearBeforeActivation = true
                            }
                        }
                    }
                }
                logger.log(
                    DiagnosticEvent(
                        category = "TOKEN_SAVE_FAILED",
                        sessionEpoch = capturedEpoch,
                        errorCategory = saveResult.safeCategory
                    )
                )
                return@withLock AuthResult.Failure(
                    AuthFailureReason.StorageFailure,
                    "Unable to persist session securely (${saveResult.safeCategory})"
                )
            }

            // Re-verify epoch, cancellation state, AND post-save token expiration (`FIX-303`)
            val postSaveSnap = timeProvider.snapshot()
            val postEval = TokenExpiryPolicy.evaluateInProcess(session, postSaveSnap.elapsedRealtimeMs)
            val callerActive = currentCoroutineContext().isActive

            val (outcome, finalEpoch, epochListenersSnap) = synchronized(commitLock) {
                when {
                    activeEpoch == capturedEpoch && callerActive && postEval != LifetimeEvaluation.EXPIRED_OR_INVALID -> {
                        val targetEpoch = if (advanceEpochOnCommit) ++activeEpoch else capturedEpoch
                        committedStorageEpoch = targetEpoch
                        requiresPriorStorageClearBeforeActivation = true
                        _sessionState.value = stateMachine.toAuthenticated(session, targetEpoch)
                        Triple(
                            CommitOutcome.COMMITTED,
                            targetEpoch,
                            if (advanceEpochOnCommit) epochListeners.toList() else emptyList()
                        )
                    }
                    activeEpoch == capturedEpoch && postEval == LifetimeEvaluation.EXPIRED_OR_INVALID -> {
                        _sessionState.value = stateMachine.toReauthenticationRequired(
                            epoch = capturedEpoch,
                            reason = AuthFailureReason.TokenExpiredOrRevoked
                        )
                        requiresPriorStorageClearBeforeActivation = true
                        Triple(CommitOutcome.EXPIRED_AFTER_SAVE, capturedEpoch, emptyList())
                    }
                    else -> {
                        Triple(CommitOutcome.SUPERSEDED_OR_CANCELLED, capturedEpoch, emptyList())
                    }
                }
            }

            if (outcome != CommitOutcome.COMMITTED) {
                // Clean up stale or expired record if nothing newer committed storage
                withContext(NonCancellable) {
                    val stillSafeToWipe = synchronized(commitLock) { committedStorageEpoch <= capturedEpoch }
                    if (stillSafeToWipe) {
                        when (tokenStore.clear()) {
                            TokenClearResult.Success -> synchronized(commitLock) {
                                requiresPriorStorageClearBeforeActivation = false
                                if (committedStorageEpoch <= capturedEpoch) committedStorageEpoch = 0L
                            }
                            is TokenClearResult.Failure -> synchronized(commitLock) {
                                requiresPriorStorageClearBeforeActivation = true
                            }
                        }
                    }
                }
                return@withLock AuthResult.Failure(
                    AuthFailureReason.TokenExpiredOrRevoked,
                    if (outcome == CommitOutcome.EXPIRED_AFTER_SAVE) {
                        "Session expired during persistence"
                    } else {
                        "Session invalidated during persistence"
                    }
                )
            }

            if (epochListenersSnap.isNotEmpty()) {
                notifyEpochListeners(finalEpoch, epochListenersSnap)
            }

            AuthResult.Success(session = session, isDurablePersisted = true)
        }
    }

    suspend fun signOut(): LogoutResult = signOutInternal(expectedEpoch = null)!!

    /**
     * Signs out ONLY if [activeEpoch] still equals [expectedEpoch], preventing stale pilot
     * callbacks from signing out a newer session (`FIX-405`). Returns `null` when epoch moved on.
     */
    suspend fun signOutIfEpoch(expectedEpoch: Long): LogoutResult? =
        signOutInternal(expectedEpoch = expectedEpoch)

    private suspend fun signOutInternal(expectedEpoch: Long?): LogoutResult? {
        val info = synchronized(commitLock) {
            if (expectedEpoch != null && activeEpoch != expectedEpoch) {
                null
            } else {
                val prev = activeEpoch
                val newEpoch = ++activeEpoch
                _sessionState.value = SessionState.SignedOut
                inFlightSignInDeferred = null
                inFlightSignInKey = null
                inFlightRefreshDeferred = null
                SignOutInfo(prev, newEpoch, epochListeners.toList(), invalidationListeners.toList())
            }
        } ?: return null

        val (prevEpoch, logoutEpoch, listeners, invListeners) = info
        notifyEpochListeners(logoutEpoch, listeners)
        notifyInvalidationListeners(prevEpoch, AuthFailureReason.TokenExpiredOrRevoked, invListeners)

        // Wrap persistenceMutex acquisition + clear() inside NonCancellable (`FIX-301`)
        return withContext(NonCancellable) {
            persistenceMutex.withLock {
                val shouldClear = synchronized(commitLock) {
                    requiresPriorStorageClearBeforeActivation || committedStorageEpoch < logoutEpoch
                }
                if (!shouldClear) {
                    return@withLock LogoutResult.DurableSuccess
                }
                when (val clearResult = tokenStore.clear()) {
                    TokenClearResult.Success -> {
                        synchronized(commitLock) {
                            requiresPriorStorageClearBeforeActivation = false
                            if (committedStorageEpoch < logoutEpoch) {
                                committedStorageEpoch = 0L
                            }
                        }
                        LogoutResult.DurableSuccess
                    }
                    is TokenClearResult.Failure -> {
                        synchronized(commitLock) {
                            requiresPriorStorageClearBeforeActivation = true
                        }
                        logger.log(
                            DiagnosticEvent(
                                category = "LOGOUT_CLEAR_FAILED",
                                sessionEpoch = logoutEpoch,
                                errorCategory = clearResult.safeCategory
                            )
                        )
                        LogoutResult.IncompleteStorageClear(clearResult.safeCategory)
                    }
                }
            }
        }
    }

    private data class SignOutInfo(
        val prevEpoch: Long,
        val logoutEpoch: Long,
        val epochListeners: List<(Long) -> Unit>,
        val invalidationListeners: List<(Long, AuthFailureReason) -> Unit>
    )

    /**
     * Inspects any persisted session artifact on startup and conservatively transitions to
     * [SessionState.ReauthenticationRequired] (never silently restoring an active [SessionState.Authenticated]
     * session in FCT-003 so combined storage/tombstone/Keystore deletion failures cannot resurrect
     * logged-out credentials on restart, `FIX-302`).
     */
    suspend fun restoreSession(): SessionState {
        return persistenceMutex.withLock {
            val (restoreEpoch, epochListenersSnap) = synchronized(commitLock) {
                val e = ++activeEpoch
                e to epochListeners.toList()
            }
            notifyEpochListeners(restoreEpoch, epochListenersSnap)
            when (tokenStore.load()) {
                TokenLoadResult.Empty -> {
                    synchronized(commitLock) {
                        requiresPriorStorageClearBeforeActivation = false
                        if (activeEpoch == restoreEpoch) {
                            _sessionState.value = SessionState.SignedOut
                        }
                        _sessionState.value
                    }
                }
                is TokenLoadResult.CorruptedOrInvalidated -> {
                    synchronized(commitLock) {
                        requiresPriorStorageClearBeforeActivation = true
                        if (activeEpoch == restoreEpoch) {
                            _sessionState.value = stateMachine.toReauthenticationRequired(
                                epoch = restoreEpoch,
                                reason = AuthFailureReason.StorageFailure
                            )
                        }
                        _sessionState.value
                    }
                }
                is TokenLoadResult.Loaded -> {
                    val clearResult = withContext(NonCancellable) { tokenStore.clear() }
                    synchronized(commitLock) {
                        requiresPriorStorageClearBeforeActivation = clearResult is TokenClearResult.Failure
                        if (clearResult is TokenClearResult.Success) {
                            committedStorageEpoch = 0L
                        }
                        if (activeEpoch == restoreEpoch) {
                            val reason = if (clearResult is TokenClearResult.Failure) {
                                AuthFailureReason.StorageFailure
                            } else {
                                AuthFailureReason.TokenExpiredOrRevoked
                            }
                            _sessionState.value = stateMachine.toReauthenticationRequired(
                                epoch = restoreEpoch,
                                reason = reason
                            )
                        }
                        _sessionState.value
                    }
                }
            }
        }
    }

    suspend fun refreshIfNeeded(force: Boolean = false): AuthResult {
        val (existingRefresh, snapshotState) = synchronized(commitLock) {
            inFlightRefreshDeferred to _sessionState.value
        }
        if (existingRefresh != null && !existingRefresh.isCompleted) {
            return existingRefresh.await()
        }

        val currentAuth = snapshotState as? SessionState.Authenticated
            ?: return AuthResult.Failure(AuthFailureReason.TokenExpiredOrRevoked, "No active session to refresh")

        val eval = TokenExpiryPolicy.evaluateInProcess(
            currentAuth.session,
            timeProvider.snapshot().elapsedRealtimeMs
        )
        if (eval == LifetimeEvaluation.EXPIRED_OR_INVALID) {
            invalidateForEpoch(currentAuth.epoch, AuthFailureReason.TokenExpiredOrRevoked)
            return AuthResult.Failure(AuthFailureReason.TokenExpiredOrRevoked, "Session already expired")
        }
        if (eval == LifetimeEvaluation.VALID && !force) {
            return AuthResult.Success(currentAuth.session)
        }

        if (!refreshStrategy.isEnabled) {
            invalidateForEpoch(currentAuth.epoch, AuthFailureReason.TokenExpiredOrRevoked)
            return AuthResult.Failure(
                AuthFailureReason.TokenExpiredOrRevoked,
                "Reauthentication required; refresh grant disabled"
            )
        }

        val (deferred, capturedEpoch, sessionToRefresh) = refreshMutex.withLock {
            synchronized(commitLock) {
                val activeDeferred = inFlightRefreshDeferred
                if (activeDeferred != null && !activeDeferred.isCompleted) {
                    return@synchronized Triple(activeDeferred, currentAuth.epoch, null as SessionCredentials?)
                }
                val live = _sessionState.value as? SessionState.Authenticated
                    ?: return@synchronized Triple(null, 0L, null)
                val newDeferred = CompletableDeferred<AuthResult>()
                _sessionState.value = stateMachine.toRefreshing(live)
                inFlightRefreshDeferred = newDeferred
                Triple(newDeferred, live.epoch, live.session)
            }
        }

        if (deferred == null) {
            return AuthResult.Failure(AuthFailureReason.TokenExpiredOrRevoked, "No active session to refresh")
        }
        if (sessionToRefresh == null) {
            return deferred.await()
        }

        return try {
            val refreshed = refreshStrategy.refresh(sessionToRefresh)
            val settled = when (refreshed) {
                is AuthResult.Failure -> {
                    invalidateForEpoch(capturedEpoch, refreshed.reason)
                    refreshed
                }
                is AuthResult.Success -> {
                    // Only grant a new lifetime if the gateway actually issued a distinct new session token (`FIX-303`)
                    val effectiveSession = if (refreshed.session.jwtToken == sessionToRefresh.jwtToken) {
                        refreshed.session.copy(
                            issuedAtElapsedMs = sessionToRefresh.issuedAtElapsedMs,
                            priorElapsedMs = sessionToRefresh.priorElapsedMs,
                            expiresInSeconds = minOf(refreshed.session.expiresInSeconds, sessionToRefresh.expiresInSeconds)
                        )
                    } else {
                        refreshed.session
                    }
                    commitAuthenticatedSession(
                        session = effectiveSession,
                        capturedEpoch = capturedEpoch,
                        advanceEpochOnCommit = true
                    )
                }
            }
            deferred.complete(settled)
            settled
        } catch (t: Throwable) {
            val fail = AuthResult.Failure(AuthFailureReason.TokenExpiredOrRevoked, "Refresh interrupted")
            invalidateForEpoch(capturedEpoch, AuthFailureReason.TokenExpiredOrRevoked)
            deferred.complete(fail)
            if (t is kotlinx.coroutines.CancellationException) throw t
            fail
        } finally {
            synchronized(commitLock) {
                if (inFlightRefreshDeferred === deferred) {
                    inFlightRefreshDeferred = null
                }
            }
        }
    }

    /**
     * Checks immediate pre-send authorization and lifetime before any authenticated call.
     */
    fun authorizeActiveSessionForSend(): SessionState.Authenticated? {
        val current = synchronized(commitLock) { _sessionState.value } as? SessionState.Authenticated
            ?: return null
        val eval = TokenExpiryPolicy.evaluateInProcess(current.session, timeProvider.snapshot().elapsedRealtimeMs)
        return if (eval == LifetimeEvaluation.EXPIRED_OR_INVALID) {
            null
        } else {
            current
        }
    }

    suspend fun invalidateForEpoch(
        expectedEpoch: Long,
        reason: AuthFailureReason
    ) {
        val (didInvalidate, newEpoch, listeners, invListeners) = synchronized(commitLock) {
            if (!stateMachine.isEpochCurrent(expectedEpoch, activeEpoch)) {
                InvalidationSnap(false, activeEpoch, emptyList(), emptyList())
            } else {
                val advanced = ++activeEpoch
                _sessionState.value = stateMachine.toReauthenticationRequired(advanced, reason)
                inFlightSignInDeferred = null
                inFlightSignInKey = null
                inFlightRefreshDeferred = null
                InvalidationSnap(true, advanced, epochListeners.toList(), invalidationListeners.toList())
            }
        }
        if (!didInvalidate) return
        notifyEpochListeners(newEpoch, listeners)
        notifyInvalidationListeners(expectedEpoch, reason, invListeners)

        // Wrap persistenceMutex acquisition + clear() inside NonCancellable (`FIX-301`)
        withContext(NonCancellable) {
            persistenceMutex.withLock {
                val shouldWipe = synchronized(commitLock) {
                    requiresPriorStorageClearBeforeActivation || committedStorageEpoch < newEpoch
                }
                if (shouldWipe) {
                    when (val clearRes = tokenStore.clear()) {
                        TokenClearResult.Success -> synchronized(commitLock) {
                            requiresPriorStorageClearBeforeActivation = false
                            if (committedStorageEpoch < newEpoch) committedStorageEpoch = 0L
                        }
                        is TokenClearResult.Failure -> {
                            synchronized(commitLock) {
                                requiresPriorStorageClearBeforeActivation = true
                            }
                            logger.log(
                                DiagnosticEvent(
                                    category = "INVALIDATION_CLEAR_FAILED",
                                    sessionEpoch = newEpoch,
                                    errorCategory = clearRes.safeCategory
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private data class InvalidationSnap(
        val didInvalidate: Boolean,
        val newEpoch: Long,
        val epochListeners: List<(Long) -> Unit>,
        val invalidationListeners: List<(Long, AuthFailureReason) -> Unit>
    )
}
