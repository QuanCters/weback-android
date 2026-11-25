package com.weback.robot.app.session

import com.weback.robot.core.BootSessionTimeProvider
import com.weback.robot.core.BootTimeSnapshot
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.LogoutResult
import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.domain.model.SessionCredentials
import com.weback.robot.domain.model.SessionState
import com.weback.robot.protocol.auth.AuthGateway
import com.weback.robot.security.TokenClearResult
import com.weback.robot.security.TokenLoadResult
import com.weback.robot.security.TokenSaveResult
import com.weback.robot.security.TokenStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SessionCoordinatorTest {

    private class FakeTimeProvider(
        var bootId: String? = "boot_1",
        var elapsedMs: Long = 10_000L,
        var wallMs: Long = 1_700_000_000_000L
    ) : BootSessionTimeProvider {
        override fun snapshot() = BootTimeSnapshot(bootId, elapsedMs, wallMs)
    }

    @Test
    fun testConcurrentSignInCollapsesAndLogoutDuringLoginDiscardsPersistence() = runTest {
        val dispatchCount = AtomicInteger(0)
        val loginGate = CompletableDeferred<AuthResult>()
        val time = FakeTimeProvider()
        val storedRecord = mutableListOf<PersistedSessionRecord>()

        val authGateway = object : AuthGateway {
            override suspend fun signIn(credentials: AuthCredentials): AuthResult {
                dispatchCount.incrementAndGet()
                return loginGate.await()
            }
        }
        val tokenStore = object : TokenStore {
            override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
                storedRecord.add(record)
                return TokenSaveResult.Success
            }
            override suspend fun load(): TokenLoadResult = TokenLoadResult.Empty
            override suspend fun clear(): TokenClearResult {
                storedRecord.clear()
                return TokenClearResult.Success
            }
        }

        val coordinator = SessionCoordinator(
            authGateway = authGateway,
            tokenStore = tokenStore,
            timeProvider = time
        )

        val creds = AuthCredentials("+1", "user@example.invalid", "pw".toCharArray())
        val d1 = async { coordinator.signIn(creds) }
        val d2 = async { coordinator.signIn(AuthCredentials("+1", "user@example.invalid", "pw".toCharArray())) }
        testScheduler.advanceUntilIdle()
        assertEquals(1, dispatchCount.get())

        // Logout while login is suspended in flight
        val logoutRes = coordinator.signOut()
        assertEquals(LogoutResult.DurableSuccess, logoutRes)
        assertEquals(SessionState.SignedOut, coordinator.sessionState.value)

        // Login finishes late -> must NOT become Authenticated or save stale record
        loginGate.complete(
            AuthResult.Success(
                SessionCredentials("tok", "http://127.0.0.1:80/api", "ws://127.0.0.1:80/ws", "us", 3600L, 10_000L)
            )
        )
        val results = listOf(d1, d2).awaitAll()
        assertTrue(results.all { it is AuthResult.Failure })
        assertEquals(SessionState.SignedOut, coordinator.sessionState.value)
        assertTrue(storedRecord.isEmpty())
    }

    @Test
    fun testFailedSaveAndFailedClearFailClosed() = runTest {
        val time = FakeTimeProvider()
        val authGateway = object : AuthGateway {
            override suspend fun signIn(credentials: AuthCredentials): AuthResult =
                AuthResult.Success(
                    SessionCredentials("tok", "http://127.0.0.1:80/api", "ws://127.0.0.1:80/ws", "us", 3600L, 10_000L)
                )
        }
        var failSave = true
        var failClear = true
        val tokenStore = object : TokenStore {
            override suspend fun save(record: PersistedSessionRecord): TokenSaveResult =
                if (failSave) TokenSaveResult.Failure("DISK_FULL") else TokenSaveResult.Success
            override suspend fun load(): TokenLoadResult = TokenLoadResult.Empty
            override suspend fun clear(): TokenClearResult =
                if (failClear) TokenClearResult.Failure("IO_ERR") else TokenClearResult.Success
        }

        val coordinator = SessionCoordinator(authGateway, tokenStore, time)

        // save() failure must NOT publish durable Authenticated
        val failLogin = coordinator.signIn(AuthCredentials("+1", "u@example.invalid", "pw".toCharArray()))
        assertTrue(failLogin is AuthResult.Failure)
        assertEquals(AuthFailureReason.StorageFailure, (failLogin as AuthResult.Failure).reason)
        assertEquals(SessionState.SignedOut, coordinator.sessionState.value)

        // Recover save(), sign in, then fail clear() -> logout returns IncompleteStorageClear while clearing memory
        failSave = false
        val okLogin = coordinator.signIn(AuthCredentials("+1", "u@example.invalid", "pw".toCharArray()))
        assertTrue(okLogin is AuthResult.Success)
        assertTrue(coordinator.sessionState.value is SessionState.Authenticated)

        val logoutFail = coordinator.signOut()
        assertTrue(logoutFail is LogoutResult.IncompleteStorageClear)
        assertEquals(SessionState.SignedOut, coordinator.sessionState.value)
    }

    @Test
    fun testAccountSwitchAndLate401EpochIsolation() = runTest {
        val time = FakeTimeProvider()
        var currentAccount = "A"
        val authGateway = object : AuthGateway {
            override suspend fun signIn(credentials: AuthCredentials): AuthResult =
                AuthResult.Success(
                    SessionCredentials("tok-$currentAccount", "http://127.0.0.1:80/api", "ws://127.0.0.1:80/ws", "us", 3600L, 10_000L)
                )
        }
        val tokenStore = object : TokenStore {
            var latest: PersistedSessionRecord? = null
            override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
                latest = record
                return TokenSaveResult.Success
            }
            override suspend fun load(): TokenLoadResult =
                latest?.let { TokenLoadResult.Loaded(it) } ?: TokenLoadResult.Empty
            override suspend fun clear(): TokenClearResult {
                latest = null
                return TokenClearResult.Success
            }
        }

        val coordinator = SessionCoordinator(authGateway, tokenStore, time)
        coordinator.signIn(AuthCredentials("+1", "a@example.invalid", "pw".toCharArray()))
        val epochA = coordinator.currentEpoch()

        currentAccount = "B"
        coordinator.signIn(AuthCredentials("+1", "b@example.invalid", "pw".toCharArray()))
        val epochB = coordinator.currentEpoch()
        assertTrue(epochB > epochA)

        // Late 401 callback from Account A must NOT invalidate Account B
        coordinator.invalidateForEpoch(expectedEpoch = epochA, reason = AuthFailureReason.TokenExpiredOrRevoked)
        val activeState = coordinator.sessionState.value
        assertTrue(activeState is SessionState.Authenticated)
        assertEquals("tok-B", (activeState as SessionState.Authenticated).session.jwtToken)
    }
}
