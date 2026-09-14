package com.tingyun.smartmistakebook.core.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.tingyun.smartmistakebook.core.domain.ModelApiKey
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Arrays
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The single Keystore alias this app owns for the model API key.
 *
 * Authored here and referenced by every reader/eraser of that alias — notably
 * `AndroidBackupRepository.deleteAllData`, whose "delete everything" sweep must
 * target the real alias. A second, independently written copy of this string in
 * that sweep is exactly how the alias survived deletion once already.
 */
internal const val MODEL_SECRET_KEY_ALIAS = "smart_mistake_book_model_api_key_v2"

internal data class ModelSecretBinding(
    val generationId: String,
    val provider: String,
    val baseUrl: String,
    val modelId: String,
)

internal sealed interface SecretVaultReadResult {
    data class Available(val apiKey: ModelApiKey) : SecretVaultReadResult
    data object Missing : SecretVaultReadResult
    data object Unavailable : SecretVaultReadResult
}

internal interface ModelSecretVault {
    suspend fun ciphertextMatches(binding: ModelSecretBinding): Boolean
    suspend fun write(apiKey: CharArray, binding: ModelSecretBinding)
    suspend fun read(binding: ModelSecretBinding): SecretVaultReadResult
    suspend fun clear()
}

/**
 * Prevents a closeable result created on [dispatcher] from leaking when withContext's prompt
 * cancellation guarantee discards that result while dispatching back to its caller.
 */
internal suspend fun <T : Any> withContextClosingDiscarded(
    dispatcher: CoroutineDispatcher,
    block: suspend () -> T,
    closeDiscarded: (T) -> Unit,
): T {
    val pendingResult = AtomicReference<T?>()
    return try {
        val result = withContext(dispatcher) {
            block().also(pendingResult::set)
        }
        pendingResult.set(null)
        result
    } catch (error: Throwable) {
        pendingResult.getAndSet(null)?.let(closeDiscarded)
        throw error
    }
}

/**
 * Stores only AES-GCM ciphertext below noBackupFilesDir. The non-exportable AES key remains in the
 * Android Keystore and no plaintext is converted to a String or written to a log.
 */
