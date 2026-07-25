package com.tingyun.smartmistakebook.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tingyun.smartmistakebook.core.domain.ModelApiKey
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityVerification
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityVerificationWriteResult
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityTestStartResult
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationIssue
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationMutationResult
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationUpdate
import com.tingyun.smartmistakebook.core.domain.ModelCredentialReadResult
import java.io.IOException
import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreDelegateTest {
    @Test
    fun `capability result is bound to one exact credential generation`() = withStore { fixture ->
        val first = fixture.store.saveWithKey(validUpdate(), "first-secret")
            as ModelConfigurationMutationResult.Success
        val testStart = fixture.store.beginCapabilityTest(first.configuration)
            as ModelCapabilityTestStartResult.Started
        val verification = ModelCapabilityVerification(
            provider = first.configuration.provider,
            baseUrl = first.configuration.baseUrl,
            modelId = first.configuration.modelId,
            configurationVersion = first.configuration.configurationVersion,
            configurationUpdatedAtEpochMillis = first.configuration.updatedAtEpochMillis,
            supportsImageInput = true,
            supportsStructuredOutput = true,
            testedAtEpochMillis = 200_000L,
            testStartSequence = testStart.sequence,
        )

        assertEquals(
            ModelCapabilityVerificationWriteResult.SAVED,
            fixture.store.recordCapabilityVerification(verification),
        )
        assertEquals(
            verification,
            fixture.store.configuration.first { it.capabilityVerification != null }
                .capabilityVerification,
        )

        val replacement = fixture.store.saveWithKey(validUpdate(), "replacement-secret")
            as ModelConfigurationMutationResult.Success
        assertTrue(replacement.configuration.configurationVersion != verification.configurationVersion)
        assertEquals(null, replacement.configuration.capabilityVerification)
        assertEquals(
            ModelCapabilityVerificationWriteResult.CONFIGURATION_CHANGED,
            fixture.store.recordCapabilityVerification(verification),
        )
    }

    @Test
    fun `older success cannot overwrite a newer authentication failure`() = withStore { fixture ->
        val configured = fixture.store.saveWithKey(validUpdate(), "first-secret")
            .let { it as ModelConfigurationMutationResult.Success }
            .configuration
        val older = fixture.store.beginCapabilityTest(configured)
            as ModelCapabilityTestStartResult.Started
        val newer = fixture.store.beginCapabilityTest(configured)
            as ModelCapabilityTestStartResult.Started
        val newerAuthenticationFailure = ModelCapabilityVerification(
            provider = configured.provider,
            baseUrl = configured.baseUrl,
            modelId = configured.modelId,
            configurationVersion = configured.configurationVersion,
            configurationUpdatedAtEpochMillis = configured.updatedAtEpochMillis,
            supportsImageInput = false,
            supportsStructuredOutput = false,
            testedAtEpochMillis = 201_000L,
            testStartSequence = newer.sequence,
        )
        val olderSuccess = newerAuthenticationFailure.copy(
            supportsImageInput = true,
            supportsStructuredOutput = true,
            testedAtEpochMillis = 202_000L,
            testStartSequence = older.sequence,
        )

        assertEquals(
            ModelCapabilityVerificationWriteResult.SAVED,
            fixture.store.recordCapabilityVerification(newerAuthenticationFailure),
        )
        assertEquals(
            ModelCapabilityVerificationWriteResult.CONFIGURATION_CHANGED,
            fixture.store.recordCapabilityVerification(olderSuccess),
        )
        assertEquals(
            newerAuthenticationFailure,
            fixture.store.configuration.first { it.capabilityVerification != null }
                .capabilityVerification,
        )
    }

    @Test
    fun `configuration collector and save do not deadlock each other`() = withStore { fixture ->
        coroutineScope {
            val configured = async {
                fixture.store.configuration.first { it.isConfigured }
            }
            yield()

            val result = withTimeout(1_000) {
                fixture.store.saveWithKey(validUpdate(), "first-secret")
            }

            assertTrue(result is ModelConfigurationMutationResult.Success)
            assertTrue(withTimeout(1_000) { configured.await() }.isConfigured)
        }
    }

    @Test
    fun `stale staging emission cannot compensate a committed credential`() =
        withStore { fixture ->
            coroutineScope {
                val configured = async {
                    fixture.store.configuration.first { it.isConfigured }
                }
                yield()

                val writeGate = fixture.vault.blockNextWrite()
                val save = async {
                    fixture.store.saveWithKey(validUpdate(), "first-secret")
                }
                writeGate.entered.await()
                repeat(10) { yield() }
                writeGate.release.complete(Unit)

                assertTrue(save.await() is ModelConfigurationMutationResult.Success)
                assertEquals(
                    "model-1",
                    withTimeout(1_000) { configured.await() }.modelId,
                )
                fixture.store.assertCredential("first-secret")
            }
        }

    @Test
    fun `save persists only approved metadata while vault owns secret`() = withStore { fixture ->
        val result = fixture.store.saveWithKey(validUpdate(), "first-secret")

        val success = result as ModelConfigurationMutationResult.Success
        assertEquals("https://api.example.com/v1", success.configuration.baseUrl)
        assertTrue(success.configuration.isConfigured)
        assertEquals(123_456L, success.configuration.updatedAtEpochMillis)
        assertArrayEquals("first-secret".toCharArray(), fixture.vault.activeSecret)
        assertEquals(
            setOf(
                "provider",
                "base_url",
                "model_id",
                "is_configured",
                "updated_at_epoch_millis",
                "credential_generation_id",
            ),
            fixture.dataStore.data.first().asMap().keys.map { it.name }.toSet(),
        )
        val diagnosticText = fixture.store.configuration.first().toString()
        assertFalse(diagnosticText.contains("first-secret"))
        assertFalse(diagnosticText.contains(GENERATION_1))
        assertFalse(diagnosticText.contains("https://api.example.com/v1"))
        assertFalse(diagnosticText.contains("model-1"))
    }

    @Test
    fun `invalid public http performs no vault or datastore write`() = withStore { fixture ->
        val result = fixture.store.saveWithKey(
            validUpdate().copy(baseUrl = "http://api.example.com/v1"),
            "secret-value",
        )

        val rejected = result as ModelConfigurationMutationResult.Rejected
        assertTrue(ModelConfigurationIssue.INSECURE_HTTP_NOT_ALLOWED in rejected.issues)
        assertEquals(0, fixture.vault.writeCount)
        assertTrue(fixture.dataStore.data.first().asMap().isEmpty())
    }

    @Test
    fun `credential lease waits for save and returns matching configuration and key`() =
        withStore { fixture ->
            coroutineScope {
                fixture.store.saveWithKey(validUpdate(), "first-secret")
                val writeGate = fixture.vault.blockNextWrite()
                val replacement = validUpdate().copy(
                    provider = "replacement-provider",
                    baseUrl = "https://replacement.example.com/v2",
                    modelId = "replacement-model",
                )

                val save = async {
                    fixture.store.saveWithKey(replacement, "replacement-secret")
                }
                writeGate.entered.await()
                val read = async { fixture.store.readCredential() }
                yield()
                assertFalse(read.isCompleted)

                writeGate.release.complete(Unit)
                assertTrue(save.await() is ModelConfigurationMutationResult.Success)
                val credential = read.await() as ModelCredentialReadResult.Available
                val copied = credential.apiKey.copyChars()
                try {
                    assertEquals("replacement-provider", credential.configuration.provider)
                    assertEquals(
                        "https://replacement.example.com/v2",
                        credential.configuration.baseUrl,
                    )
                    assertEquals("replacement-model", credential.configuration.modelId)
                    assertArrayEquals("replacement-secret".toCharArray(), copied)
                } finally {
                    Arrays.fill(copied, '\u0000')
                    credential.apiKey.close()
                }
            }
        }

    @Test
    fun `failed final metadata commit removes newly written key`() = withStore { fixture ->
        coroutineScope {
            fixture.store.saveWithKey(validUpdate(), "first-secret")
            val finalCommit = fixture.dataStore.blockAndFailUpdate(
                callNumber = fixture.dataStore.updateCount + 2,
            )

            val save = async {
                fixture.store.saveWithKey(
                    validUpdate().copy(modelId = "replacement-model"),
                    "replacement-secret",
                )
            }
            finalCommit.entered.await()
            val read = async { fixture.store.readCredential() }
            yield()
            assertFalse(read.isCompleted)

            finalCommit.release.complete(Unit)
            assertEquals(ModelConfigurationMutationResult.StorageUnavailable, save.await())
            assertEquals(ModelCredentialReadResult.Missing, read.await())
            assertEquals(null, fixture.vault.activeSecret)
            assertEquals(0, fixture.vault.readCount)
            assertFalse(fixture.store.configuration.first().isConfigured)
            assertFalse(fixture.dataStore.hasPreference("pending_secret_mutation"))
        }
    }

    @Test
    fun `failed metadata commit leaves retryable cleanup journal when vault cleanup fails`() =
        withStore { fixture ->
            coroutineScope {
                fixture.store.saveWithKey(validUpdate(), "first-secret")
                val gate = fixture.dataStore.blockAndFailUpdate(
                    callNumber = fixture.dataStore.updateCount + 2,
                )
                fixture.vault.failNextClears(1)
                val save = async {
                    fixture.store.saveWithKey(
                        validUpdate().copy(modelId = "replacement-model"),
                        "replacement-secret",
                    )
                }
                gate.entered.await()
                gate.release.complete(Unit)

                assertEquals(ModelConfigurationMutationResult.StorageUnavailable, save.await())
                assertTrue(fixture.dataStore.hasPreference("pending_secret_mutation"))
                assertArrayEquals("replacement-secret".toCharArray(), fixture.vault.activeSecret)

                assertEquals(ModelCredentialReadResult.Missing, fixture.store.readCredential())
                assertEquals(null, fixture.vault.activeSecret)
                assertFalse(fixture.dataStore.hasPreference("pending_secret_mutation"))
            }
        }

    @Test
    fun `CancellationException during save staging restores previous credential visibility`() =
        withStore { fixture ->
            fixture.store.saveWithKey(validUpdate(), "first-secret")

            coroutineScope {
                val stagingGate = fixture.dataStore.blockUpdate(
                    callNumber = fixture.dataStore.updateCount + 1,
                )
                val save = async {
                    fixture.store.saveWithKey(
                        validUpdate().copy(modelId = "replacement-model"),
                        "replacement-secret",
                    )
                }
                stagingGate.entered.await()

                save.cancelAndJoin()

                assertTrue(save.isCancelled)
            }

            assertTrue(fixture.store.configuration.first().isConfigured)
            fixture.store.assertCredential("first-secret")
        }

    @Test
    fun `clear is serialized behind in-flight save and removes its credential`() =
        withStore { fixture ->
            coroutineScope {
                val writeGate = fixture.vault.blockNextWrite()
                val save = async { fixture.store.saveWithKey(validUpdate(), "first-secret") }
                writeGate.entered.await()
                val clear = async { fixture.store.clear() }
                yield()
                assertFalse(clear.isCompleted)

                writeGate.release.complete(Unit)
                assertTrue(save.await() is ModelConfigurationMutationResult.Success)
                assertTrue(clear.await() is ModelConfigurationMutationResult.Success)
                assertEquals(ModelCredentialReadResult.Missing, fixture.store.readCredential())
                assertEquals(null, fixture.vault.activeSecret)
                assertTrue(fixture.dataStore.data.first().asMap().isEmpty())
            }
        }

    @Test
    fun `failed clear is recovered from durable cleanup journal on configuration read`() =
        withStore { fixture ->
            fixture.store.saveWithKey(validUpdate(), "first-secret")
            fixture.vault.failNextClears(1)

            assertEquals(
                ModelConfigurationMutationResult.StorageUnavailable,
                fixture.store.clear(),
            )
            assertTrue(fixture.dataStore.hasPreference("pending_secret_mutation"))
            assertArrayEquals("first-secret".toCharArray(), fixture.vault.activeSecret)

            assertFalse(fixture.store.configuration.first().isConfigured)
            assertEquals(null, fixture.vault.activeSecret)
            assertTrue(fixture.dataStore.data.first().asMap().isEmpty())
        }

    @Test
    fun `CancellationException during clear staging restores previous credential visibility`() =
        withStore { fixture ->
            fixture.store.saveWithKey(validUpdate(), "first-secret")

            coroutineScope {
                val stagingGate = fixture.dataStore.blockUpdate(
                    callNumber = fixture.dataStore.updateCount + 1,
                )
                val clear = async { fixture.store.clear() }
                stagingGate.entered.await()

                clear.cancelAndJoin()

                assertTrue(clear.isCancelled)
            }

            assertTrue(fixture.store.configuration.first().isConfigured)
            fixture.store.assertCredential("first-secret")
        }

    @Test
    fun `configuration read IOException fails closed without deleting credential`() =
        withStore { fixture ->
            fixture.store.saveWithKey(validUpdate(), "first-secret")
            fixture.dataStore.failNextDataReads(1)

            assertFalse(fixture.store.configuration.first().isConfigured)
            assertArrayEquals("first-secret".toCharArray(), fixture.vault.activeSecret)
            assertEquals(0, fixture.vault.readCount)

            fixture.store.assertCredential("first-secret")
        }

    @Test
    fun `long lived configuration collector recovers after read IOException`() =
        withStore { fixture ->
            fixture.store.saveWithKey(validUpdate(), "first-secret")
            fixture.dataStore.failNextDataReads(1)

            val snapshots = withTimeout(1_000) {
                fixture.store.configuration.take(2).toList()
            }

            assertFalse(snapshots.first().isConfigured)
            assertTrue(snapshots.last().isConfigured)
            assertArrayEquals("first-secret".toCharArray(), fixture.vault.activeSecret)
        }

    @Test
    fun `credential read IOException is unavailable and preserves credential for retry`() =
        withStore { fixture ->
            fixture.store.saveWithKey(validUpdate(), "first-secret")
            fixture.dataStore.failNextDataReads(1)

            assertEquals(ModelCredentialReadResult.Unavailable, fixture.store.readCredential())
            assertArrayEquals("first-secret".toCharArray(), fixture.vault.activeSecret)
            assertEquals(0, fixture.vault.readCount)

            fixture.store.assertCredential("first-secret")
        }

    @Test
    fun `missing metadata cleans legacy orphan secret without reading it`() = withStore { fixture ->
        fixture.vault.replace(
            secret = "orphan-secret".toCharArray(),
            binding = ModelSecretBinding(
                generationId = GENERATION_1,
                provider = "openai-compatible",
                baseUrl = "https://api.example.com/v1",
                modelId = "model-1",
            ),
        )

        assertFalse(fixture.store.configuration.first().isConfigured)
        assertEquals(null, fixture.vault.activeSecret)
        assertEquals(0, fixture.vault.readCount)
    }

    @Test
    fun `rotation changes generation and returns only latest credential`() = withStore { fixture ->
        fixture.store.saveWithKey(validUpdate(), "first-secret")
        val firstBinding = fixture.vault.activeBinding

        val rotated = fixture.store.rotateWithKey("second-secret")

        assertTrue(rotated is ModelConfigurationMutationResult.Success)
        assertTrue(firstBinding?.generationId != fixture.vault.activeBinding?.generationId)
        val credential = fixture.store.readCredential() as ModelCredentialReadResult.Available
        val copied = credential.apiKey.copyChars()
        try {
            assertArrayEquals("second-secret".toCharArray(), copied)
        } finally {
            Arrays.fill(copied, '\u0000')
            credential.apiKey.close()
        }
    }

    @Test
    fun `restored old generation is not configured and fails authentication`() =
        withStore { fixture ->
            fixture.store.saveWithKey(validUpdate(), "first-secret")
            fixture.store.rotateWithKey("second-secret")
            fixture.vault.restorePreviousGeneration()

            assertFalse(fixture.store.configuration.first().isConfigured)
            assertEquals(ModelCredentialReadResult.Unavailable, fixture.store.readCredential())
            assertFalse(fixture.store.configuration.first().isConfigured)
        }

    @Test
    fun `corrupt ciphertext fails closed and clears configured marker`() = withStore { fixture ->
        fixture.store.saveWithKey(validUpdate(), "first-secret")
        fixture.vault.unavailable = true

        assertEquals(ModelCredentialReadResult.Unavailable, fixture.store.readCredential())
        assertFalse(fixture.store.configuration.first().isConfigured)
        assertEquals(false, fixture.dataStore.data.first().asMap().values.single { it is Boolean })
    }

    @Test
    fun `missing ciphertext is never exposed as configured`() = withStore { fixture ->
        fixture.store.saveWithKey(validUpdate(), "first-secret")
        fixture.vault.eraseActive()

        assertFalse(fixture.store.configuration.first().isConfigured)
        assertEquals(ModelCredentialReadResult.Missing, fixture.store.readCredential())
    }

    @Test
    fun `authenticated but invalid secret still fails closed`() = withStore { fixture ->
        fixture.store.saveWithKey(validUpdate(), "first-secret")
        fixture.vault.replaceActiveSecret("invalid secret".toCharArray())

        assertEquals(ModelCredentialReadResult.Unavailable, fixture.store.readCredential())
        assertFalse(fixture.store.configuration.first().isConfigured)
    }

    @Test
    fun `tampered datastore metadata is not emitted to presentation code`() = withStore { fixture ->
        fixture.vault.replace(
            secret = "vault-secret".toCharArray(),
            binding = ModelSecretBinding(
                generationId = GENERATION_1,
                provider = "safe",
                baseUrl = "https://safe.example.com",
                modelId = "safe",
            ),
        )
        fixture.dataStore.edit { values ->
            values[stringPreferencesKey("provider")] = "safe\u202eunsafe"
            values[stringPreferencesKey("base_url")] = "http://127.0.0.1:11434/v1"
            values[stringPreferencesKey("model_id")] = "x".repeat(300)
            values[booleanPreferencesKey("is_configured")] = true
            values[longPreferencesKey("updated_at_epoch_millis")] = 123L
            values[stringPreferencesKey("credential_generation_id")] = GENERATION_1
        }

        val snapshot = fixture.store.configuration.first()
        assertEquals("", snapshot.provider)
        assertEquals("", snapshot.baseUrl)
        assertEquals("", snapshot.modelId)
        assertFalse(snapshot.isConfigured)
        assertEquals(0L, snapshot.updatedAtEpochMillis)
    }

    private fun validUpdate() = ModelConfigurationUpdate(
        provider = "openai-compatible",
        baseUrl = "api.example.com/v1/",
        modelId = "model-1",
    )

    private suspend fun StoreDelegate.saveWithKey(
        update: ModelConfigurationUpdate,
        value: String,
    ): ModelConfigurationMutationResult {
        val key = ModelApiKey.from(value.toCharArray())
        return try {
            save(update, key)
        } finally {
            key.close()
        }
    }

    private suspend fun StoreDelegate.rotateWithKey(value: String): ModelConfigurationMutationResult {
        val key = ModelApiKey.from(value.toCharArray())
        return try {
            rotateApiKey(key)
        } finally {
            key.close()
        }
    }

    private suspend fun StoreDelegate.assertCredential(value: String) {
        val credential = readCredential() as ModelCredentialReadResult.Available
        val copied = credential.apiKey.copyChars()
        try {
            assertArrayEquals(value.toCharArray(), copied)
        } finally {
            Arrays.fill(copied, '\u0000')
            credential.apiKey.close()
        }
    }

    private fun withStore(block: suspend (Fixture) -> Unit) = runBlocking {
        val dataStore = ControllableDataStore()
        val vault = FakeVault()
        val generationSource = GenerationSource()
        val store = StoreDelegate(dataStore, vault, { 123_456L }, generationSource::next)
        val fixture = Fixture(store, dataStore, vault)
        try {
            block(fixture)
        } finally {
            vault.eraseAll()
        }
    }

    private data class Fixture(
        val store: StoreDelegate,
        val dataStore: ControllableDataStore,
        val vault: FakeVault,
    )

    private class GenerationSource {
        private var nextValue = 1L

        fun next(): String = "00000000-0000-0000-0000-${nextValue++.toString().padStart(12, '0')}"
    }

    private class ControllableDataStore : DataStore<Preferences> {
        private val calls = AtomicInteger()
        private val dataReadFailuresRemaining = AtomicInteger()
        private val updateLock = Mutex()
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        @Volatile private var gatedCall: GatedUpdate? = null

        override val data: Flow<Preferences> = flow {
            if (dataReadFailuresRemaining.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) {
                throw IOException("Injected metadata read failure")
            }
            emitAll(state)
        }

        val updateCount: Int
            get() = calls.get()

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = updateLock.withLock {
            val callNumber = calls.incrementAndGet()
            gatedCall?.takeIf { it.callNumber == callNumber }?.let { gate ->
                gate.entered.complete(Unit)
                gate.release.await()
                if (gate.fail) throw IOException("Injected metadata write failure")
            }

            val updated = try {
                transform(state.value).toPreferences()
            } catch (error: Exception) {
                throw AssertionError("Unexpected in-memory transform failure on update $callNumber", error)
            }
            state.value = updated
            updated
        }

        fun blockAndFailUpdate(callNumber: Int): GatedUpdate = GatedUpdate(
            callNumber = callNumber,
            fail = true,
        ).also { gatedCall = it }

        fun blockUpdate(callNumber: Int): GatedUpdate = GatedUpdate(
            callNumber = callNumber,
            fail = false,
        ).also { gatedCall = it }

        fun failNextDataReads(count: Int) {
            require(count > 0)
            dataReadFailuresRemaining.set(count)
        }

        suspend fun hasPreference(name: String): Boolean =
            state.first().asMap().keys.any { it.name == name }
    }

    private data class GatedUpdate(
        val callNumber: Int,
        val fail: Boolean,
        val entered: CompletableDeferred<Unit> = CompletableDeferred(),
        val release: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private class FakeVault : ModelSecretVault {
        private val history = mutableListOf<StoredSecret>()
        private var active: StoredSecret? = null
        private var writeGate: VaultWriteGate? = null

        var unavailable: Boolean = false
        var writeCount: Int = 0
            private set
        var readCount: Int = 0
            private set
        private var clearFailuresRemaining: Int = 0

        val activeSecret: CharArray?
            get() = active?.chars
        val activeBinding: ModelSecretBinding?
            get() = active?.binding

        override suspend fun ciphertextMatches(binding: ModelSecretBinding): Boolean =
            !unavailable && active?.binding == binding

        override suspend fun write(apiKey: CharArray, binding: ModelSecretBinding) {
            writeGate?.also { gate ->
                gate.entered.complete(Unit)
                gate.release.await()
                writeGate = null
            }
            active?.let(history::add)
            active = StoredSecret(apiKey.copyOf(), binding)
            unavailable = false
            writeCount += 1
        }

        override suspend fun read(binding: ModelSecretBinding): SecretVaultReadResult {
            readCount += 1
            val stored = active ?: return SecretVaultReadResult.Missing
            if (unavailable || stored.binding != binding) return SecretVaultReadResult.Unavailable
            return SecretVaultReadResult.Available(ModelApiKey.from(stored.chars))
        }

        override suspend fun clear() {
            if (clearFailuresRemaining > 0) {
                clearFailuresRemaining -= 1
                throw IOException("Injected vault clear failure")
            }
            eraseAll()
        }

        fun blockNextWrite(): VaultWriteGate = VaultWriteGate().also { writeGate = it }

        fun failNextClears(count: Int) {
            require(count > 0)
            clearFailuresRemaining = count
        }

        fun restorePreviousGeneration() {
            val previous = history.last()
            active?.erase()
            active = StoredSecret(previous.chars.copyOf(), previous.binding)
        }

        fun replaceActiveSecret(secret: CharArray) {
            val binding = checkNotNull(active?.binding)
            replace(secret, binding)
        }

        fun replace(secret: CharArray, binding: ModelSecretBinding) {
            active?.erase()
            active = StoredSecret(secret.copyOf(), binding)
            Arrays.fill(secret, '\u0000')
            unavailable = false
        }

        fun eraseActive() {
            active?.erase()
            active = null
        }

        fun eraseAll() {
            eraseActive()
            history.forEach(StoredSecret::erase)
            history.clear()
        }
    }

    private data class StoredSecret(
        val chars: CharArray,
        val binding: ModelSecretBinding,
    ) {
        fun erase() = Arrays.fill(chars, '\u0000')
    }

    private data class VaultWriteGate(
        val entered: CompletableDeferred<Unit> = CompletableDeferred(),
        val release: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private companion object {
        const val GENERATION_1 = "00000000-0000-0000-0000-000000000001"
    }
}
