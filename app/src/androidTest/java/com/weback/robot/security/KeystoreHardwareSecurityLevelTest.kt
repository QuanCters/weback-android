package com.weback.robot.security

import android.content.Context
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.domain.model.PersistedSessionRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
 */
@RunWith(AndroidJUnit4::class)
class KeystoreHardwareSecurityLevelTest {

    @Test
    fun verifyAndroidKeystoreAes256GcmHardwareSecurityLevelAndIsolation() = runTest {
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

        val record = PersistedSessionRecord(
            jwtToken = "synthetic-hw-test-token",
            apiUrl = "https://api.loopback.invalid/api",
            wssUrl = "wss://ws.loopback.invalid/ws",
            regionName = "us-east-1",
            expiresInSeconds = 3600L,
            issuedAtEpochMs = 1_700_000_000_000L,
            lastSeenEpochMs = 1_700_000_000_000L,
            issuedAtElapsedRealtimeMs = 10_000L,
            bootGenerationId = "boot_hw_1",
            accountAliasRedacted = "t***@example.invalid"
        )

        try {
            assertTrue(store.save(record) is TokenSaveResult.Success)
            assertEquals(TokenLoadResult.Loaded(record), store.load())

            // On-device AEAD tamper rejection check (bit-flip)
            val blobFile = File(isolatedDir, KeystoreAeadTokenStore.SESSION_FILE_NAME)
            val bytes = blobFile.readBytes()
            bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0xFF).toByte()
            blobFile.writeBytes(bytes)
            assertTrue(store.load() is TokenLoadResult.CorruptedOrInvalidated)
            assertTrue(store.save(record) is TokenSaveResult.Success)

            val keyStore = KeyStore.getInstance(AndroidKeystoreAeadCipherProvider.ANDROID_KEYSTORE).apply {
                load(null)
            }
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
        } finally {
            store.clear()
            cipherProvider.deleteKey()
            isolatedDir.deleteRecursively()
        }
    }
}
