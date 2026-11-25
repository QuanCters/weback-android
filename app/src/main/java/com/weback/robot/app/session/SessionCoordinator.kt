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

    fun currentEpoch(): Long = synchronized(commitLock) { activeEpoch }

    fun registerOnEpochAdvanced(listener: (Long) -> Unit) {
        synchronized(commitLock) {
            epochListeners.add(listener)
        }
    }

    private fun notifyEpochListenersLocked(newEpoch: Long, snapshotListeners: List<(Long) -> Unit>) {
        snapshotListeners.forEach { runCatching { it(newEpoch) } }
    }

    suspend fun signIn(credentials: AuthCredentials): AuthResult {
        val accountKey = "${credentials.regionCallingCode.trim()}|${credentials.account.trim()}"

        // Collapse concurrent signIn calls for the exact same account into one dispatch,
        // while allowing a different account (Account A -> B switch) to supersede cleanly.
        val (joinExisting, attemptEpoch, myDeferred) = synchronized(commitLock) {
            val existing = inFlightSignInDeferred
            if (existing != null && !existing.isCompleted && inFlightSignInKey == accountKey) {
                Triple(existing, inFlightSignInEpoch, null)
            } else {
                val newEpoch = ++activeEpoch
                _sessionState.value = SessionState.Authenticating
                val listeners = epochListeners.toList()
                notifyEpochListenersLocked(newEpoch, listeners)
                val deferred = CompletableDeferred<AuthResult>()
                inFlightSignInKey = accountKey
                inFlightSignInEpoch = newEpoch
                inFlightSignInDeferred = deferred
                Triple(null, newEpoch, deferred)
            }
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
                    capturedEpoch = attemptEpoch
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

    private suspend fun commitAuthenticatedSession(
        session: SessionCredentials,
        capturedEpoch: Long
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
                synchronized(commitLock) {
                    if (activeEpoch == capturedEpoch) {
                        _sessionState.value = SessionState.SignedOut
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

            // Re-verify epoch inside persistenceMutex AFTER disk save
            val committed = synchronized(commitLock) {
                if (activeEpoch == capturedEpoch) {
                    committedStorageEpoch = capturedEpoch
                    _sessionState.value = stateMachine.toAuthenticated(session, capturedEpoch)
                    true
                } else {
                    false
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

            AuthResult.Success(session = session, isDurablePersisted = true)
        }
    }

    suspend fun signOut(): LogoutResult {
        val (logoutEpoch, listeners) = synchronized(commitLock) {
            val newEpoch = ++activeEpoch
            _sessionState.value = SessionState.SignedOut
            inFlightSignInDeferred = null
            inFlightSignInKey = null
            inFlightRefreshDeferred = null
            newEpoch to epochListeners.toList()
        }
        notifyEpochListenersLocked(logoutEpoch, listeners)

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

    suspend fun restoreSession(): SessionState {
        return persistenceMutex.withLock {
            val restoreEpoch = synchronized(commitLock) { ++activeEpoch }
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
                    capturedEpoch = capturedEpoch
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
        val (didInvalidate, newEpoch, listeners) = synchronized(commitLock) {
            if (!stateMachine.isEpochCurrent(expectedEpoch, activeEpoch)) {
                Triple(false, activeEpoch, emptyList())
            } else {
                val advanced = ++activeEpoch
                _sessionState.value = stateMachine.toReauthenticationRequired(advanced, reason)
                inFlightSignInDeferred = null
                inFlightSignInKey = null
                inFlightRefreshDeferred = null
                Triple(true, advanced, epochListeners.toList())
            }
        }
        if (!didInvalidate) return
        notifyEpochListenersLocked(newEpoch, listeners)

        persistenceMutex.withLock {
            val shouldWipe = synchronized(commitLock) { committedStorageEpoch < newEpoch }
            if (shouldWipe) {
                withContext(NonCancellable) {
                    tokenStore.clear()
                }
            }
        }
    }
}
