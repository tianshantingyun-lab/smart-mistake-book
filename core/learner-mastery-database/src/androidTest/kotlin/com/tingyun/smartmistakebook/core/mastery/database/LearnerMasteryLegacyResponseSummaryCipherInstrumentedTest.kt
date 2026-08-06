package com.tingyun.smartmistakebook.core.mastery.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearnerMasteryLegacyResponseSummaryCipherInstrumentedTest {
    @Test
    fun randomizedEncryptionAuthenticatesEveryBoundIdentityFieldAndRejectsRowSwaps() {
        val alias = testAlias("binding")
        deleteKey(alias)
        try {
            val cipher = AndroidKeystoreLearnerMasteryLegacyResponseSummaryCipher(alias)
            val firstBinding = binding(sourceFactId = "legacy-fact:first", snapshotOrdinal = 0)
            val secondBinding = binding(sourceFactId = "legacy-fact:second", snapshotOrdinal = 1)
            val plaintext = HIGH_ENTROPY_CANARY

            val first = cipher.encrypt(firstBinding, plaintext)
            val repeated = cipher.encrypt(firstBinding, plaintext)
            val second = cipher.encrypt(secondBinding, "另一个回答\\second")

            assertNotEquals(first.nonce.toList(), repeated.nonce.toList())
            assertNotEquals(first.ciphertext.toList(), repeated.ciphertext.toList())
            assertTrue(cipher.decrypt(firstBinding, first) == plaintext)
            assertTrue(cipher.decrypt(firstBinding, repeated) == plaintext)
            assertFalse(
                first.ciphertext.toString(StandardCharsets.UTF_8).contains(plaintext),
            )

            listOf(
                firstBinding.copy(learnerId = "learner:other"),
                firstBinding.copy(sourceGeneration = "9".repeat(64)),
                firstBinding.copy(batchSequence = 2),
                firstBinding.copy(snapshotOrdinal = 2),
                firstBinding.copy(sourceFactId = "legacy-fact:changed"),
                firstBinding.copy(sourceRecordCanonicalFingerprint = "8".repeat(64)),
                firstBinding.copy(snapshotCanonicalFingerprint = "7".repeat(64)),
            ).forEach { changedBinding ->
                assertNull(cipher.decrypt(changedBinding, first))
            }
            assertNull(cipher.decrypt(firstBinding, second))
            assertNull(cipher.decrypt(secondBinding, first))

            val tamperedCiphertext = first.ciphertext.copyOf()
            tamperedCiphertext[tamperedCiphertext.lastIndex] =
                (tamperedCiphertext.last().toInt() xor 1).toByte()
            assertNull(
                cipher.decrypt(
                    firstBinding,
                    first.copy(ciphertext = tamperedCiphertext),
                ),
            )
            val tamperedNonce = first.nonce.copyOf()
            tamperedNonce[0] = (tamperedNonce[0].toInt() xor 1).toByte()
            assertNull(cipher.decrypt(firstBinding, first.copy(nonce = tamperedNonce)))
            assertArrayEquals(first.nonce, first.nonce.copyOf())
        } finally {
            deleteKey(alias)
        }
    }

    @Test
    fun missingKeystoreKeyFailsClosedWithoutRegeneratingDuringDecrypt() {
        val alias = testAlias("key-loss")
        deleteKey(alias)
        try {
            val cipher = AndroidKeystoreLearnerMasteryLegacyResponseSummaryCipher(alias)
            val binding = binding(sourceFactId = "legacy-fact:key-loss", snapshotOrdinal = 0)
            val encrypted = cipher.encrypt(binding, HIGH_ENTROPY_CANARY)
            deleteKey(alias)

            assertNull(cipher.decrypt(binding, encrypted))
            assertFalse(keyStore().containsAlias(alias))
        } finally {
            deleteKey(alias)
        }
    }

    private fun binding(
        sourceFactId: String,
        snapshotOrdinal: Int,
    ) =
        LearnerMasteryLegacyResponseSummaryBinding(
            learnerId = "learner:local",
            sourceGeneration = "a".repeat(64),
            batchSequence = 1,
            snapshotOrdinal = snapshotOrdinal,
            sourceFactId = sourceFactId,
            sourceRecordCanonicalFingerprint = "b".repeat(64),
            snapshotCanonicalFingerprint = "c".repeat(64),
        )

    private fun testAlias(suffix: String) =
        "smart_mistake_book_test_mastery_legacy_summary_${suffix}_${System.nanoTime()}"

    private fun deleteKey(alias: String) {
        keyStore().deleteEntry(alias)
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val HIGH_ENTROPY_CANARY =
            "高熵‘单引号’\"双引号\"\\反斜杠::d7K9!pQ2#xV8@rT4%uN6^mL1&zC5"
    }
}
