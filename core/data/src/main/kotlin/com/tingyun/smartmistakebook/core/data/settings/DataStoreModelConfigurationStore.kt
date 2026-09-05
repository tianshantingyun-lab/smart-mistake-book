package com.tingyun.smartmistakebook.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.tingyun.smartmistakebook.core.domain.ModelApiKey
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityVerification
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityVerificationWriteResult
import com.tingyun.smartmistakebook.core.domain.ModelCapabilityTestStartResult
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationIssue
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationMutationResult
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationSnapshot
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationUpdate
import com.tingyun.smartmistakebook.core.domain.ModelCredentialReadResult
import java.io.IOException
import java.util.Arrays
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Local-only model configuration storage. Callers own [scope] and should create one app-wide
 * instance. The strict-offline flavor can omit construction entirely; this class has no network
 * dependency and never initiates a connection.
 */
class DataStoreModelConfigurationStore(
    context: Context,
    scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis,
) : ModelConfigurationStore {
    private val delegate = StoreDelegate(
        dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { context.applicationContext.preferencesDataStoreFile(DATASTORE_FILE) },
        ),
        vault = AndroidKeystoreModelSecretVault(context.applicationContext),
        clock = clock,
        generationSource = { UUID.randomUUID().toString() },
    )

    override val configuration: Flow<ModelConfigurationSnapshot> = delegate.configuration

    override suspend fun save(
        update: ModelConfigurationUpdate,
        apiKey: ModelApiKey,
    ): ModelConfigurationMutationResult = delegate.save(update, apiKey)

    override suspend fun rotateApiKey(apiKey: ModelApiKey): ModelConfigurationMutationResult =
        delegate.rotateApiKey(apiKey)

    override suspend fun readCredential(): ModelCredentialReadResult = delegate.readCredential()

    override suspend fun beginCapabilityTest(
        configuration: ModelConfigurationSnapshot,
    ): ModelCapabilityTestStartResult = delegate.beginCapabilityTest(configuration)

    override suspend fun recordCapabilityVerification(
        verification: ModelCapabilityVerification,
    ): ModelCapabilityVerificationWriteResult =
        delegate.recordCapabilityVerification(verification)

    override suspend fun clear(): ModelConfigurationMutationResult = delegate.clear()

    private companion object {
        const val DATASTORE_FILE = "model_configuration.preferences_pb"
    }
}

