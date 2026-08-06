package com.tingyun.smartmistakebook.core.database

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class TutorFreeResponseEncryptedAnswer(
    val keyVersion: Int,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

internal interface TutorFreeResponseOutboxCipher {
    fun encrypt(aad: ByteArray, plaintext: String): TutorFreeResponseEncryptedAnswer

    fun decrypt(
        aad: ByteArray,
        encrypted: TutorFreeResponseEncryptedAnswer,
    ): String?

    /** Keyed and scope-separated; unlike raw SHA-256 it is not enumerable or cross-question. */
    fun answerBinding(bindingContext: ByteArray, plaintext: String): String
}

/** Android-Keystore AES-GCM owner for recoverable, conversation-scoped pending answers. */
internal class AndroidKeystoreTutorFreeResponseOutboxCipher : TutorFreeResponseOutboxCipher {
    override fun encrypt(aad: ByteArray, plaintext: String): TutorFreeResponseEncryptedAnswer {
        require(aad.size in 1..MAX_AAD_BYTES && plaintext.isNotEmpty())
        val plaintextBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        require(plaintextBytes.size <= MAX_PLAINTEXT_BYTES)
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
            cipher.updateAAD(aad)
            val nonce = cipher.iv.copyOf()
            require(nonce.size == GCM_NONCE_BYTES)
            val ciphertext = cipher.doFinal(plaintextBytes)
            require(ciphertext.size <= MAX_CIPHERTEXT_BYTES)
            TutorFreeResponseEncryptedAnswer(
                keyVersion = KEY_VERSION,
                nonce = nonce,
                ciphertext = ciphertext,
            )
        } finally {
            Arrays.fill(plaintextBytes, 0)
        }
    }

    override fun decrypt(
        aad: ByteArray,
        encrypted: TutorFreeResponseEncryptedAnswer,
    ): String? {
        if (
            aad.size !in 1..MAX_AAD_BYTES ||
            encrypted.keyVersion != KEY_VERSION ||
            encrypted.nonce.size != GCM_NONCE_BYTES ||
            encrypted.ciphertext.size !in 1..MAX_CIPHERTEXT_BYTES
        ) return null
        return try {
            val key = loadExistingKey() ?: return null
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, encrypted.nonce),
            )
            cipher.updateAAD(aad)
            val plaintextBytes = cipher.doFinal(encrypted.ciphertext)
            if (plaintextBytes.size > MAX_PLAINTEXT_BYTES) {
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
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun answerBinding(bindingContext: ByteArray, plaintext: String): String {
        require(bindingContext.size in 1..MAX_BINDING_CONTEXT_BYTES)
        val plaintextBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        require(plaintextBytes.size in 1..MAX_PLAINTEXT_BYTES)
        val digest = try {
            Mac.getInstance(HMAC_ALGORITHM).run {
                init(loadOrCreateBindingKey())
                update(
                    byteArrayOf(
                        (bindingContext.size ushr 24).toByte(),
                        (bindingContext.size ushr 16).toByte(),
                        (bindingContext.size ushr 8).toByte(),
                        bindingContext.size.toByte(),
                    ),
                )
                update(bindingContext)
                doFinal(plaintextBytes)
            }
        } finally {
            Arrays.fill(plaintextBytes, 0)
        }
        return try {
            buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0f])
                }
            }
        } finally {
            Arrays.fill(digest, 0)
        }
    }

    private fun loadExistingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun loadOrCreateKey(): SecretKey = loadExistingKey() ?: synchronized(keyLock) {
        loadExistingKey() ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun loadExistingBindingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getKey(BINDING_KEY_ALIAS, null) as? SecretKey
    }

    private fun loadOrCreateBindingKey(): SecretKey =
        loadExistingBindingKey() ?: synchronized(keyLock) {
            loadExistingBindingKey() ?: KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                KEYSTORE_PROVIDER,
            ).run {
                init(
                    KeyGenParameterSpec.Builder(
                        BINDING_KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
        }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "smart_mistake_book_tutor_free_response_outbox_v1"
        const val BINDING_KEY_ALIAS =
            "smart_mistake_book_tutor_free_response_outbox_binding_v1"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val KEY_VERSION = 1
        const val GCM_TAG_BITS = 128
        const val GCM_NONCE_BYTES = 12
        const val MAX_AAD_BYTES = 4_096
        const val MAX_BINDING_CONTEXT_BYTES = 4_096
        const val MAX_PLAINTEXT_BYTES = 256 * 1_024
        const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + (GCM_TAG_BITS / 8)
        val keyLock = Any()
        const val HEX = "0123456789abcdef"
    }
}
