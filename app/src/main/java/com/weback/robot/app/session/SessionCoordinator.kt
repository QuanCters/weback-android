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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinates single-flight authentication, dual-clock lifetime enforcement, `sessionEpoch`
 * fencing, and serialized persistent storage commits (`IC-302`, `SEC-304`).
 *
 * Locking discipline:
 * - [commitLock] is held strictly for atomic in-memory state/epoch updates (never across I/O or delays).
 * - [persistenceMutex] serializes ALL `TokenStore` save/load/clear side effects in monotonic order
 *   alongside `sessionEpoch` validation so checking epoch before and after disk I/O is backed by
 *   true serialized write ordering without blocking `signOut()` behind network delays.
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

    suspend fun signIn(credentials: AuthCredentials): AuthResult {
        val accountKey = "${credentials.regionCallingCode.trim()}|${credentials.account.trim()}"

        // Collapse concurrent signIn calls for the exact same account into one dispatch,
        // while allowing a different account (Account A -> B switch) to supersede cleanly.
        val (joinExisting, attemptEpoch, myDeferred, listenersToNotify, previousEpochToInvalidate) = synchronized(commitLock) {
            val existing = inFlightSignInDeferred
            if (existing != null && !existing.isCompleted && inFlightSignInKey == accountKey) {
                SignInAdmission(existing, inFlightSignInEpoch, null, emptyList(), null)
            } else {
                val prevEpoch = activeEpoch
                val newEpoch = ++activeEpoch
                _sessionState.value = SessionState.Authenticating
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

    private data class SignInAdmission(
        val existing: CompletableDeferred<AuthResult>?,
        val attemptEpoch: Long,
        val deferred: CompletableDeferred<AuthResult>?,
        val epochListenersSnapshot: List<(Long) -> Unit>,
        val previousEpoch: Long?
    )

    private suspend fun commitAuthenticatedSession(
        session: SessionCredentials,
        capturedEpoch: Long,
        advanceEpochOnCommit: Boolean = false
    ): AuthResult {
        val snap = timeProvider.snapshot()
        val nowElapsed = snap.elapsedRealtimeMs
        if (TokenExpiryPolicy.evaluateInProcess(session, nowElapsed) == LifetimeEvaluation.EXPIRED_OR_INVALID) {
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

        val record = PersistedSessionRecord(
            jwtToken = session.jwtToken,
            apiUrl = session.apiUrl,
            wssUrl = session.wssUrl,
            regionName = session.regionName,
            expiresInSeconds = session.expiresInSeconds,
            issuedAtEpochMs = snap.wallEpochMs,
            lastSeenEpochMs = snap.wallEpochMs,
            issuedAtElapsedRealtimeMs = snap.elapsedRealtimeMs,
            bootGenerationId = snap.bootGenerationId,
            accountAliasRedacted = session.accountAliasRedacted,
            syntheticRefreshToken = session.syntheticRefreshToken
        )

        return persistenceMutex.withLock {
            // Check BEFORE touching disk inside serialized persistence worker (IC-302)
            val preWriteValid = synchronized(commitLock) { activeEpoch == capturedEpoch }
            if (!preWriteValid) {
                return@withLock AuthResult.Failure(
                    AuthFailureReason.TokenExpiredOrRevoked,
                    "Session superseded before durable persistence"
                )
            }

            val saveResult = withContext(NonCancellable) {
                tokenStore.save(record)
            }

            if (saveResult is TokenSaveResult.Failure) {
                val shouldWipePriorBlob = synchronized(commitLock) {
                    if (activeEpoch == capturedEpoch) {
                        _sessionState.value = SessionState.SignedOut
                    }
                    committedStorageEpoch < capturedEpoch
                }
                if (shouldWipePriorBlob) {
                    withContext(NonCancellable) { tokenStore.clear() }
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

            // Re-verify epoch inside persistenceMutex AFTER disk save
            val (committed, finalEpoch, epochListenersSnap) = synchronized(commitLock) {
                if (activeEpoch == capturedEpoch) {
                    val targetEpoch = if (advanceEpochOnCommit) ++activeEpoch else capturedEpoch
                    committedStorageEpoch = targetEpoch
                    _sessionState.value = stateMachine.toAuthenticated(session, targetEpoch)
                    Triple(true, targetEpoch, if (advanceEpochOnCommit) epochListeners.toList() else emptyList())
                } else {
                    Triple(false, capturedEpoch, emptyList())
                }
            }

            if (!committed) {
                // Epoch changed while write occurred; clean up stale record if nothing newer superseded storage
                withContext(NonCancellable) {
                    val stillSafeToWipe = synchronized(commitLock) { committedStorageEpoch <= capturedEpoch }
                    if (stillSafeToWipe) {
                        tokenStore.clear()
                    }
                }
                return@withLock AuthResult.Failure(
                    AuthFailureReason.TokenExpiredOrRevoked,
                    "Session invalidated during persistence"
                )
            }

            if (epochListenersSnap.isNotEmpty()) {
                notifyEpochListeners(finalEpoch, epochListenersSnap)
            }

            AuthResult.Success(session = session, isDurablePersisted = true)
        }
    }

    suspend fun signOut(): LogoutResult {
        val (prevEpoch, logoutEpoch, listeners, invListeners) = synchronized(commitLock) {
            val prev = activeEpoch
            val newEpoch = ++activeEpoch
            _sessionState.value = SessionState.SignedOut
            inFlightSignInDeferred = null
            inFlightSignInKey = null
            inFlightRefreshDeferred = null
            SignOutInfo(prev, newEpoch, epochListeners.toList(), invalidationListeners.toList())
        }
        notifyEpochListeners(logoutEpoch, listeners)
        notifyInvalidationListeners(prevEpoch, AuthFailureReason.TokenExpiredOrRevoked, invListeners)

        return persistenceMutex.withLock {
            val shouldClear = synchronized(commitLock) { committedStorageEpoch < logoutEpoch }
            if (!shouldClear) {
                return@withLock LogoutResult.DurableSuccess
            }
            val clearResult = withContext(NonCancellable) {
                tokenStore.clear()
            }
            when (clearResult) {
                TokenClearResult.Success -> LogoutResult.DurableSuccess
                is TokenClearResult.Failure -> {
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

    private data class SignOutInfo(
        val prevEpoch: Long,
        val logoutEpoch: Long,
        val epochListeners: List<(Long) -> Unit>,
        val invalidationListeners: List<(Long, AuthFailureReason) -> Unit>
    )

    suspend fun restoreSession(): SessionState {
        return persistenceMutex.withLock {
            val (restoreEpoch, epochListenersSnap) = synchronized(commitLock) {
                val e = ++activeEpoch
                e to epochListeners.toList()
            }
            notifyEpochListeners(restoreEpoch, epochListenersSnap)
            val loaded = tokenStore.load()
            when (loaded) {
                TokenLoadResult.Empty -> {
                    synchronized(commitLock) {
                        if (activeEpoch == restoreEpoch) {
                            _sessionState.value = SessionState.SignedOut
                        }
                        _sessionState.value
                    }
                }
                is TokenLoadResult.CorruptedOrInvalidated -> {
                    synchronized(commitLock) {
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
                    val snap = timeProvider.snapshot()
                    val decision = TokenExpiryPolicy.evaluatePersistedRestore(
                        record = loaded.record,
                        nowEpochMs = snap.wallEpochMs,
                        nowElapsedRealtimeMs = snap.elapsedRealtimeMs,
                        currentBootGenerationId = snap.bootGenerationId
                    )
                    when (decision) {
                        is PersistedRestoreDecision.Reauthenticate -> {
                            withContext(NonCancellable) { tokenStore.clear() }
                            synchronized(commitLock) {
                                if (activeEpoch == restoreEpoch) {
                                    _sessionState.value = stateMachine.toReauthenticationRequired(
                                        epoch = restoreEpoch,
                                        reason = AuthFailureReason.TokenExpiredOrRevoked
                                    )
                                }
                                _sessionState.value
                            }
                        }
                        is PersistedRestoreDecision.Restore -> {
                            val restoredSession = SessionCredentials(
                                jwtToken = loaded.record.jwtToken,
                                apiUrl = loaded.record.apiUrl,
                                wssUrl = loaded.record.wssUrl,
                                regionName = loaded.record.regionName,
                                expiresInSeconds = loaded.record.expiresInSeconds,
                                issuedAtElapsedMs = snap.elapsedRealtimeMs,
                                priorElapsedMs = decision.priorElapsedMs,
                                accountAliasRedacted = loaded.record.accountAliasRedacted,
                                syntheticRefreshToken = loaded.record.syntheticRefreshToken
                            )
                            synchronized(commitLock) {
                                if (activeEpoch == restoreEpoch) {
                                    committedStorageEpoch = restoreEpoch
                                    _sessionState.value = stateMachine.toAuthenticated(restoredSession, restoreEpoch)
                                }
                                _sessionState.value
                            }
                        }
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
                is AuthResult.Success -> commitAuthenticatedSession(
                    session = refreshed.session,
                    capturedEpoch = capturedEpoch,
                    advanceEpochOnCommit = true
                )
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

        persistenceMutex.withLock {
            val shouldWipe = synchronized(commitLock) { committedStorageEpoch < newEpoch }
            if (shouldWipe) {
                withContext(NonCancellable) {
                    tokenStore.clear()
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
