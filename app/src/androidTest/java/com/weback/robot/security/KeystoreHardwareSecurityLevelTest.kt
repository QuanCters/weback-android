package com.weback.robot.security

import android.content.Context
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.domain.model.PersistedSessionRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Hardware-backed Keystore instrumentation oracle (`TEST-AUTH-312` / `AC-305.4` / `IC-304`).
 * Uses an isolated Keystore alias and storage namespace so device verification never alters
 * application session data. Remains `NOT_RUN` unless executed on an explicitly authorized
 * physical Android >= 12 device.
 *
 * Distinction:
 * - Deleting the Keystore alias while the encrypted blob remains on disk exercises **missing-key
 *   orphaned blob rejection & fresh key recreation** (`MISSING_KEYSTORE_KEY`).
 * - Actual hardware biometric/lockscreen permanent key invalidation (`KeyPermanentlyInvalidatedException`)
 *   is distinct and must NOT be claimed from missing-key deletion alone.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreHardwareSecurityLevelTest {

    @Test
    fun verifyAndroidKeystoreAes256GcmHardwareSecurityLevelMissingKeyRecoveryAndCleanup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val isolatedDir = File(context.noBackupFilesDir, "androidtest_token_store")
        isolatedDir.deleteRecursively()

        val testAlias = "com.weback.robot.auth.session_key.androidtest.v1"
        val cipherProvider = AndroidKeystoreAeadCipherProvider(keyAlias = testAlias)
        cipherProvider.deleteKey()

        val store = KeystoreAeadTokenStore(
            storageDir = isolatedDir,
            cipherProvider = cipherProvider,
            allowLoopbackHttp = false
        )

        val record1 = PersistedSessionRecord(
            jwtToken = "synthetic-hw-test-token-1",
            apiUrl = "https://api.loopback.invalid/api",
            wssUrl = "wss://ws.loopback.invalid/ws",
            regionName = "us-east-1",
            expiresInSeconds = 3_600L,
            issuedAtEpochMs = 1_700_000_000_000L,
            lastSeenEpochMs = 1_700_000_000_000L,
            issuedAtElapsedRealtimeMs = 10_000L,
            bootGenerationId = "boot_hw_1",
            accountAliasRedacted = "t***@example.invalid"
        )
        val record2 = record1.copy(jwtToken = "synthetic-hw-test-token-2")
        val blobFile = File(isolatedDir, KeystoreAeadTokenStore.SESSION_FILE_NAME)

        try {
            // 1. Successful encrypted record persistence + tamper check + hardware KeyInfo verification
            assertTrue(store.save(record1) is TokenSaveResult.Success)
            assertTrue(blobFile.exists())
            assertEquals(TokenLoadResult.Loaded(record1), store.load())

            val bytes = blobFile.readBytes()
            bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0xFF).toByte()
            blobFile.writeBytes(bytes)
            assertTrue(store.load() is TokenLoadResult.CorruptedOrInvalidated)
            assertFalse(blobFile.exists())

            assertTrue(store.save(record1) is TokenSaveResult.Success)
            assertTrue(blobFile.exists())

            val keyStore = KeyStore.getInstance(AndroidKeystoreAeadCipherProvider.ANDROID_KEYSTORE).apply {
                load(null)
            }
            assertTrue(keyStore.containsAlias(testAlias))
            val key = keyStore.getKey(testAlias, null) as SecretKey
            assertNull("AndroidKeyStore key material must be non-exportable", key.encoded)

            val factory = SecretKeyFactory.getInstance(key.algorithm, AndroidKeystoreAeadCipherProvider.ANDROID_KEYSTORE)
            val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            val validSecurityLevels = setOf(
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
                KeyProperties.SECURITY_LEVEL_STRONGBOX
            )
            assertTrue(
                "Expected TEE or StrongBox hardware security level on API >= 31, got ${keyInfo.securityLevel}",
                keyInfo.securityLevel in validSecurityLevels
            )
            assertEquals(256, keyInfo.keySize)
            assertTrue(keyInfo.blockModes.contains(KeyProperties.BLOCK_MODE_GCM))
            assertTrue(keyInfo.encryptionPaddings.contains(KeyProperties.ENCRYPTION_PADDING_NONE))

            // 2. Deletion of isolated test key while encrypted record stays on disk
            val deleteResult = cipherProvider.deleteKey()
            assertEquals(KeyDeletionResult.Deleted, deleteResult)
            keyStore.load(null)
            assertFalse("Isolated test Keystore alias must be deleted", keyStore.containsAlias(testAlias))
            assertTrue("Orphaned encrypted record should still exist prior to load()", blobFile.exists())

            // 3. Rejection of orphaned encrypted record (missing-key recovery, distinct from KeyPermanentlyInvalidatedException)
            val orphanLoadResult = store.load()
            assertEquals(TokenLoadResult.CorruptedOrInvalidated("MISSING_KEYSTORE_KEY"), orphanLoadResult)
            assertFalse("Orphaned encrypted record must be wiped fail-closed", blobFile.exists())

            // 4. Successful new key creation and fresh persistence
            assertTrue(store.save(record2) is TokenSaveResult.Success)
            assertTrue(blobFile.exists())
            keyStore.load(null)
            assertTrue("Fresh test Keystore alias must be recreated on save()", keyStore.containsAlias(testAlias))
            val freshKey = keyStore.getKey(testAlias, null) as SecretKey
            assertNull(freshKey.encoded)
            val freshInfo = factory.getKeySpec(freshKey, KeyInfo::class.java) as KeyInfo
            assertTrue(freshInfo.securityLevel in validSecurityLevels)
            assertEquals(TokenLoadResult.Loaded(record2), store.load())

            // 5. Verified cleanup of test aliases and files
            assertEquals(TokenClearResult.Success, store.clear())
            keyStore.load(null)
            assertFalse("clear() must remove isolated test alias", keyStore.containsAlias(testAlias))
            assertFalse("clear() must remove isolated encrypted blob", blobFile.exists())
        } finally {
            store.clear()
            cipherProvider.deleteKey()
            isolatedDir.deleteRecursively()
            val ksCheck = KeyStore.getInstance(AndroidKeystoreAeadCipherProvider.ANDROID_KEYSTORE).apply { load(null) }
            assertFalse(ksCheck.containsAlias(testAlias))
            assertFalse(isolatedDir.exists())
        }
    }
}
