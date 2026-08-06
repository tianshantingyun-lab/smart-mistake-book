package com.tingyun.smartmistakebook.core.domain

import java.util.Arrays
import kotlinx.coroutines.flow.Flow

/** Non-secret model endpoint metadata that is safe to expose to presentation code. */
data class ModelConfigurationSnapshot(
    val provider: String = "",
    val baseUrl: String = "",
    val modelId: String = "",
    val isConfigured: Boolean = false,
    val updatedAtEpochMillis: Long = 0L,
    /** Opaque, non-secret credential generation used to invalidate stale capability tests. */
    val configurationVersion: String = "",
    val capabilityVerification: ModelCapabilityVerification? = null,
) {
    override fun toString(): String =
        "ModelConfigurationSnapshot(" +
            "provider=$provider, " +
            "baseUrl=[REDACTED], " +
            "modelId=[REDACTED], " +
            "isConfigured=$isConfigured, " +
            "updatedAtEpochMillis=$updatedAtEpochMillis, " +
            "configurationVersion=[REDACTED], " +
            "capabilityVerification=${capabilityVerification?.toSafeLogSummary()}" +
            ")"
}

/** Results from a user-triggered synthetic check against one exact saved configuration. */
data class ModelCapabilityVerification(
    val provider: String,
    val baseUrl: String,
    val modelId: String,
    val configurationVersion: String,
    val configurationUpdatedAtEpochMillis: Long,
    val supportsImageInput: Boolean,
    val supportsStructuredOutput: Boolean,
    val testedAtEpochMillis: Long,
    /** Persisted start order for concurrent tests of this exact configuration generation. */
    val testStartSequence: Long = 0L,
) {
    fun matches(configuration: ModelConfigurationSnapshot): Boolean =
        configuration.isConfigured &&
            provider == configuration.provider &&
            baseUrl == configuration.baseUrl &&
            modelId == configuration.modelId &&
            configurationVersion == configuration.configurationVersion &&
            configurationUpdatedAtEpochMillis == configuration.updatedAtEpochMillis

    internal fun toSafeLogSummary(): String =
        "ModelCapabilityVerification(" +
            "provider=$provider, " +
            "baseUrl=[REDACTED], " +
            "modelId=[REDACTED], " +
            "configurationVersion=[REDACTED], " +
            "configurationUpdatedAtEpochMillis=$configurationUpdatedAtEpochMillis, " +
            "supportsImageInput=$supportsImageInput, " +
            "supportsStructuredOutput=$supportsStructuredOutput, " +
            "testedAtEpochMillis=$testedAtEpochMillis, " +
            "testStartSequence=$testStartSequence" +
            ")"

    override fun toString(): String = toSafeLogSummary()
}

fun ModelConfigurationSnapshot.currentCapabilityVerification(): ModelCapabilityVerification? =
    capabilityVerification?.takeIf { it.matches(this) }

enum class ModelCapabilityVerificationWriteResult {
    SAVED,
    CONFIGURATION_CHANGED,
    STORAGE_UNAVAILABLE,
}

sealed interface ModelCapabilityTestStartResult {
    data class Started(val sequence: Long) : ModelCapabilityTestStartResult

    data object ConfigurationChanged : ModelCapabilityTestStartResult
    data object StorageUnavailable : ModelCapabilityTestStartResult
}

sealed interface ModelCapabilityTestResult {
    data class Completed(val verification: ModelCapabilityVerification) : ModelCapabilityTestResult

    data object NotConfigured : ModelCapabilityTestResult
    data object ConfigurationChanged : ModelCapabilityTestResult
    data object AuthenticationFailed : ModelCapabilityTestResult
    data object ConnectionFailed : ModelCapabilityTestResult
    data object ProviderUnavailable : ModelCapabilityTestResult
    data object InvalidServiceAddress : ModelCapabilityTestResult
    data object StorageUnavailable : ModelCapabilityTestResult
}

fun interface ModelCapabilityTester {
    suspend fun testSavedConfiguration(): ModelCapabilityTestResult
}

/**
 * A pending configuration change. M1 accepts HTTPS only because the Android network-security
 * policy disables cleartext traffic. A future development flavor must pair any local HTTP support
 * with an explicit static platform allowlist; this production contract cannot bypass that policy.
 */
data class ModelConfigurationUpdate(
    val provider: String,
    val baseUrl: String,
    val modelId: String,
)

/** A closeable secret container whose string representation is always redacted. */
class ModelApiKey private constructor(chars: CharArray) : AutoCloseable {
    private var value: CharArray? = chars.copyOf()

