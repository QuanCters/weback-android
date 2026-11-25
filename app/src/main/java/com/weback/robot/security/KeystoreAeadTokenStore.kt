package com.weback.robot.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.weback.robot.domain.model.PersistedSessionRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.CharBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedPayload(
    val iv: ByteArray,
    val ciphertextWithTag: ByteArray
)

class MissingKeystoreKeyException(message: String) : Exception(message)
class PermanentlyInvalidatedKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface AeadCipherProvider {
    fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedPayload
    fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray, aad: ByteArray): ByteArray
    fun deleteKey()
}

/**
 * Production AndroidKeyStore AES-256-GCM provider (`ADR-003`).
 * Generates a non-exportable AES-256 key (`randomizedEncryptionRequired = true`), lets AndroidKeyStore
 * generate fresh 12-byte IVs internally, and validates explicit 128-bit GCM authentication tags.
 * Contains ZERO plaintext or software-key fallback.
 */
class AndroidKeystoreAeadCipherProvider(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : AeadCipherProvider {

    private fun loadOrCreateKey(createIfMissing: Boolean): SecretKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = try {
            ks.getKey(keyAlias, null) as? SecretKey
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw PermanentlyInvalidatedKeyException("Keystore alias invalidated", e)
        } catch (e: UnrecoverableKeyException) {
            throw PermanentlyInvalidatedKeyException("Keystore alias unrecoverable", e)
        }
        if (existing != null) return existing
        if (!createIfMissing) return null

        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec)
        return generator.generateKey()
    }

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedPayload {
        var key = try {
            loadOrCreateKey(createIfMissing = true)
                ?: throw IllegalStateException("Unable to generate AndroidKeyStore key")
        } catch (e: PermanentlyInvalidatedKeyException) {
            deleteKey()
            loadOrCreateKey(createIfMissing = true)
                ?: throw IllegalStateException("Unable to recreate AndroidKeyStore key after invalidation", e)
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } catch (_: KeyPermanentlyInvalidatedException) {
            deleteKey()
            key = loadOrCreateKey(createIfMissing = true)
                ?: throw IllegalStateException("Unable to recreate AndroidKeyStore key after invalidation")
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } catch (_: UnrecoverableKeyException) {
            deleteKey()
            key = loadOrCreateKey(createIfMissing = true)
                ?: throw IllegalStateException("Unable to recreate AndroidKeyStore key after unrecoverable state")
            cipher.init(Cipher.ENCRYPT_MODE, key)
        }
        val iv = cipher.iv
        require(iv != null && iv.size == KeystoreAeadTokenStore.IV_BYTES) {
            "Expected cipher-generated 12-byte GCM IV"
        }
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedPayload(iv = iv, ciphertextWithTag = ciphertext)
    }

    override fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray, aad: ByteArray): ByteArray {
        val key = loadOrCreateKey(createIfMissing = false)
            ?: throw MissingKeystoreKeyException("Missing AndroidKeyStore key alias")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertextWithTag)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw PermanentlyInvalidatedKeyException("Key permanently invalidated during decrypt", e)
        } catch (e: UnrecoverableKeyException) {
            throw PermanentlyInvalidatedKeyException("Key unrecoverable during decrypt", e)
        }
    }

    override fun deleteKey() {
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(keyAlias)) {
                ks.deleteEntry(keyAlias)
            }
        }
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "com.weback.robot.auth.session_key.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}

/**
 * ADR-003 encrypted session persistence store.
 *
 * Security / threat-model limitation (`IC-302`): AES-256-GCM guarantees confidentiality and integrity
 * under the device's AndroidKeyStore key, but AEAD alone cannot prevent filesystem-level snapshot replay
 * of an older valid encrypted blob prior to expiration or key deletion/rotation; mitigation relies on
 * Keystore alias cleanup + invalidation tombstones + dual-clock expiry + server revocation.
 */
