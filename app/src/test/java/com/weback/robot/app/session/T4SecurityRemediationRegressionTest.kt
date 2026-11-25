package com.weback.robot.app.session

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.core.AndroidBootSessionTimeProvider
import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.BootSessionTimeProvider
import com.weback.robot.core.BootTimeSnapshot
import com.weback.robot.domain.auth.LifetimeEvaluation
import com.weback.robot.domain.auth.PersistedRestoreDecision
import com.weback.robot.domain.auth.RestoreRejectionReason
import com.weback.robot.domain.auth.TokenExpiryPolicy
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.LogoutResult
import com.weback.robot.domain.model.PersistedSessionRecord
import com.weback.robot.domain.model.SessionCredentials
import com.weback.robot.domain.model.SessionState
import com.weback.robot.protocol.auth.AuthEnvelopeConfig
import com.weback.robot.protocol.auth.AuthGateway
import com.weback.robot.protocol.auth.OkHttpAuthGateway
import com.weback.robot.protocol.auth.RefreshGrantStrategy
import com.weback.robot.protocol.auth.RegionEndpointRouter
import com.weback.robot.security.AeadCipherProvider
import com.weback.robot.security.InMemoryJcaAeadCipherProvider
import com.weback.robot.security.KeyDeletionResult
import com.weback.robot.security.KeystoreAeadTokenStore
import com.weback.robot.security.TestLoopbackTrustPolicy
import com.weback.robot.security.TokenClearResult
import com.weback.robot.security.TokenLoadResult
import com.weback.robot.security.TokenSaveResult
import com.weback.robot.security.TokenStore
import com.weback.robot.security.UriValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress

/**
 * Security remediation regression suite covering `FIX-301` through `FIX-304`.
 */
@RunWith(AndroidJUnit4::class)
class T4SecurityRemediationRegressionTest {

    private class MutableTime(
        var bootId: String? = "boot_50",
        var elapsedMs: Long = 10_000L,
        var wallMs: Long = 1_700_000_000_000L
    ) : BootSessionTimeProvider {
        override fun snapshot() = BootTimeSnapshot(bootId, elapsedMs, wallMs)
    }

    @Test
    fun fix301_cancellationWhileWaitingForPersistenceLockStillExecutesDurableClear() = runTest {
        val time = MutableTime()
        val authGateway = object : AuthGateway {
            override suspend fun signIn(credentials: AuthCredentials) = AuthResult.Success(
                SessionCredentials(
                    jwtToken = "jwt-fix301-${credentials.account}",
                    apiUrl = "http://127.0.0.1:80/api",
                    wssUrl = "ws://127.0.0.1:80/ws",
                    regionName = "us",
                    expiresInSeconds = 3_600L,
                    issuedAtElapsedMs = time.elapsedMs
                )
            )
        }

        val lockOccupied = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        var blockNextLoad = false
        var storedRecord: PersistedSessionRecord? = null
        var clearCount = 0

        val store = object : TokenStore {
            override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
                storedRecord = record
                return TokenSaveResult.Success
            }

            override suspend fun load(): TokenLoadResult {
                if (blockNextLoad) {
                    lockOccupied.complete(Unit)
                    releaseLock.await()
                }
                return storedRecord?.let { TokenLoadResult.Loaded(it) } ?: TokenLoadResult.Empty
            }

            override suspend fun clear(): TokenClearResult {
                clearCount++
                storedRecord = null
                return TokenClearResult.Success
            }
        }

        val coordinator = SessionCoordinator(authGateway, store, time)
        val login = coordinator.signIn(AuthCredentials("+1", "alice@example.invalid", "pw".toCharArray()))
        assertTrue(login is AuthResult.Success)
        assertEquals("jwt-fix301-alice@example.invalid", storedRecord?.jwtToken)

        // Occupy persistenceMutex via a suspended restoreSession() call
        blockNextLoad = true
        val lockHolder = async { coordinator.restoreSession() }
        lockOccupied.await()