internal class StoreDelegate(
    private val dataStore: DataStore<Preferences>,
    private val vault: ModelSecretVault,
    private val clock: () -> Long,
    private val generationSource: () -> String,
) : ModelConfigurationStore {
    private val mutationLock = Mutex()
    private val forceUnconfigured = MutableStateFlow(false)
    private val preferenceReads: Flow<PreferenceRead> = dataStore.data
        .map<Preferences, PreferenceRead>(PreferenceRead::Available)
        .retryWhen { error, attempt ->
            if (error !is IOException) return@retryWhen false
            emit(PreferenceRead.Unavailable)
            delay(readRetryDelayMillis(attempt))
            true
        }

    override val configuration: Flow<ModelConfigurationSnapshot> = preferenceReads
        .combine(forceUnconfigured) { read, _ ->
            when (read) {
                is PreferenceRead.Available -> currentConfigurationSnapshot()
                PreferenceRead.Unavailable -> ModelConfigurationSnapshot()
            }
        }
        .distinctUntilChanged()

    override suspend fun save(
        update: ModelConfigurationUpdate,
        apiKey: ModelApiKey,
    ): ModelConfigurationMutationResult = mutationLock.withLock {
        if (!recoverPendingSecretMutation()) {
            return@withLock ModelConfigurationMutationResult.StorageUnavailable
        }
        val chars = apiKey.copyOrNull()
            ?: return@withLock rejected(ModelConfigurationIssue.API_KEY_REQUIRED)
        try {
            when (val validation = ModelConfigurationValidator.validate(update, chars)) {
                is ModelConfigurationValidationResult.Invalid ->
                    ModelConfigurationMutationResult.Rejected(validation.issues)

                is ModelConfigurationValidationResult.Valid -> {
                    val currentValues = readPreferences()
                        ?: return@withLock ModelConfigurationMutationResult.StorageUnavailable
                    val currentGeneration = currentValues[CREDENTIAL_GENERATION_ID]
                    val nextGeneration = nextGeneration(currentGeneration)
                        ?: return@withLock ModelConfigurationMutationResult.StorageUnavailable
                    persist(validation.configuration, chars, nextGeneration)
                }
            }
        } finally {
            Arrays.fill(chars, '\u0000')
        }
    }

    override suspend fun rotateApiKey(apiKey: ModelApiKey): ModelConfigurationMutationResult =
        mutationLock.withLock {
            if (!recoverPendingSecretMutation()) {
                return@withLock ModelConfigurationMutationResult.StorageUnavailable
            }
            val currentValues = readPreferences()
                ?: return@withLock ModelConfigurationMutationResult.StorageUnavailable
            val current = readStoredCredential(currentValues)
                ?: return@withLock ModelConfigurationMutationResult.MissingConfiguration
            val chars = apiKey.copyOrNull()
                ?: return@withLock rejected(ModelConfigurationIssue.API_KEY_REQUIRED)
            try {
                val validation = ModelConfigurationValidator.validate(
                    update = ModelConfigurationUpdate(
                        provider = current.configuration.provider,
                        baseUrl = current.configuration.baseUrl,
                        modelId = current.configuration.modelId,
                    ),
                    apiKey = chars,
                )
                when (validation) {
                    is ModelConfigurationValidationResult.Invalid ->
                        ModelConfigurationMutationResult.Rejected(validation.issues)

                    is ModelConfigurationValidationResult.Valid -> {
                        val nextGeneration = nextGeneration(current.binding.generationId)
                            ?: return@withLock ModelConfigurationMutationResult.StorageUnavailable
                        persist(validation.configuration, chars, nextGeneration)
                    }
                }
            } finally {
                Arrays.fill(chars, '\u0000')
            }
        }

    override suspend fun readCredential(): ModelCredentialReadResult = mutationLock.withLock {
        val initialValues = readPreferences()
            ?: return@withLock ModelCredentialReadResult.Unavailable
        if (initialValues.pendingSecretMutation() != null) {
            recoverPendingSecretMutation(initialValues)
            return@withLock ModelCredentialReadResult.Missing
        }
        if (forceUnconfigured.value) return@withLock ModelCredentialReadResult.Missing
        val stored = readStoredCredential(initialValues)
        if (stored == null) {
            vault.clearSafely()
            return@withLock ModelCredentialReadResult.Missing
        }
        if (!stored.configuration.isConfigured) {
            vault.clearSafely()
            return@withLock ModelCredentialReadResult.Missing
        }

        when (val result = vault.readSafely(stored.binding)) {
            is SecretVaultReadResult.Available -> {
                if (result.apiKey.isValidFor(stored.configuration)) {
                    ModelCredentialReadResult.Available(stored.configuration, result.apiKey)
                } else {
                    result.apiKey.close()
                    failClosed()
                    ModelCredentialReadResult.Unavailable
                }
            }

            SecretVaultReadResult.Missing -> {
                failClosed()
                ModelCredentialReadResult.Missing
            }

            SecretVaultReadResult.Unavailable -> {
                failClosed()
                ModelCredentialReadResult.Unavailable
            }
        }
    }

    override suspend fun recordCapabilityVerification(
        verification: ModelCapabilityVerification,
    ): ModelCapabilityVerificationWriteResult = mutationLock.withLock {
        val initialValues = readPreferences()
            ?: return@withLock ModelCapabilityVerificationWriteResult.STORAGE_UNAVAILABLE
        if (initialValues.pendingSecretMutation() != null) {
            if (!recoverPendingSecretMutation(initialValues)) {
                return@withLock ModelCapabilityVerificationWriteResult.STORAGE_UNAVAILABLE
            }
        }
        val currentValues = readPreferences()
            ?: return@withLock ModelCapabilityVerificationWriteResult.STORAGE_UNAVAILABLE
        val current = readStoredCredential(currentValues)?.configuration
            ?: return@withLock ModelCapabilityVerificationWriteResult.CONFIGURATION_CHANGED
        if (!verification.matches(current)) {
            return@withLock ModelCapabilityVerificationWriteResult.CONFIGURATION_CHANGED
        }
        val latestTestSequence = currentValues[LATEST_CAPABILITY_TEST_SEQUENCE] ?: 0L
        if (verification.testStartSequence <= 0L ||
            verification.testStartSequence != latestTestSequence
        ) {
            return@withLock ModelCapabilityVerificationWriteResult.CONFIGURATION_CHANGED
        }
        if (!storageSucceeded {
                dataStore.edit { values -> values.writeCapabilityVerification(verification) }
            }
        ) {
            return@withLock ModelCapabilityVerificationWriteResult.STORAGE_UNAVAILABLE
        }
        ModelCapabilityVerificationWriteResult.SAVED
    }

    override suspend fun beginCapabilityTest(
        configuration: ModelConfigurationSnapshot,
    ): ModelCapabilityTestStartResult = mutationLock.withLock {
        val initialValues = readPreferences()
            ?: return@withLock ModelCapabilityTestStartResult.StorageUnavailable
        if (initialValues.pendingSecretMutation() != null &&
            !recoverPendingSecretMutation(initialValues)
        ) {
            return@withLock ModelCapabilityTestStartResult.StorageUnavailable
        }
        val currentValues = readPreferences()
            ?: return@withLock ModelCapabilityTestStartResult.StorageUnavailable
        val current = readStoredCredential(currentValues)?.configuration
            ?: return@withLock ModelCapabilityTestStartResult.ConfigurationChanged
        if (!configuration.isSameGenerationAs(current)) {
            return@withLock ModelCapabilityTestStartResult.ConfigurationChanged
        }
        val previous = currentValues[LATEST_CAPABILITY_TEST_SEQUENCE] ?: 0L
        if (previous == Long.MAX_VALUE) {
            return@withLock ModelCapabilityTestStartResult.StorageUnavailable
        }
        val next = previous + 1L
        if (!storageSucceeded {
                dataStore.edit { values -> values[LATEST_CAPABILITY_TEST_SEQUENCE] = next }
            }
        ) {
            return@withLock ModelCapabilityTestStartResult.StorageUnavailable
        }
        ModelCapabilityTestStartResult.Started(next)
    }

    override suspend fun clear(): ModelConfigurationMutationResult = mutationLock.withLock {
        val initialValues = readPreferences()
            ?: return@withLock ModelConfigurationMutationResult.StorageUnavailable
        val pending = initialValues.pendingSecretMutation()
        if (pending != null) {
            if (!recoverPendingSecretMutation(initialValues)) {
                return@withLock ModelConfigurationMutationResult.StorageUnavailable
            }
            if (pending == PendingSecretMutation.CLEAR) {
                return@withLock ModelConfigurationMutationResult.Success(
                    ModelConfigurationSnapshot(),
                )
            }
        }

        forceUnconfigured.value = true
        try {
            if (!storageSucceeded {
                    dataStore.edit { values ->
                        values[IS_CONFIGURED] = false
                        values[PENDING_SECRET_MUTATION] = PendingSecretMutation.CLEAR.storedValue
                    }
                }
            ) {
                return@withLock ModelConfigurationMutationResult.StorageUnavailable
            }

            if (!recoverPendingSecretMutation()) {
                return@withLock ModelConfigurationMutationResult.StorageUnavailable
            }
            ModelConfigurationMutationResult.Success(ModelConfigurationSnapshot())
        } finally {
            forceUnconfigured.value = false
        }
    }

    private suspend fun persist(
        configuration: ValidatedModelConfiguration,
        chars: CharArray,
        generationId: String,
    ): ModelConfigurationMutationResult {
        forceUnconfigured.value = true
        try {
            if (!storageSucceeded {
                    dataStore.edit { values ->
                        values[IS_CONFIGURED] = false
                        values[PENDING_SECRET_MUTATION] = PendingSecretMutation.REPLACE.storedValue
                    }
                }
            ) {
                return ModelConfigurationMutationResult.StorageUnavailable
            }

            val binding = configuration.toBinding(generationId)
            if (!storageSucceeded { vault.write(chars, binding) }) {
                recoverPendingSecretMutation()
                return ModelConfigurationMutationResult.StorageUnavailable
            }

            val updatedAt = clock().coerceAtLeast(1L)
            if (!storageSucceeded {
                    dataStore.edit { values ->
                        values[PROVIDER] = configuration.provider
                        values[BASE_URL] = configuration.baseUrl
                        values[MODEL_ID] = configuration.modelId
                        values[IS_CONFIGURED] = true
                        values[UPDATED_AT] = updatedAt
                        values[CREDENTIAL_GENERATION_ID] = generationId
                        values.removeCapabilityVerification()
                        values.remove(LATEST_CAPABILITY_TEST_SEQUENCE)
                        values.remove(PENDING_SECRET_MUTATION)
                    }
                }
            ) {
                recoverPendingSecretMutation()
                return ModelConfigurationMutationResult.StorageUnavailable
            }

            return ModelConfigurationMutationResult.Success(
                ModelConfigurationSnapshot(
                    provider = configuration.provider,
                    baseUrl = configuration.baseUrl,
                    modelId = configuration.modelId,
                    isConfigured = true,
                    updatedAtEpochMillis = updatedAt,
                    configurationVersion = generationId,
                ),
            )
        } finally {
            forceUnconfigured.value = false
        }
    }

    private suspend fun failClosed() {
        forceUnconfigured.value = true
        if (storageSucceeded {
                dataStore.edit { values ->
                    values[IS_CONFIGURED] = false
                    values[PENDING_SECRET_MUTATION] = PendingSecretMutation.REPLACE.storedValue
                }
            }
        ) {
            recoverPendingSecretMutation()
        } else {
            vault.clearSafely()
        }
    }

    private suspend fun currentConfigurationSnapshot(): ModelConfigurationSnapshot =
        mutationLock.withLock {
            // A DataStore emission can wait behind an in-flight mutation. Re-read after taking the
            // mutation lock so a stale staging journal cannot compensate a successfully committed
            // replacement and erase its newly written credential.
            var values = readPreferences() ?: return@withLock ModelConfigurationSnapshot()
            val pending = values.pendingSecretMutation()
            if (pending != null) {
                if (!recoverPendingSecretMutation(values)) {
                    return@withLock pending.failClosedSnapshot(values)
                }
                values = readPreferences() ?: return@withLock ModelConfigurationSnapshot()
            }

            val stored = readStoredCredential(values)
            if (stored == null) {
                vault.clearSafely()
                return@withLock ModelConfigurationSnapshot()
            }
            if (forceUnconfigured.value) {
                return@withLock stored.configuration.copy(isConfigured = false)
            }
            if (!stored.configuration.isConfigured) {
                vault.clearSafely()
                return@withLock stored.configuration.copy(isConfigured = false)
            }

            if (!vault.ciphertextMatchesSafely(stored.binding)) {
                return@withLock stored.configuration.copy(isConfigured = false)
            }
            stored.configuration
        }

    private suspend fun recoverPendingSecretMutation(): Boolean =
        readPreferences()?.let { recoverPendingSecretMutation(it) } ?: false

    private suspend fun readPreferences(): Preferences? =
        when (val read = preferenceReads.first()) {
            is PreferenceRead.Available -> read.values
            PreferenceRead.Unavailable -> null
        }

    private suspend fun recoverPendingSecretMutation(
        values: Preferences,
    ): Boolean {
        val pending = values.pendingSecretMutation() ?: return true
        if (!vault.clearSafely()) return false

        val metadataRecovered = storageSucceeded {
            dataStore.edit { current ->
                when (pending) {
                    PendingSecretMutation.CLEAR -> current.clear()
                    PendingSecretMutation.REPLACE -> current.remove(PENDING_SECRET_MUTATION)
                }
            }
        }
        if (metadataRecovered) forceUnconfigured.value = false
        return metadataRecovered
    }

    private fun Preferences.pendingSecretMutation(): PendingSecretMutation? =
        this[PENDING_SECRET_MUTATION]?.let(PendingSecretMutation::fromStoredValue)

    private fun PendingSecretMutation.failClosedSnapshot(
        values: Preferences,
    ): ModelConfigurationSnapshot = when (this) {
        PendingSecretMutation.CLEAR -> ModelConfigurationSnapshot()
        PendingSecretMutation.REPLACE -> readStoredCredential(values)
            ?.configuration
            ?.copy(isConfigured = false)
            ?: ModelConfigurationSnapshot()
    }

    private fun readStoredCredential(values: Preferences): StoredCredential? {
        val validation = ModelConfigurationValidator.validate(
            update = ModelConfigurationUpdate(
                provider = values[PROVIDER].orEmpty(),
                baseUrl = values[BASE_URL].orEmpty(),
                modelId = values[MODEL_ID].orEmpty(),
            ),
            apiKey = charArrayOf('x'),
        )
        if (validation !is ModelConfigurationValidationResult.Valid) return null

        val generationId = values[CREDENTIAL_GENERATION_ID]
            ?.takeIf(::isValidGenerationId)
            ?: return null
        val updatedAt = (values[UPDATED_AT] ?: 0L).coerceAtLeast(0L)
        val configuration = ModelConfigurationSnapshot(
            provider = validation.configuration.provider,
            baseUrl = validation.configuration.baseUrl,
            modelId = validation.configuration.modelId,
            isConfigured = values[IS_CONFIGURED] == true && updatedAt > 0L,
            updatedAtEpochMillis = updatedAt,
            configurationVersion = generationId,
        )
        val verifiedConfiguration = configuration.copy(
            capabilityVerification = values.readCapabilityVerification()
                ?.takeIf { it.matches(configuration) },
        )
        return StoredCredential(
            configuration = verifiedConfiguration,
            binding = validation.configuration.toBinding(generationId),
        )
    }

    private fun Preferences.readCapabilityVerification(): ModelCapabilityVerification? {
        val provider = this[VERIFIED_PROVIDER] ?: return null
        val baseUrl = this[VERIFIED_BASE_URL] ?: return null
        val modelId = this[VERIFIED_MODEL_ID] ?: return null
        val version = this[VERIFIED_CONFIGURATION_VERSION] ?: return null
        val configurationUpdatedAt = this[VERIFIED_CONFIGURATION_UPDATED_AT] ?: return null
        val testedAt = this[VERIFIED_TESTED_AT] ?: return null
        val imageInput = this[VERIFIED_IMAGE_INPUT] ?: return null
        val structuredOutput = this[VERIFIED_STRUCTURED_OUTPUT] ?: return null
        // 老版本存的验证没有此键 → 默认 false（Route A 保持关，直到重新探测证明支持 tools）。
        val functionCalling = this[VERIFIED_FUNCTION_CALLING] ?: false
        val testStartSequence = this[VERIFIED_TEST_START_SEQUENCE] ?: 0L
        if (configurationUpdatedAt <= 0L || testedAt <= 0L) return null
        return ModelCapabilityVerification(
            provider = provider,
            baseUrl = baseUrl,
            modelId = modelId,
            configurationVersion = version,
            configurationUpdatedAtEpochMillis = configurationUpdatedAt,
            supportsImageInput = imageInput,
            supportsStructuredOutput = structuredOutput,
            supportsFunctionCalling = functionCalling,
            testedAtEpochMillis = testedAt,
            testStartSequence = testStartSequence,
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeCapabilityVerification(
        verification: ModelCapabilityVerification,
    ) {
        this[VERIFIED_PROVIDER] = verification.provider
        this[VERIFIED_BASE_URL] = verification.baseUrl
        this[VERIFIED_MODEL_ID] = verification.modelId
        this[VERIFIED_CONFIGURATION_VERSION] = verification.configurationVersion
        this[VERIFIED_CONFIGURATION_UPDATED_AT] =
            verification.configurationUpdatedAtEpochMillis
        this[VERIFIED_IMAGE_INPUT] = verification.supportsImageInput
        this[VERIFIED_STRUCTURED_OUTPUT] = verification.supportsStructuredOutput
        this[VERIFIED_FUNCTION_CALLING] = verification.supportsFunctionCalling
        this[VERIFIED_TESTED_AT] = verification.testedAtEpochMillis
        this[VERIFIED_TEST_START_SEQUENCE] = verification.testStartSequence
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.removeCapabilityVerification() {
        remove(VERIFIED_PROVIDER)
        remove(VERIFIED_BASE_URL)
        remove(VERIFIED_MODEL_ID)
        remove(VERIFIED_CONFIGURATION_VERSION)
        remove(VERIFIED_CONFIGURATION_UPDATED_AT)
        remove(VERIFIED_IMAGE_INPUT)
        remove(VERIFIED_STRUCTURED_OUTPUT)
        remove(VERIFIED_FUNCTION_CALLING)
        remove(VERIFIED_TESTED_AT)
        remove(VERIFIED_TEST_START_SEQUENCE)
    }

    private fun ModelConfigurationSnapshot.isSameGenerationAs(
        other: ModelConfigurationSnapshot,
    ): Boolean = isConfigured && other.isConfigured &&
        provider == other.provider &&
        baseUrl == other.baseUrl &&
        modelId == other.modelId &&
        configurationVersion == other.configurationVersion &&
        updatedAtEpochMillis == other.updatedAtEpochMillis

    private fun nextGeneration(previous: String?): String? {
        repeat(MAX_GENERATION_ATTEMPTS) {
            val candidate = try {
                generationSource()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                return null
            }
            if (candidate != previous && isValidGenerationId(candidate)) return candidate
        }
        return null
    }

    private fun isValidGenerationId(value: String): Boolean =
        value.length in MIN_GENERATION_ID_LENGTH..MAX_GENERATION_ID_LENGTH &&
            value.all {
                it in 'a'..'z' ||
                    it in 'A'..'Z' ||
                    it in '0'..'9' ||
                    it == '-' ||
                    it == '_' ||
                    it == '.' ||
                    it == ':'
            }

    private fun ValidatedModelConfiguration.toBinding(generationId: String) = ModelSecretBinding(
        generationId = generationId,
        provider = provider,
        baseUrl = baseUrl,
        modelId = modelId,
    )

    private fun ModelApiKey.copyOrNull(): CharArray? = try {
        copyChars()
    } catch (_: IllegalStateException) {
        null
    }

    private fun ModelApiKey.isValidFor(configuration: ModelConfigurationSnapshot): Boolean {
        val chars = copyOrNull() ?: return false
        return try {
            ModelConfigurationValidator.validate(
                update = ModelConfigurationUpdate(
                    provider = configuration.provider,
                    baseUrl = configuration.baseUrl,
                    modelId = configuration.modelId,
                ),
                apiKey = chars,
            ) is ModelConfigurationValidationResult.Valid
        } finally {
            Arrays.fill(chars, '\u0000')
        }
    }

    private suspend fun ModelSecretVault.readSafely(
        binding: ModelSecretBinding,
    ): SecretVaultReadResult = try {
        read(binding)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        SecretVaultReadResult.Unavailable
    }

    private suspend fun ModelSecretVault.ciphertextMatchesSafely(
        binding: ModelSecretBinding,
    ): Boolean = try {
        ciphertextMatches(binding)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        false
    }

    private suspend fun ModelSecretVault.clearSafely(): Boolean = storageSucceeded { clear() }

    private suspend fun storageSucceeded(operation: suspend () -> Unit): Boolean = try {
        operation()
        true
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        false
    }

    private fun rejected(issue: ModelConfigurationIssue) =
        ModelConfigurationMutationResult.Rejected(setOf(issue))

    private data class StoredCredential(
        val configuration: ModelConfigurationSnapshot,
        val binding: ModelSecretBinding,
    )

    private sealed interface PreferenceRead {
        data class Available(val values: Preferences) : PreferenceRead

        data object Unavailable : PreferenceRead
    }

    private enum class PendingSecretMutation(val storedValue: String) {
        REPLACE("replace"),
        CLEAR("clear");

        companion object {
            fun fromStoredValue(value: String): PendingSecretMutation =
                entries.firstOrNull { it.storedValue == value } ?: CLEAR
        }
    }

    private companion object {
        const val INITIAL_READ_RETRY_DELAY_MILLIS = 50L
        const val MAX_READ_RETRY_DELAY_MILLIS = 1_000L

        fun readRetryDelayMillis(attempt: Long): Long {
            val shift = attempt.coerceAtMost(4L).toInt()
            return (INITIAL_READ_RETRY_DELAY_MILLIS shl shift)
                .coerceAtMost(MAX_READ_RETRY_DELAY_MILLIS)
        }

        const val MIN_GENERATION_ID_LENGTH = 8
        const val MAX_GENERATION_ID_LENGTH = 128
        const val MAX_GENERATION_ATTEMPTS = 4
        val PROVIDER = stringPreferencesKey("provider")
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL_ID = stringPreferencesKey("model_id")
        val IS_CONFIGURED = booleanPreferencesKey("is_configured")
        val UPDATED_AT = longPreferencesKey("updated_at_epoch_millis")
        val CREDENTIAL_GENERATION_ID = stringPreferencesKey("credential_generation_id")
        val PENDING_SECRET_MUTATION = stringPreferencesKey("pending_secret_mutation")
        val VERIFIED_PROVIDER = stringPreferencesKey("verified_provider")
        val VERIFIED_BASE_URL = stringPreferencesKey("verified_base_url")
        val VERIFIED_MODEL_ID = stringPreferencesKey("verified_model_id")
        val VERIFIED_CONFIGURATION_VERSION = stringPreferencesKey("verified_configuration_version")
        val VERIFIED_CONFIGURATION_UPDATED_AT =
            longPreferencesKey("verified_configuration_updated_at_epoch_millis")
        val VERIFIED_IMAGE_INPUT = booleanPreferencesKey("verified_image_input")
        val VERIFIED_STRUCTURED_OUTPUT = booleanPreferencesKey("verified_structured_output")
        val VERIFIED_FUNCTION_CALLING = booleanPreferencesKey("verified_function_calling")
        val VERIFIED_TESTED_AT = longPreferencesKey("verified_tested_at_epoch_millis")
        val VERIFIED_TEST_START_SEQUENCE =
            longPreferencesKey("verified_test_start_sequence")
        val LATEST_CAPABILITY_TEST_SEQUENCE =
            longPreferencesKey("latest_capability_test_sequence")
    }
}