class KeystoreAeadTokenStore(
    private val storageDir: File,
    private val cipherProvider: AeadCipherProvider,
    private val allowLoopbackHttp: Boolean = false,
    private val deleteHook: ((File) -> Boolean)? = null
) : TokenStore {

    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    @Serializable
    private data class StoredSessionDto(
        val jwtToken: String,
        val apiUrl: String,
        val wssUrl: String,
        val regionName: String,
        val expiresInSeconds: Long,
        val issuedAtEpochMs: Long,
        val lastSeenEpochMs: Long,
        val issuedAtElapsedRealtimeMs: Long? = null,
        val bootGenerationId: String? = null,
        val accountAliasRedacted: String,
        val syntheticRefreshToken: String? = null
    )

    private val targetFile: File get() = File(storageDir, SESSION_FILE_NAME)
    private val tempFile: File get() = File(storageDir, "$SESSION_FILE_NAME.tmp")
    private val tombstoneFile: File get() = File(storageDir, LOGOUT_TOMBSTONE_NAME)

    override suspend fun save(record: PersistedSessionRecord): TokenSaveResult {
        var plaintextBytes: ByteArray? = null
        var envelopeBytes: ByteArray? = null
        return try {
            if (!isValidRecordFields(record)) {
                return TokenSaveResult.Failure("INVALID_SESSION_RECORD")
            }
            if (!storageDir.exists() && !storageDir.mkdirs()) {
                return TokenSaveResult.Failure("STORAGE_MKDIR_FAILED")
            }

            val dto = StoredSessionDto(
                jwtToken = record.jwtToken,
                apiUrl = record.apiUrl,
                wssUrl = record.wssUrl,
                regionName = record.regionName,
                expiresInSeconds = record.expiresInSeconds,
                issuedAtEpochMs = record.issuedAtEpochMs,
                lastSeenEpochMs = record.lastSeenEpochMs,
                issuedAtElapsedRealtimeMs = record.issuedAtElapsedRealtimeMs,
                bootGenerationId = record.bootGenerationId,
                accountAliasRedacted = record.accountAliasRedacted,
                syntheticRefreshToken = record.syntheticRefreshToken
            )
            plaintextBytes = json.encodeToString(dto).toByteArray(Charsets.UTF_8)
            val encrypted = cipherProvider.encrypt(plaintextBytes, CANONICAL_AAD_BYTES)
            require(encrypted.iv.size == IV_BYTES) { "Expected 12-byte GCM IV" }

            val totalSize = 1 + IV_BYTES + encrypted.ciphertextWithTag.size
            if (totalSize !in MIN_RECORD_BYTES..MAX_RECORD_BYTES) {
                return TokenSaveResult.Failure("RECORD_SIZE_OUT_OF_BOUNDS")
            }

            envelopeBytes = ByteArray(totalSize)
            envelopeBytes[0] = SCHEMA_VERSION_V1
            System.arraycopy(encrypted.iv, 0, envelopeBytes, 1, IV_BYTES)
            System.arraycopy(encrypted.ciphertextWithTag, 0, envelopeBytes, 1 + IV_BYTES, encrypted.ciphertextWithTag.size)

            writeAtomically(envelopeBytes)
            // Clear any prior logout tombstone only AFTER atomic replacement; fail if tombstone cannot be cleared
            if (tombstoneFile.exists() && !performDelete(tombstoneFile) && tombstoneFile.exists()) {
                return TokenSaveResult.Failure("TOMBSTONE_CLEAR_FAILED")
            }
            TokenSaveResult.Success
        } catch (t: Throwable) {
            runCatching { if (tempFile.exists()) tempFile.delete() }
            TokenSaveResult.Failure("SAVE_${t::class.java.simpleName.uppercase()}")
        } finally {
            plaintextBytes?.let { Arrays.fill(it, 0.toByte()) }
            envelopeBytes?.let { Arrays.fill(it, 0.toByte()) }
        }
    }

    override suspend fun load(): TokenLoadResult {
        var rawBytes: ByteArray? = null
        var decryptedBytes: ByteArray? = null
        var charArray: CharArray? = null
        var decodedBuffer: CharBuffer? = null
        return try {
            if (tombstoneFile.exists()) {
                wipeBlobAndTombstone()
                return TokenLoadResult.CorruptedOrInvalidated("LOGOUT_TOMBSTONE_PRESENT")
            }
            if (!targetFile.exists()) {
                return TokenLoadResult.Empty
            }

            val fileLength = targetFile.length()
            if (fileLength < MIN_RECORD_BYTES || fileLength > MAX_RECORD_BYTES) {
                wipeBlobAndTombstone()
                return TokenLoadResult.CorruptedOrInvalidated("ENVELOPE_SIZE_BOUNDS")
            }

            rawBytes = targetFile.readBytes()
            if (rawBytes.size !in MIN_RECORD_BYTES..MAX_RECORD_BYTES) {
                wipeBlobAndTombstone()
                return TokenLoadResult.CorruptedOrInvalidated("ENVELOPE_SIZE_BOUNDS")
            }
            if (rawBytes[0] != SCHEMA_VERSION_V1) {
                wipeBlobAndTombstone()
                return TokenLoadResult.CorruptedOrInvalidated("SCHEMA_VERSION_MISMATCH")
            }

            val iv = rawBytes.copyOfRange(1, 1 + IV_BYTES)
            val ciphertextWithTag = rawBytes.copyOfRange(1 + IV_BYTES, rawBytes.size)
            decryptedBytes = cipherProvider.decrypt(iv, ciphertextWithTag, CANONICAL_AAD_BYTES)

            decodedBuffer = Charsets.UTF_8.decode(java.nio.ByteBuffer.wrap(decryptedBytes))
            charArray = CharArray(decodedBuffer.remaining())
            decodedBuffer.get(charArray)
            val dto = json.decodeFromString<StoredSessionDto>(String(charArray))

            val record = PersistedSessionRecord(
                jwtToken = dto.jwtToken,
                apiUrl = dto.apiUrl,
                wssUrl = dto.wssUrl,
                regionName = dto.regionName,
                expiresInSeconds = dto.expiresInSeconds,
                issuedAtEpochMs = dto.issuedAtEpochMs,
                lastSeenEpochMs = dto.lastSeenEpochMs,
                issuedAtElapsedRealtimeMs = dto.issuedAtElapsedRealtimeMs,
                bootGenerationId = dto.bootGenerationId,
                accountAliasRedacted = dto.accountAliasRedacted,
                syntheticRefreshToken = dto.syntheticRefreshToken
            )
            if (!isValidRecordFields(record)) {
                wipeBlobAndTombstone()
                return TokenLoadResult.CorruptedOrInvalidated("POST_DECRYPT_VALIDATION_FAILED")
            }
            TokenLoadResult.Loaded(record)
        } catch (_: MissingKeystoreKeyException) {
            wipeBlobAndTombstone()
            TokenLoadResult.CorruptedOrInvalidated("MISSING_KEYSTORE_KEY")
        } catch (_: PermanentlyInvalidatedKeyException) {
            wipeBlobAndTombstone()
            cipherProvider.deleteKey()
            TokenLoadResult.CorruptedOrInvalidated("KEY_PERMANENTLY_INVALIDATED")
        } catch (_: AEADBadTagException) {
            wipeBlobAndTombstone()
            TokenLoadResult.CorruptedOrInvalidated("AEAD_AUTHENTICATION_FAILED")
        } catch (_: Throwable) {
            wipeBlobAndTombstone()
            TokenLoadResult.CorruptedOrInvalidated("DECODE_OR_IO_ERROR")
        } finally {
            rawBytes?.let { Arrays.fill(it, 0.toByte()) }
            decryptedBytes?.let { Arrays.fill(it, 0.toByte()) }
            charArray?.let { Arrays.fill(it, '\u0000') }
            decodedBuffer?.takeIf { it.hasArray() }?.array()?.let { Arrays.fill(it, '\u0000') }
        }
    }

    override suspend fun clear(): TokenClearResult {
        var allCleared = true
        runCatching {
            if (tempFile.exists() && !performDelete(tempFile)) {
                allCleared = false
            }
            if (targetFile.exists() && !performDelete(targetFile)) {
                allCleared = false
            }
        }.onFailure {
            allCleared = false
        }

        if (!allCleared || targetFile.exists()) {
            // Write fail-closed invalidation tombstone & drop Keystore key so restart cannot restore blob
            runCatching {
                if (!storageDir.exists()) storageDir.mkdirs()
                tombstoneFile.writeText("INVALIDATED")
            }
            cipherProvider.deleteKey()
            return TokenClearResult.Failure("CLEAR_FILE_DELETE_FAILED")
        }

        runCatching { if (tombstoneFile.exists()) performDelete(tombstoneFile) }
        return TokenClearResult.Success
    }

    private fun performDelete(file: File): Boolean =
        deleteHook?.invoke(file) ?: file.delete()

    private fun wipeBlobAndTombstone() {
        runCatching { if (tempFile.exists()) performDelete(tempFile) }
        val blobCleared = runCatching {
            !targetFile.exists() || performDelete(targetFile)
        }.getOrDefault(false) && !targetFile.exists()
        // Only clear invalidation tombstone after confirming the session blob is truly gone
        if (blobCleared) {
            runCatching { if (tombstoneFile.exists()) performDelete(tombstoneFile) }
        }
    }

    private fun writeAtomically(bytes: ByteArray) {
        if (tempFile.exists()) tempFile.delete()
        FileOutputStream(tempFile).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }
        try {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            if (targetFile.exists() && !targetFile.delete()) {
                throw IllegalStateException("Cannot replace existing token file")
            }
            if (!tempFile.renameTo(targetFile)) {
                throw IllegalStateException("Atomic rename fallback failed")
            }
        }
    }

    private fun isValidRecordFields(record: PersistedSessionRecord): Boolean {
        if (record.jwtToken.isBlank() ||
            record.regionName.isBlank() ||
            record.expiresInSeconds <= 0L ||
            record.issuedAtEpochMs <= 0L ||
            record.lastSeenEpochMs < record.issuedAtEpochMs
        ) {
            return false
        }
        val apiValid = UriValidator.validateEndpoint(record.apiUrl, allowLoopbackCleartext = allowLoopbackHttp)
        val wssValid = UriValidator.validateEndpoint(record.wssUrl, allowLoopbackCleartext = allowLoopbackHttp)
        return apiValid is UriValidator.UriValidationResult.Valid &&
            wssValid is UriValidator.UriValidationResult.Valid
    }

    companion object {
        const val SESSION_FILE_NAME = "session_record.bin"
        private const val LOGOUT_TOMBSTONE_NAME = "session_logout_tombstone.flag"
        const val SCHEMA_VERSION_V1: Byte = 0x01
        const val IV_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val MIN_RECORD_BYTES = 1 + IV_BYTES + GCM_TAG_BYTES
        const val MAX_RECORD_BYTES = 16_384
        private val CANONICAL_AAD_BYTES: ByteArray = "com.weback.robot:session:v1:01".toByteArray(Charsets.UTF_8)
        internal fun canonicalAadCopy(): ByteArray = CANONICAL_AAD_BYTES.copyOf()
    }
}