        // Invoke signOut() -> revokes memory immediately and suspends waiting for persistenceMutex
        val logoutJob = async { coordinator.signOut() }
        testScheduler.advanceUntilIdle()
        assertEquals(SessionState.SignedOut, coordinator.sessionState.value)

        // Cancel logoutJob BEFORE it acquires persistenceMutex
        logoutJob.cancel()
        releaseLock.complete(Unit)
        lockHolder.await()
        logoutJob.join()

        assertTrue(
            "FIX-301: clear() must execute even when signOut() is cancelled while waiting for persistenceMutex (clearCount=$clearCount)",
            clearCount >= 1
        )
        assertNull("FIX-301: persisted token must not survive cancelled signOut()", storedRecord)
    }

    @Test
    fun fix301_invalidateForEpochCancelledBeforeLockAcquisitionAndStaleClearNeverDeletesNewerAccount() = runTest {
        val time = MutableTime()
        val authGateway = object : AuthGateway {
            override suspend fun signIn(credentials: AuthCredentials) = AuthResult.Success(
                SessionCredentials(
                    "jwt-${credentials.account}",
                    "http://127.0.0.1:80/api",
                    "ws://127.0.0.1:80/ws",
                    "us",
                    3_600L,
                    10_000L
                )
            )
        }

        val loadGateStarted = CompletableDeferred<Unit>()
        val releaseLoadGate = CompletableDeferred<Unit>()
        var blockLoad = false
        var storedRecord: PersistedSessionRecord? = null
        var clearCount = 0

        val store = object : TokenStore {
            override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
                storedRecord = record
                return TokenSaveResult.Success
            }

            override suspend fun load(): TokenLoadResult {
                if (blockLoad) {
                    loadGateStarted.complete(Unit)
                    releaseLoadGate.await()
                }
                return storedRecord?.let { TokenLoadResult.Loaded(it) } ?: TokenLoadResult.Empty
            }

            override suspend fun clear(): TokenClearResult {
                clearCount++
                storedRecord = null
                return TokenClearResult.Success
            }
        }

        val coordinator = SessionCoordinator(authGateway, store, time)
        coordinator.signIn(AuthCredentials("+1", "bob@example.invalid", "pw".toCharArray()))
        val epochB = coordinator.currentEpoch()

        // Occupy persistenceMutex via a suspended load while invalidating active epoch
        blockLoad = true
        val lockHolder = async { coordinator.restoreSession() }
        loadGateStarted.await()
        val epochBeforeInv = coordinator.currentEpoch()

        val invJob = async {
            coordinator.invalidateForEpoch(epochBeforeInv, AuthFailureReason.TokenExpiredOrRevoked)
        }
        testScheduler.advanceUntilIdle()
        assertTrue(coordinator.sessionState.value is SessionState.ReauthenticationRequired)

        // Cancel invalidation while it is waiting for persistenceMutex
        invJob.cancel()
        releaseLoadGate.complete(Unit)
        lockHolder.await()
        invJob.join()

        assertTrue(epochBeforeInv > epochB)
        assertTrue(
            "FIX-301: invalidateForEpoch must still clear storage when cancelled waiting for persistenceMutex (clearCount=$clearCount)",
            clearCount >= 1
        )
        assertNull("FIX-301: storage must be wiped after invalidateForEpoch", storedRecord)

        // Verify stale invalidateForEpoch(oldEpoch) cannot wipe newer Account C's record
        blockLoad = false
        clearCount = 0
        coordinator.signIn(AuthCredentials("+1", "carol@example.invalid", "pw".toCharArray()))
        val epochCarol = coordinator.currentEpoch()
        coordinator.invalidateForEpoch(epochCarol - 1, AuthFailureReason.TokenExpiredOrRevoked)
        assertEquals(0, clearCount)
        assertEquals("jwt-carol@example.invalid", storedRecord?.jwtToken)
    }

    @Test
    fun fix302_combinedDeleteTombstoneAndKeystoreFailureOnLogoutNeverSilentlyRestoresOnRestart() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 77)
        val dir = File(context.noBackupFilesDir, "test_fix302_combined_fail")
        dir.setWritable(true)
        dir.deleteRecursively()

        val underlyingProvider = InMemoryJcaAeadCipherProvider()
        var failKeyDelete = false
        val faultProvider = object : AeadCipherProvider by underlyingProvider {
            override fun deleteKey(): KeyDeletionResult {
                return if (failKeyDelete) {
                    KeyDeletionResult.Failure("KEYSTORE_HARDWARE_DAEMON_ERROR")
                } else {
                    underlyingProvider.deleteKey()
                }
            }
        }

        val store = KeystoreAeadTokenStore(
            storageDir = dir,
            cipherProvider = faultProvider,
            allowLoopbackHttp = true
        )
        val timeProvider = AndroidBootSessionTimeProvider(context)
        val authGateway = object : AuthGateway {
            override suspend fun signIn(credentials: AuthCredentials) = AuthResult.Success(
                SessionCredentials(
                    jwtToken = "jwt-fix302-secret",
                    apiUrl = "http://127.0.0.1:80/api",
                    wssUrl = "ws://127.0.0.1:80/ws",
                    regionName = "us",
                    expiresInSeconds = 3_600L,
                    issuedAtElapsedMs = timeProvider.snapshot().elapsedRealtimeMs
                )
            )
        }

        try {
            val coordinator1 = SessionCoordinator(authGateway, store, timeProvider)
            val signIn = coordinator1.signIn(AuthCredentials("+1", "fix302@example.invalid", "pw".toCharArray()))
            assertTrue(signIn is AuthResult.Success)
            val blobFile = File(dir, KeystoreAeadTokenStore.SESSION_FILE_NAME)
            assertTrue(blobFile.exists())

            // Simulate simultaneous failure of:
            // 1. encrypted record deletion (read-only storage dir blocks unlink),
            // 2. invalidation tombstone creation (read-only storage dir blocks file creation),
            // 3. Keystore alias deletion (faultProvider returns Failure).
            failKeyDelete = true
            assertTrue(dir.setWritable(false))

            val logoutResult = coordinator1.signOut()
            assertTrue(logoutResult is LogoutResult.IncompleteStorageClear)
            val errCat = (logoutResult as LogoutResult.IncompleteStorageClear).safeCategory
            assertEquals("CLEAR_BLOB_TOMBSTONE_AND_KEY_DELETE_FAILED", errCat)
            assertEquals(SessionState.SignedOut, coordinator1.sessionState.value)

            // Process restart: fresh SessionCoordinator + fresh KeystoreAeadTokenStore pointing at dir
            dir.setWritable(true)
            val restartedStore = KeystoreAeadTokenStore(
                storageDir = dir,
                cipherProvider = underlyingProvider,
                allowLoopbackHttp = true
            )
            val restartedCoordinator = SessionCoordinator(authGateway, restartedStore, timeProvider)
            val restoredState = restartedCoordinator.restoreSession()

            assertFalse(
                "FIX-302: failed logout with combined delete+tombstone+keydelete failure MUST NOT silently restore Authenticated on restart",
                restoredState is SessionState.Authenticated
            )
            assertTrue(
                "FIX-302: expected ReauthenticationRequired after restart, got $restoredState",
                restoredState is SessionState.ReauthenticationRequired
            )
        } finally {
            dir.setWritable(true)
            dir.deleteRecursively()
        }
    }

    @Test
    fun fix303_delayedPersistencePreservesOriginalLifetimeRejectsExpiredAndGuardsRefreshOverflow() = runTest {
        val time = MutableTime(bootId = "boot_60", elapsedMs = 10_000L, wallMs = 1_700_000_000_000L)
        val capturedRecords = mutableListOf<PersistedSessionRecord>()
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        var blockSave = false

        val store = object : TokenStore {
            override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
                if (blockSave) {
                    saveStarted.complete(Unit)
                    releaseSave.await()
                }
                capturedRecords.add(record)
                return TokenSaveResult.Success
            }

            override suspend fun load(): TokenLoadResult =
                capturedRecords.lastOrNull()?.let { TokenLoadResult.Loaded(it) } ?: TokenLoadResult.Empty

            override suspend fun clear(): TokenClearResult {
                capturedRecords.clear()
                return TokenClearResult.Success
            }
        }

        // Case 1: Login issues token at 10_000ms with 500_000ms priorElapsedMs, persist runs at 1_010_000ms
        val delayedGateway = object : AuthGateway {
            override suspend fun signIn(credentials: AuthCredentials): AuthResult {
                val issuedSession = SessionCredentials(
                    jwtToken = "jwt-delayed-ok",
                    apiUrl = "http://127.0.0.1:80/api",
                    wssUrl = "ws://127.0.0.1:80/ws",
                    regionName = "us",
                    expiresInSeconds = 3_600L,
                    issuedAtElapsedMs = 10_000L,
                    priorElapsedMs = 500_000L
                )
                time.elapsedMs = 1_010_000L
                time.wallMs = 1_700_001_000_000L
                return AuthResult.Success(issuedSession)
            }
        }

        val coordinator = SessionCoordinator(delayedGateway, store, time)
        val okRes = coordinator.signIn(AuthCredentials("+1", "u1@example.invalid", "pw".toCharArray()))
        assertTrue(okRes is AuthResult.Success)
        val saved = capturedRecords.last()

        val eval = TokenExpiryPolicy.evaluatePersistedRestore(
            record = saved,
            nowEpochMs = 1_700_001_000_000L,
            nowElapsedRealtimeMs = 1_010_000L,
            currentBootGenerationId = "boot_60"
        )
        assertTrue(eval is PersistedRestoreDecision.Restore)
        assertEquals(
            "FIX-303: persisted record must preserve 500_000 + (1_010_000 - 10_000) = 1_500_000ms consumed lifetime",
            1_500_000L,
            (eval as PersistedRestoreDecision.Restore).priorElapsedMs
        )

        // Overflowing priorElapsedMs + sinceIssued in PersistedSessionRecord fails closed
        val overflowEval = TokenExpiryPolicy.evaluatePersistedRestore(
            record = saved.copy(priorElapsedMs = Long.MAX_VALUE - 5L),
            nowEpochMs = 1_700_001_000_000L,
            nowElapsedRealtimeMs = 1_010_000L,
            currentBootGenerationId = "boot_60"
        )
        assertEquals(
            PersistedRestoreDecision.Reauthenticate(RestoreRejectionReason.ARITHMETIC_OVERFLOW),
            overflowEval
        )

        // Synthetic refresh returning SAME jwtToken with attempted lifetime reset must NOT extend lifetime
        val sneakyRefresh = object : RefreshGrantStrategy {
            override val isEnabled: Boolean = true
            override suspend fun refresh(session: SessionCredentials): AuthResult {
                time.elapsedMs = 2_010_000L
                return AuthResult.Success(
                    session.copy(issuedAtElapsedMs = time.elapsedMs, priorElapsedMs = 0L)
                )
            }
        }
        val refreshCoord = SessionCoordinator(delayedGateway, store, time, refreshStrategy = sneakyRefresh)
        time.elapsedMs = 10_000L
        refreshCoord.signIn(AuthCredentials("+1", "refresh@example.invalid", "pw".toCharArray()))
        val refreshedRes = refreshCoord.refreshIfNeeded(force = true)
        assertTrue(refreshedRes is AuthResult.Success)
        val authedAfterSameToken = refreshCoord.sessionState.value as SessionState.Authenticated
        assertEquals(
            "FIX-303: refresh returning same jwtToken must preserve original issuedAtElapsedMs",
            10_000L,
            authedAfterSameToken.session.issuedAtElapsedMs
        )
        assertEquals(500_000L, authedAfterSameToken.session.priorElapsedMs)

        // Case 2: Token valid before save(), expires DURING delayed save() -> must NOT become Authenticated
        val expiringGateway = object : AuthGateway {
            override suspend fun signIn(credentials: AuthCredentials): AuthResult {
                time.elapsedMs = 10_000L
                return AuthResult.Success(
                    SessionCredentials(
                        jwtToken = "jwt-expires-during-save",
                        apiUrl = "http://127.0.0.1:80/api",
                        wssUrl = "ws://127.0.0.1:80/ws",
                        regionName = "us",
                        expiresInSeconds = 100L,
                        issuedAtElapsedMs = 10_000L
                    )
                )
            }
        }
        val coordinator2 = SessionCoordinator(expiringGateway, store, time)
        blockSave = true
        val pendingLogin = async { coordinator2.signIn(AuthCredentials("+1", "u2@example.invalid", "pw".toCharArray())) }
        saveStarted.await()

        time.elapsedMs = 110_000L
        releaseSave.complete(Unit)
        val expiredResult = pendingLogin.await()

        assertTrue(expiredResult is AuthResult.Failure)
        assertFalse(coordinator2.sessionState.value is SessionState.Authenticated)
        assertTrue("FIX-303: delayed expired save must wipe persisted record", capturedRecords.isEmpty())
    }

    @Test
    fun fix304_failedAccountReplacementWipesPriorAccountAndGatewayNegativeRemainsSideEffectFree() = runTest {
        val time = MutableTime(bootId = "boot_88", elapsedMs = 10_000L, wallMs = 1_700_000_000_000L)
        var failAccountB = false
        val authGateway = object : AuthGateway {
            override suspend fun signIn(credentials: AuthCredentials): AuthResult {
                return if (credentials.account == "B" && failAccountB) {
                    AuthResult.Failure(AuthFailureReason.InvalidCredentials, "bad password for B")
                } else {
                    AuthResult.Success(
                        SessionCredentials(
                            jwtToken = "jwt-${credentials.account}",
                            apiUrl = "http://127.0.0.1:80/api",
                            wssUrl = "ws://127.0.0.1:80/ws",
                            regionName = "us",
                            expiresInSeconds = 3_600L,
                            issuedAtElapsedMs = time.elapsedMs
                        )
                    )
                }
            }
        }

        var diskRecord: PersistedSessionRecord? = null
        val store = object : TokenStore {
            override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
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

        // Pure AuthGateway negative call does NOT mutate an active SessionCoordinator session (`AC-302.3`)
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val httpOrigin = UriValidator.NormalizedOrigin("http", "127.0.0.1", server.port)
            val rawGateway = OkHttpAuthGateway(
                router = RegionEndpointRouter(globalOverrideUrl = "http://127.0.0.1:${server.port}/prod/oauth"),
                trustPolicy = TestLoopbackTrustPolicy(setOf(httpOrigin)),
                envelopeConfig = AuthEnvelopeConfig("6.1.7"),
                authClock = AuthMonotonicClock { 1_000L }
            )
            server.enqueue(MockResponse().setResponseCode(401))

            val coordinator = SessionCoordinator(authGateway, store, time)
            val loginA = coordinator.signIn(AuthCredentials("+1", "A", "pw".toCharArray()))
            assertTrue(loginA is AuthResult.Success)
            assertEquals("jwt-A", diskRecord?.jwtToken)

            val pureNeg = rawGateway.signIn(AuthCredentials("+1", "probe@example.invalid", "bad".toCharArray()))
            assertTrue(pureNeg is AuthResult.Failure)
            assertTrue("Gateway-only negative parse must leave active SessionCoordinator intact", coordinator.sessionState.value is SessionState.Authenticated)
            assertEquals("jwt-A", diskRecord?.jwtToken)

            // Explicit Account B replacement FAILS -> revokes A immediately and wipes prior storage
            failAccountB = true
            val loginB = coordinator.switchAccount(AuthCredentials("+1", "B", "wrong".toCharArray()))
            assertTrue(loginB is AuthResult.Failure)
            assertFalse(coordinator.sessionState.value is SessionState.Authenticated)
            assertNull("FIX-304: failed Account B replacement must wipe Account A from storage", diskRecord)

            // Process restart must remain safely unauthenticated
            val restarted = SessionCoordinator(authGateway, store, time)
            val restartedState = restarted.restoreSession()
            assertFalse(
                "FIX-304: process restart after failed Account B replacement must NOT restore Account A",
                restartedState is SessionState.Authenticated
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun fix304_storageCleanupFailureDuringReplacementBlocksNewSessionDuplicateSingleFlightAndLateAccountAIgnored() = runTest {
        val time = MutableTime(bootId = "boot_89", elapsedMs = 10_000L, wallMs = 1_700_000_000_000L)
        val gateA = CompletableDeferred<AuthResult>()
        val gateB = CompletableDeferred<AuthResult>()
        var blockA = false
        var blockB = false
        var callsToB = 0
        val authGateway = object : AuthGateway {
            override suspend fun signIn(credentials: AuthCredentials): AuthResult {
                if (credentials.account == "A" && blockA) {
                    return gateA.await()
                }
                if (credentials.account == "B") {
                    callsToB++
                    if (blockB) return gateB.await()
                }
                return AuthResult.Success(
                    SessionCredentials(
                        jwtToken = "jwt-${credentials.account}",
                        apiUrl = "http://127.0.0.1:80/api",
                        wssUrl = "ws://127.0.0.1:80/ws",
                        regionName = "us",
                        expiresInSeconds = 3_600L,
                        issuedAtElapsedMs = time.elapsedMs
                    )
                )
            }
        }

        var diskRecord: PersistedSessionRecord? = null
        var failClear = false
        val store = object : TokenStore {
            override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
                diskRecord = record
                return TokenSaveResult.Success
            }

            override suspend fun load(): TokenLoadResult =
                diskRecord?.let { TokenLoadResult.Loaded(it) } ?: TokenLoadResult.Empty

            override suspend fun clear(): TokenClearResult {
                if (failClear) return TokenClearResult.Failure("CLEAR_DENIED")
                diskRecord = null
                return TokenClearResult.Success
            }
        }

        // Authenticate A, then simulate storage cleanup failure during replacement to B
        val coordinator = SessionCoordinator(authGateway, store, time)
        assertTrue(coordinator.signIn(AuthCredentials("+1", "A", "pw".toCharArray())) is AuthResult.Success)
        failClear = true

        val blockedB = coordinator.switchAccount(AuthCredentials("+1", "B", "pw".toCharArray()))
        assertTrue(blockedB is AuthResult.Failure)
        assertEquals(AuthFailureReason.StorageFailure, (blockedB as AuthResult.Failure).reason)
        assertEquals(0, callsToB)
        assertFalse(coordinator.sessionState.value is SessionState.Authenticated)

        // Concurrent duplicate Account B replacement remains single-flight while superseding slow Account A
        failClear = false
        val coordinator2 = SessionCoordinator(authGateway, store, time)
        blockA = true
        val slowA = async { coordinator2.signIn(AuthCredentials("+1", "A", "pw".toCharArray())) }
        testScheduler.advanceUntilIdle()

        blockB = true
        val dupB1 = async { coordinator2.switchAccount(AuthCredentials("+1", "B", "pw".toCharArray())) }
        val dupB2 = async { coordinator2.switchAccount(AuthCredentials("+1", "B", "pw".toCharArray())) }
        testScheduler.advanceUntilIdle()
        assertEquals("FIX-304: concurrent duplicate B replacement must stay single-flight", 1, callsToB)

        gateB.complete(
            AuthResult.Success(
                SessionCredentials("jwt-B", "http://127.0.0.1:80/api", "ws://127.0.0.1:80/ws", "us", 3_600L, time.elapsedMs)
            )
        )
        val okBs = listOf(dupB1, dupB2).awaitAll()
        assertTrue(okBs.all { it is AuthResult.Success })
        assertEquals("jwt-B", diskRecord?.jwtToken)

        // Late Account A completes after Account B is active -> must be discarded cleanly
        gateA.complete(
            AuthResult.Success(
                SessionCredentials("jwt-A-late", "http://127.0.0.1:80/api", "ws://127.0.0.1:80/ws", "us", 3_600L, 10_000L)
            )
        )
        val lateAResult = slowA.await()
        assertTrue(lateAResult is AuthResult.Failure)
        val live = coordinator2.sessionState.value as SessionState.Authenticated
        assertEquals("jwt-B", live.session.jwtToken)
        assertEquals("jwt-B", diskRecord?.jwtToken)
    }
}
