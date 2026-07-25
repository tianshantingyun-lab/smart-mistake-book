package com.tingyun.smartmistakebook.core.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.Arrays
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreModelSecretVaultTest {
    @Test
    fun roundTripAndRotationReturnOnlyLatestBoundCredential() = withVault { vault, _ ->
        vault.writeSecret("first-secret", binding(GENERATION_1))
        vault.writeSecret("rotated-secret", binding(GENERATION_2))

        assertFalse(vault.ciphertextMatches(binding(GENERATION_1)))
        assertTrue(vault.ciphertextMatches(binding(GENERATION_2)))
        val result = vault.read(binding(GENERATION_2)) as SecretVaultReadResult.Available
        val copied = result.apiKey.copyChars()
        try {
            assertArrayEquals("rotated-secret".toCharArray(), copied)
            assertFalse(result.toString().contains("rotated-secret"))
        } finally {
            Arrays.fill(copied, '\u0000')
            result.apiKey.close()
        }
    }

    @Test
    fun clearRemovesCiphertextAndKeystoreEntry() = withVault { vault, secretFile ->
        val binding = binding(GENERATION_1)
        vault.writeSecret("secret-to-clear", binding)

        vault.clear()

        assertFalse(secretFile.exists())
        assertFalse(vault.ciphertextMatches(binding))
        assertEquals(SecretVaultReadResult.Missing, vault.read(binding))
    }

    @Test
    fun modifiedCiphertextNeverReturnsPlaintext() = withVault { vault, secretFile ->
        val binding = binding(GENERATION_1)
        vault.writeSecret("authenticated-secret", binding)
        val bytes = secretFile.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        secretFile.writeBytes(bytes)
        Arrays.fill(bytes, 0.toByte())

        assertEquals(SecretVaultReadResult.Unavailable, vault.read(binding))
        assertTrue(secretFile.exists())
    }

    @Test
    fun restoredOldEnvelopeCannotAuthenticateAsNewGeneration() = withVault { vault, secretFile ->
        val firstBinding = binding(GENERATION_1)
        val secondBinding = binding(GENERATION_2)
        vault.writeSecret("first-secret", firstBinding)
        val oldEnvelope = secretFile.readBytes()
        try {
            vault.writeSecret("second-secret", secondBinding)
            secretFile.writeBytes(oldEnvelope)

            assertFalse(vault.ciphertextMatches(secondBinding))
            assertEquals(SecretVaultReadResult.Unavailable, vault.read(secondBinding))
        } finally {
            Arrays.fill(oldEnvelope, 0.toByte())
        }
    }

    @Test
    fun normalizedConfigurationFingerprintIsPartOfAuthentication() = withVault { vault, _ ->
        val original = binding(GENERATION_1)
        val changedEndpoint = original.copy(baseUrl = "https://other.example.com/v1")
        vault.writeSecret("bound-secret", original)

        assertFalse(vault.ciphertextMatches(changedEndpoint))
        assertEquals(SecretVaultReadResult.Unavailable, vault.read(changedEndpoint))
    }

    @Test
    fun oversizedPrivateFileIsRejectedByMaxPlusOneBoundedRead() =
        withVault { vault, secretFile ->
            val parent = checkNotNull(secretFile.parentFile)
            check(parent.isDirectory || parent.mkdirs())
            val oversized = ByteArray(1_000_000)
            try {
                secretFile.writeBytes(oversized)
            } finally {
                Arrays.fill(oversized, 0.toByte())
            }

            assertFalse(vault.ciphertextMatches(binding(GENERATION_1)))
            assertEquals(SecretVaultReadResult.Unavailable, vault.read(binding(GENERATION_1)))
        }

    private fun withVault(
        block: suspend (AndroidKeystoreModelSecretVault, File) -> Unit,
    ) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testId = UUID.randomUUID().toString()
        val directory = File(context.noBackupFilesDir, "model-vault-tests/$testId")
        val secretFile = File(directory, "secret.bin")
        val vault = AndroidKeystoreModelSecretVault(
            context = context,
            keyAlias = "smart_mistake_book_test_$testId",
            secretFile = secretFile,
        )
        try {
            block(vault, secretFile)
        } finally {
            vault.clear()
            secretFile.delete()
            directory.delete()
        }
    }

    private suspend fun AndroidKeystoreModelSecretVault.writeSecret(
        value: String,
        binding: ModelSecretBinding,
    ) {
        val chars = value.toCharArray()
        try {
            write(chars, binding)
        } finally {
            Arrays.fill(chars, '\u0000')
        }
    }

    private fun binding(generationId: String) = ModelSecretBinding(
        generationId = generationId,
        provider = "openai-compatible",
        baseUrl = "https://api.example.com/v1",
        modelId = "model-1",
    )

    private companion object {
        const val GENERATION_1 = "00000000-0000-0000-0000-000000000001"
        const val GENERATION_2 = "00000000-0000-0000-0000-000000000002"
    }
}
