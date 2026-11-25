package com.weback.robot

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.core.AndroidAuthMonotonicClock
import com.weback.robot.core.AndroidBootSessionTimeProvider
import com.weback.robot.core.AppContainer
import com.weback.robot.core.DefaultAppContainer
import com.weback.robot.domain.auth.LifetimeEvaluation
import com.weback.robot.domain.auth.PersistedRestoreDecision
import com.weback.robot.domain.auth.TokenExpiryPolicy
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.model.ConnectivityError
import com.weback.robot.domain.model.InventoryState
import com.weback.robot.domain.model.LogoutResult
import com.weback.robot.domain.model.RecoveryAction
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.RobotState
import com.weback.robot.domain.model.SessionCredentials
import com.weback.robot.domain.model.SessionState
import com.weback.robot.protocol.ws.OfflineFixtureRealtimeTransport
import com.weback.robot.security.EndpointTrustDecision
import com.weback.robot.security.InMemoryJcaAeadCipherProvider
import com.weback.robot.security.KeystoreAeadTokenStore
import com.weback.robot.security.ProductionEndpointTrustPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class ConnectivityRecoveryTest {

    @Test
    fun testConnectivityErrorMappingCoversAllVariantsWithoutSecretLeaks() {
        val errors = listOf(
            ConnectivityError.Unauthenticated to RecoveryAction.REAUTHENTICATE,
            ConnectivityError.TokenExpired to RecoveryAction.REAUTHENTICATE,
            ConnectivityError.AuthorizationDenied to RecoveryAction.REAUTHENTICATE,
            ConnectivityError.ChallengeRequired to RecoveryAction.REAUTHENTICATE,
            ConnectivityError.UntrustedEndpoint to RecoveryAction.REAUTHENTICATE,
            ConnectivityError.RedirectRejected to RecoveryAction.REAUTHENTICATE,
            ConnectivityError.TransportTimeout to RecoveryAction.RETRY,
            ConnectivityError.NetworkUnavailable to RecoveryAction.RETRY,
            ConnectivityError.HandshakeRejected(503) to RecoveryAction.RETRY,
            ConnectivityError.HandshakeRejected(401) to RecoveryAction.REAUTHENTICATE,
            ConnectivityError.MalformedPayload to RecoveryAction.RETRY,
            ConnectivityError.StorageFailure to RecoveryAction.REAUTHENTICATE,
            ConnectivityError.UnsupportedDevice("sock-pw-") to RecoveryAction.UNSUPPORTED_INFO
        )
        for ((err, expectedAction) in errors) {
            assertEquals(expectedAction, err.recoveryAction)
            assertTrue(err.safeCategory.isNotBlank())
            assertFalse(err.toString().contains("jwt", ignoreCase = true))
        }
    }

    @Test
    fun testAndroidElapsedRealtimeIncludesDeepSleepInAuthLifetimeAndBootProvider() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)

        val provider = AndroidBootSessionTimeProvider(context)
        val snap1 = provider.snapshot()
        assertEquals("boot_7", snap1.bootGenerationId)

        val session = SessionCredentials(
            jwtToken = "synthetic-deep-sleep-jwt",
            apiUrl = "https://loopback.offline.invalid/api",
            wssUrl = "wss://loopback.offline.invalid/ws",
            regionName = "offline-fixture",
            expiresInSeconds = 3_600L,
            issuedAtElapsedMs = AndroidAuthMonotonicClock.elapsedRealtimeMs()
        )
        assertEquals(
            LifetimeEvaluation.VALID,
            TokenExpiryPolicy.evaluateInProcess(session, AndroidAuthMonotonicClock.elapsedRealtimeMs())
        )

        // Advance deep-sleep inclusive SystemClock.elapsedRealtime beyond 80% threshold
        ShadowSystemClock.advanceBy(Duration.ofMillis(2_880_001L))
        assertEquals(
            LifetimeEvaluation.PROACTIVE_REFRESH_RECOMMENDED,
            TokenExpiryPolicy.evaluateInProcess(session, AndroidAuthMonotonicClock.elapsedRealtimeMs())
        )

        // Advance past 100% expiration
        ShadowSystemClock.advanceBy(Duration.ofMillis(720_000L))
        assertEquals(
            LifetimeEvaluation.EXPIRED_OR_INVALID,
            TokenExpiryPolicy.evaluateInProcess(session, AndroidAuthMonotonicClock.elapsedRealtimeMs())
        )
        assertTrue(SystemClock.elapsedRealtime() > snap1.elapsedRealtimeMs)
    }

    @Test
    fun testEndToEndOfflineLifecycleSignInPersistInventoryFilterWsTelemetryAndSignOut() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 12)
        val storeDir = File(context.noBackupFilesDir, "e2e_offline_token_store")
        storeDir.deleteRecursively()

        val cipherProvider = InMemoryJcaAeadCipherProvider()
        val tokenStore = KeystoreAeadTokenStore(storageDir = storeDir, cipherProvider = cipherProvider)
        val container = DefaultAppContainer(
            context = context,
            tokenStoreOverride = tokenStore
        )

        // Production endpoint trust remains disabled by default in FCT-003
        assertTrue(container.endpointTrustPolicy === ProductionEndpointTrustPolicy)
        assertTrue(container.endpointTrustPolicy.authorize("https://user.grit-cloud.com/prod/oauth") is EndpointTrustDecision.Untrusted)

        // 1. Offline Sign-In -> persisted encrypted session
        val signInRes = container.sessionCoordinator.signIn(
            AuthCredentials("+1", "offline-user@example.invalid", "SyntheticPass!".toCharArray())
        )
        assertTrue(signInRes is AuthResult.Success)
        val authedState = container.sessionCoordinator.sessionState.value as SessionState.Authenticated
        val epoch = authedState.epoch

        // Verify persisted record exists and restores under same boot
        val persistedSnap = AndroidBootSessionTimeProvider(context).snapshot()
        val loaded = tokenStore.load()
        assertNotNull(loaded)
        val decision = TokenExpiryPolicy.evaluatePersistedRestore(
            record = (loaded as com.weback.robot.security.TokenLoadResult.Loaded).record,
            nowEpochMs = persistedSnap.wallEpochMs,
            nowElapsedRealtimeMs = persistedSnap.elapsedRealtimeMs,
            currentBootGenerationId = persistedSnap.bootGenerationId
        )
        assertTrue(decision is PersistedRestoreDecision.Restore)

        // 2. Inventory & capability filtering
        val invState = container.inventoryRepository.refreshInventory() as InventoryState.Loaded
        assertEquals(2, invState.items.size)
        val laserItem = invState.items.first { it.capability.compatibility.isMvpControlSupported }
        val unsupportedItem = invState.items.first { !it.capability.compatibility.isMvpControlSupported }

        // 3. Select Laser SLAM robot & connect offline WS transport -> receive telemetry
        val selected = container.inventoryRepository.selectDevice(laserItem.thingName, epoch)
        assertNotNull(selected)
        val wsTransport = container.realtimeTransport as OfflineFixtureRealtimeTransport
        wsTransport.selectDevice(selected, epoch)
        wsTransport.connect(authedState.session, epoch)
        assertTrue(wsTransport.connectionState.value is ConnectionState.Connected)

        val gen = wsTransport.currentGeneration()
        assertTrue(
            wsTransport.emitTelemetry(
                thingName = laserItem.thingName,
                updatedState = RobotState(laserItem.nickname, 72, RobotMode.CLEAN, isDustbinClean = true, errorCode = 0),
                epoch = epoch,
                gen = gen
            )
        )
        assertEquals(72, wsTransport.robotState.value.batteryPercentage)

        // Telemetry for unsupported peripheral must NOT mutate state
        assertFalse(
            wsTransport.emitTelemetry(
                thingName = unsupportedItem.thingName,
                updatedState = RobotState("Plug", 10, RobotMode.FAULT),
                epoch = epoch,
                gen = gen
            )
        )
        assertEquals(72, wsTransport.robotState.value.batteryPercentage)

        // Disconnect preserves committed telemetry in active epoch and blocks outbound send
        wsTransport.invalidateForEpoch(expectedEpoch = epoch - 1, reason = ConnectivityError.NetworkUnavailable) // mismatched ignored
        assertEquals(72, wsTransport.robotState.value.batteryPercentage)

        // 4. Sign-Out wipes memory, selection, WS state, and encrypted storage cleanly
        val logoutResult = container.sessionCoordinator.signOut()
        assertEquals(LogoutResult.DurableSuccess, logoutResult)
        assertEquals(SessionState.SignedOut, container.sessionCoordinator.sessionState.value)
        assertEquals(InventoryState.Idle, container.inventoryRepository.inventoryState.value)
        assertNull(container.inventoryRepository.selectedDevice.value)
        assertFalse(File(storeDir, KeystoreAeadTokenStore.SESSION_FILE_NAME).exists())

        container.close()
    }
}