    @Synchronized
    fun copyChars(): CharArray = checkNotNull(value) { "Model API key is closed" }.copyOf()

    @Synchronized
    override fun close() {
        value?.let { Arrays.fill(it, '\u0000') }
        value = null
    }

    override fun toString(): String = "ModelApiKey([REDACTED])"

    companion object {
        /** Copies [chars]; the caller remains responsible for wiping its source array. */
        fun from(chars: CharArray): ModelApiKey = ModelApiKey(chars)
    }
}

enum class ModelConfigurationIssue {
    PROVIDER_REQUIRED,
    PROVIDER_TOO_LONG,
    PROVIDER_CONTAINS_CONTROL_CHARACTER,
    BASE_URL_REQUIRED,
    BASE_URL_TOO_LONG,
    BASE_URL_INVALID,
    BASE_URL_CONTAINS_CONTROL_CHARACTER,
    BASE_URL_CREDENTIALS_NOT_ALLOWED,
    BASE_URL_QUERY_OR_FRAGMENT_NOT_ALLOWED,
    BASE_URL_PATH_NOT_ALLOWED,
    INSECURE_HTTP_NOT_ALLOWED,
    MODEL_ID_REQUIRED,
    MODEL_ID_TOO_LONG,
    MODEL_ID_CONTAINS_CONTROL_CHARACTER,
    API_KEY_REQUIRED,
    API_KEY_TOO_LONG,
    API_KEY_CONTAINS_WHITESPACE_OR_CONTROL_CHARACTER,
}

sealed interface ModelConfigurationMutationResult {
    data class Success(val configuration: ModelConfigurationSnapshot) :
        ModelConfigurationMutationResult

    data class Rejected(val issues: Set<ModelConfigurationIssue>) :
        ModelConfigurationMutationResult

    data object MissingConfiguration : ModelConfigurationMutationResult

    /** Persistence or keystore access failed. No configured secret should be assumed. */
    data object StorageUnavailable : ModelConfigurationMutationResult
}

sealed interface ModelCredentialReadResult {
    /** Configuration and key captured atomically from one credential generation. */
    data class Available(
        val configuration: ModelConfigurationSnapshot,
        val apiKey: ModelApiKey,
    ) : ModelCredentialReadResult

    data object Missing : ModelCredentialReadResult

    /** Ciphertext or its keystore key could not be authenticated; the store fails closed. */
    data object Unavailable : ModelCredentialReadResult
}

/**
 * Least-privilege capability used by the external model gateway.
 *
 * It can observe non-secret metadata and lease the current credential for one request, but it
 * cannot save, rotate, verify, or clear configuration.
 */
interface ModelConfigurationReadCapability {
    /**
     * Presentation-only metadata and configured state. It never emits API-key material.
     *
     * A model client must not combine a snapshot collected here with a separately retained key;
     * call [readCredential] to lease an atomic configuration/key pair for each request instead.
     */
    val configuration: Flow<ModelConfigurationSnapshot>

    /** One-shot atomic credential lease; callers must close an available key. */
    suspend fun readCredential(): ModelCredentialReadResult
}

/**
 * Narrow capability used only by the explicit settings-screen compatibility test.
 *
 * The test may reserve and record its own result, but it cannot mutate endpoint or credential
 * configuration.
 */
interface ModelCapabilityTestConfigurationCapability : ModelConfigurationReadCapability {
    /** Atomically reserves the next test-start order for one exact configuration generation. */
    suspend fun beginCapabilityTest(
        configuration: ModelConfigurationSnapshot,
    ): ModelCapabilityTestStartResult = ModelCapabilityTestStartResult.StorageUnavailable

    /**
     * Records a synthetic test only while its exact configuration generation and latest reserved
     * test-start order are still current.
     */
    suspend fun recordCapabilityVerification(
        verification: ModelCapabilityVerification,
    ): ModelCapabilityVerificationWriteResult =
        ModelCapabilityVerificationWriteResult.STORAGE_UNAVAILABLE
}

/** Settings-owned mutable store; external provider code must depend on narrower capabilities. */
interface ModelConfigurationStore : ModelCapabilityTestConfigurationCapability {
    suspend fun save(
        update: ModelConfigurationUpdate,
        apiKey: ModelApiKey,
    ): ModelConfigurationMutationResult

    suspend fun rotateApiKey(apiKey: ModelApiKey): ModelConfigurationMutationResult

    suspend fun clear(): ModelConfigurationMutationResult
}
