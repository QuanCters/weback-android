package com.weback.robot.domain.auth

import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.SessionCredentials
import com.weback.robot.domain.model.SessionState

/**
 * Pure deterministic helper governing session state transitions and monotonic epoch checks.
 */
class SessionStateMachine {
    val initialState: SessionState = SessionState.SignedOut

    fun canStartSignIn(current: SessionState): Boolean = when (current) {
        SessionState.SignedOut,
        is SessionState.ReauthenticationRequired,
        is SessionState.Authenticated -> true
        SessionState.Authenticating,
        is SessionState.Refreshing -> false
    }

    fun isEpochCurrent(expectedEpoch: Long, activeEpoch: Long): Boolean =
        expectedEpoch > 0L && expectedEpoch == activeEpoch

    fun toAuthenticated(session: SessionCredentials, epoch: Long): SessionState.Authenticated =
        SessionState.Authenticated(session = session, epoch = epoch)

    fun toRefreshing(current: SessionState.Authenticated): SessionState.Refreshing =
        SessionState.Refreshing(session = current.session, epoch = current.epoch)

    fun toReauthenticationRequired(
        epoch: Long,
        reason: AuthFailureReason
    ): SessionState.ReauthenticationRequired =
        SessionState.ReauthenticationRequired(reason = reason, epoch = epoch)
}
