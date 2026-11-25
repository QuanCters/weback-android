package com.weback.robot.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.domain.model.PersistedSessionRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@RunWith(AndroidJUnit4::class)
class KeystoreAeadTokenStoreTest {

    private fun testRecord(jwt: String = "synthetic-jwt-alpha-9911") = PersistedSessionRecord(
        jwtToken = jwt,
        apiUrl = "https://loopback.invalid:8443/api",
        wssUrl = "wss://loopback.invalid:8443/ws",
        regionName = "us-east-1",
        expiresInSeconds = 3600L,
        issuedAtEpochMs = 1_700_000_000_000L,
        lastSeenEpochMs = 1_700_000_000_000L,
        issuedAtElapsedRealtimeMs = 10_000L,
        bootGenerationId = "boot_1",
        accountAliasRedacted = "u***@example.invalid"
    )

    @Test
    fun testAeadEnvelopeRoundTripDistinctCipherGeneratedIvsAndNoBackupFilesDir() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storeDir = File(context.noBackupFilesDir, "test_token_store_ok")
        storeDir.deleteRecursively()

        val fakeProvider = InMemoryJcaAeadCipherProvider()
        val store = KeystoreAeadTokenStore(
            storageDir = storeDir,
            cipherProvider = fakeProvider,
            allowLoopbackHttp = false
        )

        val record1 = testRecord("jwt-secret-token-value-111")
        assertTrue(store.save(record1) is TokenSaveResult.Success)

        val blobFile = File(storeDir, KeystoreAeadTokenStore.SESSION_FILE_NAME)
        assertTrue(blobFile.exists())
        val rawBytes1 = blobFile.readBytes()
        assertTrue(rawBytes1.size in KeystoreAeadTokenStore.MIN_RECORD_BYTES..KeystoreAeadTokenStore.MAX_RECORD_BYTES)
        assertEquals(KeystoreAeadTokenStore.SCHEMA_VERSION_V1, rawBytes1[0])
        assertFalse(rawBytes1.decodeToString().contains("jwt-secret-token-value-111"))

        val iv1 = rawBytes1.copyOfRange(1, 13)
        assertEquals(12, iv1.size)

        // Second save must yield a distinct cipher-generated 12B IV
        val record2 = testRecord("jwt-secret-token-value-222")
        assertTrue(store.save(record2) is TokenSaveResult.Success)
        val rawBytes2 = blobFile.readBytes()
        val iv2 = rawBytes2.copyOfRange(1, 13)
        assertFalse("Consecutive encryptions must produce distinct IVs", iv1.contentEquals(iv2))

        val loaded = store.load()
        assertEquals(TokenLoadResult.Loaded(record2), loaded)
        assertEquals(KeystoreAeadTokenStore.CANONICAL_AAD.toList(), fakeProvider.lastUsedAad?.toList())
    }
}

/**
 * Pure-JVM JCA test double for Robolectric/unit verification ONLY.
 * Real Android runtime uses [AndroidKeystoreAeadCipherProvider] without software fallback.
 */
internal class InMemoryJcaAeadCipherProvider : AeadCipherProvider {
    var secretKey: SecretKey? = generateKey()
    var lastUsedAad: ByteArray? = null
    var forceKeyInvalidationOnDecrypt: Boolean = false
    var deleteKeyCalled: Boolean = false

    private fun generateKey(): SecretKey {
        val gen = KeyGenerator.getInstance("AES")
        gen.init(256, SecureRandom())
        return gen.generateKey()
    }

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedPayload {
        lastUsedAad = aad.copyOf()
        val key = secretKey ?: generateKey().also { secretKey = it }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Cipher-generated IV (no caller IV parameter)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedPayload(iv = cipher.iv.copyOf(), ciphertextWithTag = ciphertext)
    }

    override fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray, aad: ByteArray): ByteArray {
        lastUsedAad = aad.copyOf()
        if (forceKeyInvalidationOnDecrypt) {
            throw PermanentlyInvalidatedKeyException("Simulated KeyPermanentlyInvalidatedException")
        }
        val key = secretKey ?: throw MissingKeystoreKeyException("Simulated missing keystore alias")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertextWithTag)
    }

    override fun deleteKey() {
        deleteKeyCalled = true
        secretKey = null
    }
}
