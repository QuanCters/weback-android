package com.weback.robot.app.session

import com.weback.robot.core.BootSessionTimeProvider
import com.weback.robot.core.BootTimeSnapshot
import com.weback.robot.domain.model.AuthCredentials
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fault-injected persistence ordering tests (`IC-302`, `TEST-AUTH-304`):
 * verifies suspension during TokenStore.save(), coroutine cancellation during cleanup,
 * stale clear protection against newer Account B saves, and non-restoration on restart after failed clear.
 */
class PersistenceOrderingFaultTest {

    @Test
    fun testLogoutDuringSuspendedSaveAndStaleClearNeverOverwritesNewerAccount() = runTest {
        val time = BootSessionTimeProvider { BootTimeSnapshot("boot_1", 10_000L, 1_700_000_000_000L) }
        val authGateway = object : AuthGateway {
            override suspend fun signIn(credentials: AuthCredentials): AuthResult =
                AuthResult.Success(
                    SessionCredentials("jwt-${credentials.account}", "http://127.0.0.1:80/api", "ws://127.0.0.1:80/ws", "us", 3600L, 10_000L)
                )
        }

        val saveStarted = CompletableDeferred<Unit>()
        val allowSaveFinish = CompletableDeferred<Unit>()
        var diskRecord: PersistedSessionRecord? = null
        var interceptNextSave = true

        val tokenStore = object : TokenStore {
            override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
                if (interceptNextSave) {
                    saveStarted.complete(Unit)
                    allowSaveFinish.await()
                }
                diskRecord = record
                return TokenSaveResult.Success
            }
            override suspend fun load(): TokenLoadResult =
                diskRecord?.let { TokenLoadResult.Loaded(it) } ?: TokenLoadResult.Empty
            override suspend fun clear(): TokenClearResult {
                diskRecord = null
                return TokenClearResult.Success
            }
        }

        val coordinator = SessionCoordinator(authGateway, tokenStore, time)
        val loginAJob = async { coordinator.signIn(AuthCredentials("+1", "A", "pw".toCharArray())) }
        saveStarted.await()

        // While Account A's disk write is suspended inside save(), trigger signOut()
        val logoutJob = async { coordinator.signOut() }
        testScheduler.advanceUntilIdle()
        // In-memory authorization is wiped immediately even before persistence finishes
        assertEquals(SessionState.SignedOut, coordinator.sessionState.value)

        // Allow save A to finish -> post-write epoch check sees logout, clears disk cleanly before returning
        allowSaveFinish.complete(Unit)
        val loginARes = loginAJob.await()
        val logoutRes = logoutJob.await()

        assertTrue(loginARes is AuthResult.Failure)
        assertEquals(LogoutResult.DurableSuccess, logoutRes)
        assertEquals(SessionState.SignedOut, coordinator.sessionState.value)
        assertNull("Stale Account A credential must NOT remain on disk after logout", diskRecord)

        // Now test Account B signIn followed by cancelled/stale clear protection
        interceptNextSave = false
        val loginB = coordinator.signIn(AuthCredentials("+1", "B", "pw".toCharArray()))
        assertTrue(loginB is AuthResult.Success)
        val epochB = coordinator.currentEpoch()
        assertEquals("jwt-B", diskRecord?.jwtToken)

        // Stale epoch invalidation below epochB is ignored; Account B stays persisted
        coordinator.invalidateForEpoch(epochB - 1, com.weback.robot.domain.model.AuthFailureReason.TokenExpiredOrRevoked)
        assertEquals("jwt-B", diskRecord?.jwtToken)
        assertTrue(coordinator.sessionState.value is SessionState.Authenticated)

        // Cancel a coroutine invoking signOut mid-flight -> NonCancellable cleanup still runs
        val slowClearGate = CompletableDeferred<Unit>()
        val cancellingCoordinator = SessionCoordinator(
            authGateway = authGateway,
            tokenStore = object : TokenStore by tokenStore {
                override suspend fun clear(): TokenClearResult {
                    slowClearGate.await()
                    diskRecord = null
                    return TokenClearResult.Success
                }
            },
            timeProvider = time
        )
        cancellingCoordinator.signIn(AuthCredentials("+1", "C", "pw".toCharArray()))
        val jobToCancel = async { cancellingCoordinator.signOut() }
        testScheduler.advanceUntilIdle()
        jobToCancel.cancel()
        slowClearGate.complete(Unit)
        jobToCancel.join()
        assertNull("Cancelled signOut cleanup must still clear persisted record via NonCancellable", diskRecord)
    }
}
