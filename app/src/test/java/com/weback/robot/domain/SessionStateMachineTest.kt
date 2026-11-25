package com.weback.robot.domain

import com.weback.robot.domain.auth.SessionStateMachine
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.SessionCredentials
import com.weback.robot.domain.model.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {

    private fun session(token: String = "tok-1") = SessionCredentials(
        jwtToken = token,
        apiUrl = "http://127.0.0.1:8080/api",
        wssUrl = "ws://127.0.0.1:8080/ws",
        regionName = "us-east-1",
        expiresInSeconds = 3600L,
        issuedAtElapsedMs = 100L,
        priorElapsedMs = 0L,
        accountAliasRedacted = "u***@example.invalid"
    )

    @Test
    fun testValidStateTransitionsAndEpochFencing() {
        val sm = SessionStateMachine()
        assertEquals(SessionState.SignedOut, sm.initialState)

        assertTrue(sm.canStartSignIn(SessionState.SignedOut))
        assertTrue(sm.canStartSignIn(SessionState.ReauthenticationRequired(AuthFailureReason.TokenExpiredOrRevoked, 1L)))
        assertTrue(sm.canStartSignIn(SessionState.Authenticated(session(), 1L))) // account switch allowed
        assertFalse(sm.canStartSignIn(SessionState.Authenticating))

        assertTrue(sm.isEpochCurrent(expectedEpoch = 2L, activeEpoch = 2L))
        assertFalse(sm.isEpochCurrent(expectedEpoch = 1L, activeEpoch = 2L))
        assertFalse(sm.isEpochCurrent(expectedEpoch = 0L, activeEpoch = 0L))

        val authed = SessionState.Authenticated(session(), epoch = 2L)
        val refreshing = sm.toRefreshing(authed)
        assertEquals(2L, refreshing.epoch)

        val reauth = sm.toReauthenticationRequired(epoch = 3L, reason = AuthFailureReason.TokenExpiredOrRevoked)
        assertEquals(SessionState.ReauthenticationRequired(AuthFailureReason.TokenExpiredOrRevoked, 3L), reauth)
    }
}
