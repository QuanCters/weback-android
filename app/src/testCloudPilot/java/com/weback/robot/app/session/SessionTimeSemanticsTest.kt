package com.weback.robot.app.session

import com.weback.robot.core.BootSessionTimeProvider
import com.weback.robot.core.BootTimeSnapshot
import com.weback.robot.domain.auth.PersistedRestoreDecision
import com.weback.robot.domain.auth.TokenExpiryPolicy
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.domain.model.SessionCredentials
import com.weback.robot.domain.model.SessionState
import com.weback.robot.protocol.auth.AuthGateway
import com.weback.robot.protocol.auth.DisabledPasswordlessRefreshStrategy
import com.weback.robot.protocol.auth.RefreshGrantStrategy
import com.weback.robot.security.TokenClearResult
import com.weback.robot.security.TokenLoadResult
import com.weback.robot.security.TokenSaveResult
import com.weback.robot.security.TokenStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SessionTimeSemanticsTest {

    private class MutableTime(
        var bootId: String? = "boot_10",
        var elapsedMs: Long = 10_000L,
        var wallMs: Long = 1_700_000_000_000L
    ) : BootSessionTimeProvider {
        override fun snapshot() = BootTimeSnapshot(bootId, elapsedMs, wallMs)
    }

    private class InMemoryTokenStore : TokenStore {
        var record: PersistedSessionRecord? = null
        var clearCalls = 0
        override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
            this.record = record
            return TokenSaveResult.Success
        }
        override suspend fun load(): TokenLoadResult =
            record?.let { TokenLoadResult.Loaded(it) } ?: TokenLoadResult.Empty
        override suspend fun clear(): TokenClearResult {
            clearCalls++
            record = null
            return TokenClearResult.Success
        }
    }

    @Test
    fun testRestoreSameBootPreservesLifetimeAndRebootOrRollbackRequiresReauth() = runTest {
        val time = MutableTime(bootId = "boot_10", elapsedMs = 10_000L, wallMs = 1_700_000_000_000L)
        val store = InMemoryTokenStore()
        // JWT with exp year 2286 must be ignored in favor of 3600s monotonic lifetime
        val farExpJwt = "eyJhbGciOiJub25lIn0.eyJleHAiOjk5OTk5OTk5OTl9.sig"
        store.record = PersistedSessionRecord(
            jwtToken = farExpJwt,
            apiUrl = "http://127.0.0.1:80/api",
            wssUrl = "ws://127.0.0.1:80/ws",
            regionName = "us-east-1",
            expiresInSeconds = 3600L,
            issuedAtEpochMs = 1_700_000_000_000L,
            lastSeenEpochMs = 1_700_000_100_000L,
            issuedAtElapsedRealtimeMs = 10_000L,
            bootGenerationId = "boot_10",
            accountAliasRedacted = "u***@example.invalid"
        )
        val dummyGateway = object : AuthGateway {
            override suspend fun signIn(credentials: AuthCredentials) =
                AuthResult.Failure(AuthFailureReason.InvalidCredentials, "unused")
        }

        // Same boot, +1_000_000ms elapsed -> TokenExpiryPolicy preserves priorElapsedMs = 1_000_000L (NOT reset to 0!),
        // while SessionCoordinator.restoreSession() conservatively disables auto-activation (`FIX-302`) and wipes storage.
        time.elapsedMs = 1_010_000L
        time.wallMs = 1_700_001_000_000L
        val sameBootDecision = TokenExpiryPolicy.evaluatePersistedRestore(
            record = store.record!!,
            nowEpochMs = time.wallMs,
            nowElapsedRealtimeMs = time.elapsedMs,
            currentBootGenerationId = time.bootId
        )
        assertTrue(sameBootDecision is PersistedRestoreDecision.Restore)
        assertEquals(1_000_000L, (sameBootDecision as PersistedRestoreDecision.Restore).priorElapsedMs)

        val coordinator1 = SessionCoordinator(dummyGateway, store, time)
        val state1 = coordinator1.restoreSession()
        assertTrue("FCT-003 conservative startup requires reauthentication", state1 is SessionState.ReauthenticationRequired)
        assertNull(store.record)

        // Device reboot -> ReauthenticationRequired and store wiped
        store.record = PersistedSessionRecord(
            jwtToken = farExpJwt,
            apiUrl = "http://127.0.0.1:80/api",
            wssUrl = "ws://127.0.0.1:80/ws",
            regionName = "us-east-1",
            expiresInSeconds = 3600L,
            issuedAtEpochMs = 1_700_000_000_000L,
            lastSeenEpochMs = 1_700_000_100_000L,
            issuedAtElapsedRealtimeMs = 10_000L,
            bootGenerationId = "boot_10",
            accountAliasRedacted = "u***@example.invalid"
        )
        time.bootId = "boot_11"
        time.elapsedMs = 2_000L
        val coordinator2 = SessionCoordinator(dummyGateway, store, time)
        val state2 = coordinator2.restoreSession()
        assertTrue(state2 is SessionState.ReauthenticationRequired)
        assertNull(store.record)

        // Wall-clock rollback -> ReauthenticationRequired and store wiped
        store.record = PersistedSessionRecord(
            jwtToken = farExpJwt,
            apiUrl = "http://127.0.0.1:80/api",
            wssUrl = "ws://127.0.0.1:80/ws",
            regionName = "us-east-1",
            expiresInSeconds = 3600L,
            issuedAtEpochMs = 1_700_000_500_000L,
            lastSeenEpochMs = 1_700_000_600_000L,
            issuedAtElapsedRealtimeMs = 10_000L,
            bootGenerationId = "boot_11",
            accountAliasRedacted = "u***@example.invalid"
        )
        time.wallMs = 1_700_000_400_000L
        val coordinator3 = SessionCoordinator(dummyGateway, store, time)
        val state3 = coordinator3.restoreSession()
        assertTrue(state3 is SessionState.ReauthenticationRequired)
        assertNull(store.record)
    }

    @Test
    fun testProductionRefreshDisabledDefaultsToReauthAndSyntheticRefreshCollapses() = runTest {
        assertFalse(DisabledPasswordlessRefreshStrategy.isEnabled)
        val time = MutableTime()
        val store = InMemoryTokenStore()
        val dummyGateway = object : AuthGateway {
            override suspend fun signIn(credentials: AuthCredentials) =
                AuthResult.Success(SessionCredentials("t0", "http://127.0.0.1:80/api", "ws://127.0.0.1:80/ws", "us", 3600L, 10_000L))
        }

        // Default production disabled refresh transitions to ReauthenticationRequired
        val prodCoord = SessionCoordinator(dummyGateway, store, time)
        prodCoord.signIn(AuthCredentials("+1", "u@example.invalid", "pw".toCharArray()))
        time.elapsedMs = 10_000L + 2_880_001L // past 80% window
        val prodRefresh = prodCoord.refreshIfNeeded()
        assertTrue(prodRefresh is AuthResult.Failure)
        assertTrue(prodCoord.sessionState.value is SessionState.ReauthenticationRequired)

        // Synthetic test refresh strategy enabled collapses concurrent refreshes into 1 dispatch
        val refreshCount = AtomicInteger(0)
        val refreshGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val syntheticStrategy = object : RefreshGrantStrategy {
            override val isEnabled: Boolean = true
            override suspend fun refresh(session: SessionCredentials): AuthResult {
                refreshCount.incrementAndGet()
                refreshGate.await()
                return AuthResult.Success(
                    session.copy(jwtToken = "refreshed-tok", issuedAtElapsedMs = time.elapsedMs, priorElapsedMs = 0L)
                )
            }
        }
        val synCoord = SessionCoordinator(dummyGateway, store, time, refreshStrategy = syntheticStrategy)
        synCoord.signIn(AuthCredentials("+1", "u@example.invalid", "pw".toCharArray()))
        val f1 = async { synCoord.refreshIfNeeded() }
        val f2 = async { synCoord.refreshIfNeeded() }
        testScheduler.advanceUntilIdle()
        refreshGate.complete(Unit)
        val r = awaitAll(f1, f2)
        assertTrue(r.all { it is AuthResult.Success })
        assertEquals(1, refreshCount.get())
    }
}
