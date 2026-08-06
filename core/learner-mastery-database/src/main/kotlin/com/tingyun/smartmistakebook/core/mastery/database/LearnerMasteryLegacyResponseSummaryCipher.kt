package com.tingyun.smartmistakebook.core.mastery.database

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class LearnerMasteryEncryptedLegacyResponseSummary(
    val keyVersion: Int,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

internal data class LearnerMasteryLegacyResponseSummaryBinding(
    val learnerId: String,
    val sourceGeneration: String,
    val batchSequence: Long,
    val snapshotOrdinal: Int,
    val sourceFactId: String,
    val sourceRecordCanonicalFingerprint: String,
    val snapshotCanonicalFingerprint: String,
    val keyVersion: Int = LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_KEY_VERSION,
) {
    init {
        require(learnerId.isNotBlank() && learnerId.length <= MAX_AAD_TEXT_CHARS)
        require(sourceGeneration.isNotBlank() && sourceGeneration.length <= MAX_AAD_TEXT_CHARS)
        require(batchSequence > 0L)
        require(snapshotOrdinal >= 0)
        require(sourceFactId.isNotBlank() && sourceFactId.length <= MAX_AAD_TEXT_CHARS)
        require(
            sourceRecordCanonicalFingerprint.isNotBlank() &&
                sourceRecordCanonicalFingerprint.length <= MAX_AAD_TEXT_CHARS,
        )
        require(
            snapshotCanonicalFingerprint.isNotBlank() &&
                snapshotCanonicalFingerprint.length <= MAX_AAD_TEXT_CHARS,
        )
        require(keyVersion == LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_KEY_VERSION)
    }

    fun canonicalAad(): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeText(AAD_DOMAIN)
                output.writeText(learnerId)
                output.writeText(sourceGeneration)
                output.writeLong(batchSequence)
                output.writeInt(snapshotOrdinal)
                output.writeText(sourceFactId)
                output.writeText(sourceRecordCanonicalFingerprint)
                output.writeText(snapshotCanonicalFingerprint)
                output.writeInt(keyVersion)
            }
            bytes.toByteArray()
        }
}

internal interface LearnerMasteryLegacyResponseSummaryCipher {
    fun encrypt(
        binding: LearnerMasteryLegacyResponseSummaryBinding,
        plaintext: String,
    ): LearnerMasteryEncryptedLegacyResponseSummary

    fun decrypt(
        binding: LearnerMasteryLegacyResponseSummaryBinding,
        encrypted: LearnerMasteryEncryptedLegacyResponseSummary,
    ): String?
}

/** At-rest owner for the exact legacy response text retained only by the cutover ledger. */
internal class AndroidKeystoreLearnerMasteryLegacyResponseSummaryCipher(
    private val keyAlias: String = LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_KEY_ALIAS,
) : LearnerMasteryLegacyResponseSummaryCipher {
    private val keyCacheLock = Any()
    private var cachedKey: SecretKey? = null
    private val decryptCipherThreadLocal =
        ThreadLocal.withInitial {
            Cipher.getInstance(CIPHER_TRANSFORMATION)
        }

    init {
        require(keyAlias.isNotBlank() && keyAlias.length <= 128)
    }

    override fun encrypt(
        binding: LearnerMasteryLegacyResponseSummaryBinding,
        plaintext: String,
    ): LearnerMasteryEncryptedLegacyResponseSummary {
        require(binding.keyVersion == LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_KEY_VERSION)
        require(plaintext.isNotBlank() && plaintext == plaintext.trim())
        val plaintextBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        require(plaintextBytes.size in 1..MAX_PLAINTEXT_BYTES)
        val aad = binding.canonicalAad()
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
            cipher.updateAAD(aad)
            val nonce = cipher.iv.copyOf()
            check(nonce.size == GCM_NONCE_BYTES) {
                "Learner-mastery response-summary cipher returned an invalid nonce"
            }
            val ciphertext = cipher.doFinal(plaintextBytes)
            check(ciphertext.size in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES) {
                "Learner-mastery response-summary cipher returned an invalid envelope"
            }
            LearnerMasteryEncryptedLegacyResponseSummary(
                keyVersion = binding.keyVersion,
                nonce = nonce,
                ciphertext = ciphertext,
            )
        } finally {
            Arrays.fill(plaintextBytes, 0)
            Arrays.fill(aad, 0)
        }
    }

    override fun decrypt(
        binding: LearnerMasteryLegacyResponseSummaryBinding,
        encrypted: LearnerMasteryEncryptedLegacyResponseSummary,
    ): String? {
        if (
            binding.keyVersion != LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_KEY_VERSION ||
            encrypted.keyVersion != binding.keyVersion ||
            encrypted.nonce.size != GCM_NONCE_BYTES ||
            encrypted.ciphertext.size !in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES
        ) return null
        val aad = binding.canonicalAad()
        return try {
            val key = loadExistingKey() ?: return null
            val cipher = checkNotNull(decryptCipherThreadLocal.get())
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, encrypted.nonce),
            )
            cipher.updateAAD(aad)
            val plaintextBytes = cipher.doFinal(encrypted.ciphertext)
            if (plaintextBytes.size !in 1..MAX_PLAINTEXT_BYTES) {
                Arrays.fill(plaintextBytes, 0)
                null
            } else {
                try {
                    String(plaintextBytes, StandardCharsets.UTF_8)
                } finally {
                    Arrays.fill(plaintextBytes, 0)
                }
            }
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: ProviderException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            Arrays.fill(aad, 0)
        }
    }

    private fun loadExistingKey(): SecretKey? {
        cachedKey?.let { return it }
        return synchronized(keyCacheLock) {
            cachedKey?.let { return@synchronized it }
            val loaded = loadExistingKeyFromStore()
            if (loaded != null) {
                cachedKey = loaded
            }
            loaded
        }
    }

    private fun loadOrCreateKey(): SecretKey =
        loadExistingKey() ?: synchronized(KEY_CREATION_LOCK) {
            loadExistingKey() ?: KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER,
            ).run {
                init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }.also { cachedKey = it }
        }

    private fun loadExistingKeyFromStore(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as? SecretKey
    }
}

private fun DataOutputStream.writeText(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= MAX_AAD_FIELD_BYTES)
    writeInt(bytes.size)
    write(bytes)
}

internal const val LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_KEY_ALIAS =
    "smart_mistake_book_learner_mastery_legacy_response_summary_v1"
internal const val LEARNER_MASTERY_LEGACY_RESPONSE_SUMMARY_KEY_VERSION = 1

private const val AAD_DOMAIN = "learner-mastery-legacy-response-summary-aad-v1"
private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val GCM_NONCE_BYTES = 12
private const val MIN_CIPHERTEXT_BYTES = GCM_TAG_BITS / 8 + 1
private const val MAX_PLAINTEXT_BYTES = 4 * 1_024
private const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + GCM_TAG_BITS / 8
private const val MAX_AAD_TEXT_CHARS = 1_024
private const val MAX_AAD_FIELD_BYTES = 4 * 1_024
private val KEY_CREATION_LOCK = Any()