internal class AndroidKeystoreModelSecretVault(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val keyAlias: String = MODEL_SECRET_KEY_ALIAS,
    secretFile: File = File(
        context.applicationContext.noBackupFilesDir,
        DEFAULT_SECRET_RELATIVE_PATH,
    ),
) : ModelSecretVault {
    private val atomicFile = AtomicFile(secretFile)
    private val mutationLock = Mutex()

    override suspend fun ciphertextMatches(binding: ModelSecretBinding): Boolean =
        withContext(ioDispatcher) {
            mutationLock.withLock {
                try {
                    when (val stored = readEnvelopeBounded()) {
                        BoundedEnvelopeRead.Invalid,
                        BoundedEnvelopeRead.Missing,
                        -> false

                        is BoundedEnvelopeRead.Available -> try {
                            envelopeBindingMatches(stored.bytes, binding)
                        } finally {
                            Arrays.fill(stored.bytes, 0.toByte())
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    false
                }
            }
        }

    override suspend fun write(
        apiKey: CharArray,
        binding: ModelSecretBinding,
    ) = withContext(ioDispatcher) {
        mutationLock.withLock {
            ensureParentDirectory()
            val plaintext = encodeUtf8(apiKey)
            val material = bindingMaterial(binding)
            try {
                val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
                cipher.updateAAD(material.aad)
                val ciphertext = cipher.doFinal(plaintext)
                val envelope = encodeEnvelope(material.bindingTag, cipher.iv, ciphertext)
                try {
                    writeAtomically(envelope)
                    verifyWrittenPlaintext(plaintext, binding)
                } finally {
                    Arrays.fill(ciphertext, 0.toByte())
                    Arrays.fill(envelope, 0.toByte())
                }
            } finally {
                material.wipe()
                Arrays.fill(plaintext, 0.toByte())
            }
        }
    }

    override suspend fun read(binding: ModelSecretBinding): SecretVaultReadResult =
        withContextClosingDiscarded(
            dispatcher = ioDispatcher,
            block = { mutationLock.withLock { readLocked(binding) } },
            closeDiscarded = { result ->
                if (result is SecretVaultReadResult.Available) result.apiKey.close()
            },
        )

    override suspend fun clear() = withContext(ioDispatcher) {
        mutationLock.withLock {
            atomicFile.delete()
            val keyStore = loadKeyStore()
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        }
    }

    private fun readLocked(binding: ModelSecretBinding): SecretVaultReadResult = try {
        when (val stored = readEnvelopeBounded()) {
            BoundedEnvelopeRead.Missing -> SecretVaultReadResult.Missing
            BoundedEnvelopeRead.Invalid -> SecretVaultReadResult.Unavailable
            is BoundedEnvelopeRead.Available -> try {
                val plaintext = decryptEnvelope(stored.bytes, binding)
                    ?: return SecretVaultReadResult.Unavailable
                try {
                    decodeApiKey(plaintext)
                } finally {
                    Arrays.fill(plaintext, 0.toByte())
                }
            } finally {
                Arrays.fill(stored.bytes, 0.toByte())
            }
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        SecretVaultReadResult.Unavailable
    }

    private fun ensureParentDirectory() {
        val parent = atomicFile.baseFile.parentFile
            ?: throw IOException("Model secret path has no parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Unable to create the private model secret directory")
        }
    }

    private fun writeAtomically(envelope: ByteArray) {
        val output = atomicFile.startWrite()
        try {
            output.write(envelope)
            output.flush()
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            runCatching { atomicFile.failWrite(output) }
            throw error
        }
    }

    private fun verifyWrittenPlaintext(
        expectedPlaintext: ByteArray,
        binding: ModelSecretBinding,
    ) {
        val stored = readEnvelopeBounded()
        if (stored !is BoundedEnvelopeRead.Available) {
            throw IOException("Unable to verify the persisted model credential")
        }
        try {
            val actualPlaintext = decryptEnvelope(stored.bytes, binding)
                ?: throw IOException("Persisted model credential authentication failed")
            try {
                if (!MessageDigest.isEqual(expectedPlaintext, actualPlaintext)) {
                    throw IOException("Persisted model credential verification failed")
                }
            } finally {
                Arrays.fill(actualPlaintext, 0.toByte())
            }
        } finally {
            Arrays.fill(stored.bytes, 0.toByte())
        }
    }

    private fun readEnvelopeBounded(): BoundedEnvelopeRead {
        val input = try {
            atomicFile.openRead()
        } catch (_: FileNotFoundException) {
            return BoundedEnvelopeRead.Missing
        }
        val buffer = ByteArray(MAX_ENVELOPE_BYTES + 1)
        return try {
            input.use { stream ->
                var total = 0
                while (total < buffer.size) {
                    val count = stream.read(buffer, total, buffer.size - total)
                    if (count < 0) break
                    if (count == 0) {
                        val singleByte = stream.read()
                        if (singleByte < 0) break
                        buffer[total++] = singleByte.toByte()
                    } else {
                        total += count
                    }
                }
                if (total !in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES) {
                    BoundedEnvelopeRead.Invalid
                } else {
                    BoundedEnvelopeRead.Available(buffer.copyOf(total))
                }
            }
        } finally {
            Arrays.fill(buffer, 0.toByte())
        }
    }

    private fun envelopeBindingMatches(
        envelopeBytes: ByteArray,
        binding: ModelSecretBinding,
    ): Boolean {
        val envelope = decodeEnvelope(envelopeBytes) ?: return false
        val material = bindingMaterial(binding)
        return try {
            MessageDigest.isEqual(envelope.bindingTag, material.bindingTag)
        } finally {
            envelope.wipe()
            material.wipe()
        }
    }

    private fun decryptEnvelope(
        envelopeBytes: ByteArray,
        binding: ModelSecretBinding,
    ): ByteArray? {
        val envelope = decodeEnvelope(envelopeBytes) ?: return null
        val material = bindingMaterial(binding)
        return try {
            if (!MessageDigest.isEqual(envelope.bindingTag, material.bindingTag)) return null
            val key = loadExistingKey() ?: return null
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, envelope.iv))
            cipher.updateAAD(material.aad)
            cipher.doFinal(envelope.ciphertext)
        } finally {
            envelope.wipe()
            material.wipe()
        }
    }

    private fun bindingMaterial(binding: ModelSecretBinding): BindingMaterial {
        val configurationFingerprint = fingerprint(
            CONFIGURATION_FINGERPRINT_PREFIX,
            binding.provider,
            binding.baseUrl,
            binding.modelId,
        )
        val generationBytes = binding.generationId.toByteArray(StandardCharsets.UTF_8)
        val aad = ByteBuffer.allocate(
            AAD_PREFIX.size + Int.SIZE_BYTES + generationBytes.size + configurationFingerprint.size,
        ).put(AAD_PREFIX)
            .putInt(generationBytes.size)
            .put(generationBytes)
            .put(configurationFingerprint)
            .array()
        return try {
            BindingMaterial(
                aad = aad,
                bindingTag = MessageDigest.getInstance(SHA_256).digest(aad),
            )
        } finally {
            Arrays.fill(generationBytes, 0.toByte())
            Arrays.fill(configurationFingerprint, 0.toByte())
        }
    }

    private fun fingerprint(prefix: ByteArray, vararg values: String): ByteArray {
        val digest = MessageDigest.getInstance(SHA_256)
        digest.update(prefix)
        values.forEach { value ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            val length = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array()
            try {
                digest.update(length)
                digest.update(bytes)
            } finally {
                Arrays.fill(length, 0.toByte())
                Arrays.fill(bytes, 0.toByte())
            }
        }
        return digest.digest()
    }

    private fun loadExistingKey(): SecretKey? {
        val keyStore = loadKeyStore()
        if (!keyStore.containsAlias(keyAlias)) return null
        return keyStore.getKey(keyAlias, null) as? SecretKey
    }

    private fun loadOrCreateKey(): SecretKey = loadExistingKey() ?: KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        ANDROID_KEYSTORE,
    ).run {
        init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generateKey()
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    private fun encodeEnvelope(
        bindingTag: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        require(bindingTag.size == BINDING_TAG_BYTES)
        require(iv.size == GCM_IV_BYTES)
        require(ciphertext.size in GCM_TAG_BYTES..MAX_CIPHERTEXT_BYTES)
        return ByteBuffer.allocate(HEADER_BYTES + bindingTag.size + iv.size + ciphertext.size)
            .put(FORMAT_VERSION)
            .put(iv.size.toByte())
            .put(bindingTag)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    private fun decodeEnvelope(bytes: ByteArray): EncryptedEnvelope? {
        if (bytes.size !in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes)
        if (buffer.get() != FORMAT_VERSION) return null
        val ivLength = buffer.get().toInt() and 0xff
        if (ivLength != GCM_IV_BYTES) return null
        if (buffer.remaining() < BINDING_TAG_BYTES + ivLength + GCM_TAG_BYTES) return null
        val bindingTag = ByteArray(BINDING_TAG_BYTES).also(buffer::get)
        val iv = ByteArray(ivLength).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        return EncryptedEnvelope(bindingTag, iv, ciphertext)
    }

    private fun encodeUtf8(chars: CharArray): ByteArray {
        val buffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(chars))
        return try {
            ByteArray(buffer.remaining()).also(buffer::get)
        } finally {
            buffer.wipe()
        }
    }

    private fun decodeApiKey(plaintext: ByteArray): SecretVaultReadResult {
        val buffer = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(plaintext))
        return try {
            val chars = CharArray(buffer.remaining()).also(buffer::get)
            try {
                SecretVaultReadResult.Available(ModelApiKey.from(chars))
            } finally {
                Arrays.fill(chars, '\u0000')
            }
        } finally {
            buffer.wipe()
        }
    }

    private fun ByteBuffer.wipe() {
        if (hasArray()) {
            Arrays.fill(array(), 0.toByte())
            return
        }
        clear()
        while (hasRemaining()) put(0.toByte())
    }

    private fun CharBuffer.wipe() {
        if (hasArray()) {
            Arrays.fill(array(), '\u0000')
            return
        }
        clear()
        while (hasRemaining()) put('\u0000')
    }

    private data class BindingMaterial(
        val aad: ByteArray,
        val bindingTag: ByteArray,
    ) {
        fun wipe() {
            Arrays.fill(aad, 0.toByte())
            Arrays.fill(bindingTag, 0.toByte())
        }
    }

    private data class EncryptedEnvelope(
        val bindingTag: ByteArray,
        val iv: ByteArray,
        val ciphertext: ByteArray,
    ) {
        fun wipe() {
            Arrays.fill(bindingTag, 0.toByte())
            Arrays.fill(iv, 0.toByte())
            Arrays.fill(ciphertext, 0.toByte())
        }
    }

    private sealed interface BoundedEnvelopeRead {
        data class Available(val bytes: ByteArray) : BoundedEnvelopeRead
        data object Missing : BoundedEnvelopeRead
        data object Invalid : BoundedEnvelopeRead
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val SHA_256 = "SHA-256"
        const val DEFAULT_SECRET_RELATIVE_PATH = "model-secrets/api-key-v2.bin"
        const val AES_KEY_BITS = 256
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val GCM_IV_BYTES = 12
        const val BINDING_TAG_BYTES = 32
        const val HEADER_BYTES = 2
        const val MAX_CIPHERTEXT_BYTES = 16_400
        const val MIN_ENVELOPE_BYTES =
            HEADER_BYTES + BINDING_TAG_BYTES + GCM_IV_BYTES + GCM_TAG_BYTES
        const val MAX_ENVELOPE_BYTES =
            HEADER_BYTES + BINDING_TAG_BYTES + GCM_IV_BYTES + MAX_CIPHERTEXT_BYTES
        const val FORMAT_VERSION: Byte = 2
        val AAD_PREFIX: ByteArray =
            "smart-mistake-book/model-api-key/aad/v2".toByteArray(StandardCharsets.US_ASCII)
        val CONFIGURATION_FINGERPRINT_PREFIX: ByteArray =
            "smart-mistake-book/model-configuration/v1".toByteArray(StandardCharsets.US_ASCII)
    }
}
