package com.weback.robot.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.domain.model.PersistedSessionRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class KeystoreAeadTokenStoreTamperTest {

    private fun sampleRecord() = PersistedSessionRecord(
        jwtToken = "jwt-tamper-test",
        apiUrl = "https://api.loopback.invalid/api",
        wssUrl = "wss://ws.loopback.invalid/ws",
        regionName = "us-east-1",
        expiresInSeconds = 3600L,
        issuedAtEpochMs = 1_700_000_000_000L,
        lastSeenEpochMs = 1_700_000_000_000L,
        issuedAtElapsedRealtimeMs = 10_000L,
        bootGenerationId = "boot_1",
        accountAliasRedacted = "u***@example.invalid"
    )

    @Test
    fun testTamperBitFlipBadVersionOversizeTruncationAndMissingKeyFailClosed() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.noBackupFilesDir, "test_tamper_store")
        dir.deleteRecursively()

        val provider = InMemoryJcaAeadCipherProvider()
        val store = KeystoreAeadTokenStore(storageDir = dir, cipherProvider = provider)
        val blobFile = File(dir, KeystoreAeadTokenStore.SESSION_FILE_NAME)

        // 1. Tag / ciphertext bit-flip
        assertTrue(store.save(sampleRecord()) is TokenSaveResult.Success)
        val tampered = blobFile.readBytes()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0xFF).toByte()
        blobFile.writeBytes(tampered)
        assertTrue(store.load() is TokenLoadResult.CorruptedOrInvalidated)
        assertFalse("Corrupt blob must be wiped fail-closed", blobFile.exists())

        // 2. Bad schema version
        assertTrue(store.save(sampleRecord()) is TokenSaveResult.Success)
        val badVer = blobFile.readBytes()
        badVer[0] = 0x02
        blobFile.writeBytes(badVer)
        assertTrue(store.load() is TokenLoadResult.CorruptedOrInvalidated)
        assertFalse(blobFile.exists())

        // 3. Truncated < 29 bytes
        dir.mkdirs()
        blobFile.writeBytes(ByteArray(28) { 0x01 })
        assertTrue(store.load() is TokenLoadResult.CorruptedOrInvalidated)
        assertFalse(blobFile.exists())

        // 4. Oversized > 16384 bytes
        blobFile.writeBytes(ByteArray(KeystoreAeadTokenStore.MAX_RECORD_BYTES + 1) { 0x01 })
        assertTrue(store.load() is TokenLoadResult.CorruptedOrInvalidated)
        assertFalse(blobFile.exists())

        // 5. Missing key with orphaned blob
        assertTrue(store.save(sampleRecord()) is TokenSaveResult.Success)
        provider.secretKey = null
        assertTrue(store.load() is TokenLoadResult.CorruptedOrInvalidated)
        assertFalse(blobFile.exists())

        // 6. KeyPermanentlyInvalidatedException deletes blob AND keystore alias
        assertTrue(store.save(sampleRecord()) is TokenSaveResult.Success)
        provider.forceKeyInvalidationOnDecrypt = true
        provider.deleteKeyCalled = false
        assertTrue(store.load() is TokenLoadResult.CorruptedOrInvalidated)
        assertFalse(blobFile.exists())
        assertTrue(provider.deleteKeyCalled)
    }

    @Test
    fun testPostDecryptMalformedRecordAndClearFailureTombstonePreventSilentRestore() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.noBackupFilesDir, "test_tombstone_store")
        dir.deleteRecursively()

        val provider = InMemoryJcaAeadCipherProvider()
        val store = KeystoreAeadTokenStore(storageDir = dir, cipherProvider = provider)

        // Authenticated ciphertext containing untrusted http:// apiUrl fails post-decrypt validation
        val untrustedUrlJson = """
            {"jwtToken":"ok","apiUrl":"http://evil.invalid/api","wssUrl":"wss://api.loopback.invalid/ws","regionName":"us","expiresInSeconds":3600,"issuedAtEpochMs":1700000000000,"lastSeenEpochMs":1700000000000,"issuedAtElapsedRealtimeMs":10,"bootGenerationId":"boot_1","accountAliasRedacted":"a"}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val payload = provider.encrypt(untrustedUrlJson, KeystoreAeadTokenStore.canonicalAadCopy())
        dir.mkdirs()
        File(dir, KeystoreAeadTokenStore.SESSION_FILE_NAME)
            .writeBytes(byteArrayOf(KeystoreAeadTokenStore.SCHEMA_VERSION_V1) + payload.iv + payload.ciphertextWithTag)

        assertTrue(store.load() is TokenLoadResult.CorruptedOrInvalidated)
        assertFalse(File(dir, KeystoreAeadTokenStore.SESSION_FILE_NAME).exists())

        // Wrong AAD must also reject authentication and wipe blob
        val wrongAadPayload = provider.encrypt(untrustedUrlJson, "com.weback.robot:session:v1:99".toByteArray())
        File(dir, KeystoreAeadTokenStore.SESSION_FILE_NAME)
            .writeBytes(byteArrayOf(KeystoreAeadTokenStore.SCHEMA_VERSION_V1) + wrongAadPayload.iv + wrongAadPayload.ciphertextWithTag)
        assertTrue(store.load() is TokenLoadResult.CorruptedOrInvalidated)
        assertFalse(File(dir, KeystoreAeadTokenStore.SESSION_FILE_NAME).exists())

        // Clear failure leaves tombstoned state -> subsequent process restart load() must fail closed
        assertTrue(store.save(sampleRecord()) is TokenSaveResult.Success)
        val failingDeleteStore = KeystoreAeadTokenStore(
            storageDir = dir,
            cipherProvider = provider,
            deleteHook = { false } // simulate filesystem refusal to delete blob
        )
        val clearResult = failingDeleteStore.clear()
        assertTrue(clearResult is TokenClearResult.Failure)

        // Simulated restart with normal store instance sees tombstone -> must refuse silent restore and clean up
        val restartedStore = KeystoreAeadTokenStore(storageDir = dir, cipherProvider = provider)
        assertTrue(restartedStore.load() is TokenLoadResult.CorruptedOrInvalidated)
        assertFalse(File(dir, KeystoreAeadTokenStore.SESSION_FILE_NAME).exists())
    }
}
