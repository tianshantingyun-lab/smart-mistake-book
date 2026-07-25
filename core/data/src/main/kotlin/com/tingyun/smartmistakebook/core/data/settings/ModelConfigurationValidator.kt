package com.tingyun.smartmistakebook.core.data.settings

import com.tingyun.smartmistakebook.core.domain.ModelConfigurationIssue
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationUpdate
import java.net.URI
import java.net.URISyntaxException

internal data class ValidatedModelConfiguration(
    val provider: String,
    val baseUrl: String,
    val modelId: String,
)

internal sealed interface ModelConfigurationValidationResult {
    data class Valid(val configuration: ValidatedModelConfiguration) :
        ModelConfigurationValidationResult

    data class Invalid(val issues: Set<ModelConfigurationIssue>) :
        ModelConfigurationValidationResult
}

internal object ModelConfigurationValidator {
    private const val MAX_PROVIDER_LENGTH = 128
    private const val MAX_BASE_URL_LENGTH = 2_048
    private const val MAX_MODEL_ID_LENGTH = 256
    private const val MAX_API_KEY_LENGTH = 4_096

    fun validate(
        update: ModelConfigurationUpdate,
        apiKey: CharArray,
    ): ModelConfigurationValidationResult {
        val issues = linkedSetOf<ModelConfigurationIssue>()
        val provider = update.provider.takeIf { it.length <= MAX_PROVIDER_LENGTH }?.trim().orEmpty()
        val modelId = update.modelId.takeIf { it.length <= MAX_MODEL_ID_LENGTH }?.trim().orEmpty()

        validateLabel(
            original = update.provider,
            normalized = provider,
            requiredIssue = ModelConfigurationIssue.PROVIDER_REQUIRED,
            tooLongIssue = ModelConfigurationIssue.PROVIDER_TOO_LONG,
            controlIssue = ModelConfigurationIssue.PROVIDER_CONTAINS_CONTROL_CHARACTER,
            maxLength = MAX_PROVIDER_LENGTH,
            issues = issues,
        )
        validateLabel(
            original = update.modelId,
            normalized = modelId,
            requiredIssue = ModelConfigurationIssue.MODEL_ID_REQUIRED,
            tooLongIssue = ModelConfigurationIssue.MODEL_ID_TOO_LONG,
            controlIssue = ModelConfigurationIssue.MODEL_ID_CONTAINS_CONTROL_CHARACTER,
            maxLength = MAX_MODEL_ID_LENGTH,
            issues = issues,
        )
        validateApiKey(apiKey, issues)

        val normalizedBaseUrl = validateAndNormalizeBaseUrl(
            rawValue = update.baseUrl,
            issues = issues,
        )

        return if (issues.isEmpty() && normalizedBaseUrl != null) {
            ModelConfigurationValidationResult.Valid(
                ValidatedModelConfiguration(
                    provider = provider,
                    baseUrl = normalizedBaseUrl,
                    modelId = modelId,
                ),
            )
        } else {
            ModelConfigurationValidationResult.Invalid(issues)
        }
    }

    private fun validateLabel(
        original: String,
        normalized: String,
        requiredIssue: ModelConfigurationIssue,
        tooLongIssue: ModelConfigurationIssue,
        controlIssue: ModelConfigurationIssue,
        maxLength: Int,
        issues: MutableSet<ModelConfigurationIssue>,
    ) {
        if (original.length > maxLength) {
            issues += tooLongIssue
            return
        }
        if (normalized.isEmpty()) issues += requiredIssue
        if (original.any(::isUnsafeMetadataCharacter)) issues += controlIssue
    }

    private fun validateApiKey(
        apiKey: CharArray,
        issues: MutableSet<ModelConfigurationIssue>,
    ) {
        if (apiKey.size > MAX_API_KEY_LENGTH) {
            issues += ModelConfigurationIssue.API_KEY_TOO_LONG
            return
        }
        if (apiKey.isEmpty()) issues += ModelConfigurationIssue.API_KEY_REQUIRED
        if (apiKey.any { it.isWhitespace() || isUnsafeMetadataCharacter(it) }) {
            issues += ModelConfigurationIssue.API_KEY_CONTAINS_WHITESPACE_OR_CONTROL_CHARACTER
        }
    }

    private fun validateAndNormalizeBaseUrl(
        rawValue: String,
        issues: MutableSet<ModelConfigurationIssue>,
    ): String? {
        if (rawValue.length > MAX_BASE_URL_LENGTH) {
            issues += ModelConfigurationIssue.BASE_URL_TOO_LONG
            return null
        }
        val value = rawValue.trim()
        if (value.isEmpty()) {
            issues += ModelConfigurationIssue.BASE_URL_REQUIRED
            return null
        }
        if (rawValue.any(::isUnsafeMetadataCharacter)) {
            issues += ModelConfigurationIssue.BASE_URL_CONTAINS_CONTROL_CHARACTER
            return null
        }

        val candidate = if (SCHEME_PREFIX.matches(value)) value else "https://$value"
        val uri = try {
            URI(candidate)
        } catch (_: URISyntaxException) {
            issues += ModelConfigurationIssue.BASE_URL_INVALID
            return null
        }

        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.removeSurrounding("[", "]")?.lowercase()
        if (scheme == "http") {
            issues += ModelConfigurationIssue.INSECURE_HTTP_NOT_ALLOWED
            return null
        }
        if (scheme != "https" || host.isNullOrBlank() || uri.isOpaque || !uri.isAbsolute) {
            issues += ModelConfigurationIssue.BASE_URL_INVALID
            return null
        }
        // Destination/redirect SSRF policy belongs to the eventual network client, where resolved
        // addresses and every redirect hop are visible. Storage accepts any syntactically safe
        // user-selected HTTPS host and must not pretend to enforce a network policy it cannot see.
        if (uri.port != -1 && uri.port !in 1..65_535) {
            issues += ModelConfigurationIssue.BASE_URL_INVALID
        }
        if (uri.userInfo != null) {
            issues += ModelConfigurationIssue.BASE_URL_CREDENTIALS_NOT_ALLOWED
        }
        if (uri.rawQuery != null || uri.rawFragment != null) {
            issues += ModelConfigurationIssue.BASE_URL_QUERY_OR_FRAGMENT_NOT_ALLOWED
        }
        if (!isSafeBasePath(uri)) {
            issues += ModelConfigurationIssue.BASE_URL_PATH_NOT_ALLOWED
        }
        if (issues.any { it.isBaseUrlIssue() }) return null

        val normalizedPath = uri.path.orEmpty().trimEnd('/').ifEmpty { null }
        return try {
            URI(scheme, null, host, uri.port, normalizedPath, null, null).toASCIIString()
        } catch (_: URISyntaxException) {
            issues += ModelConfigurationIssue.BASE_URL_INVALID
            null
        }
    }

    private fun isSafeBasePath(uri: URI): Boolean {
        val rawPath = uri.rawPath.orEmpty()
        val decodedPath = uri.path.orEmpty()
        if ('\\' in rawPath || '\\' in decodedPath) return false
        if (ENCODED_SEPARATOR.containsMatchIn(rawPath)) return false
        if (ENCODED_CONTROL.containsMatchIn(rawPath)) return false
        if (decodedPath.any(::isUnsafeMetadataCharacter)) return false
        return decodedPath.split('/').none { it == "." || it == ".." }
    }

    private fun isUnsafeMetadataCharacter(char: Char): Boolean =
        char.code == 0x7f ||
            Character.isISOControl(char) ||
            Character.isSurrogate(char) ||
            Character.getType(char) == Character.FORMAT.toInt() ||
            Character.getType(char) == Character.LINE_SEPARATOR.toInt() ||
            Character.getType(char) == Character.PARAGRAPH_SEPARATOR.toInt() ||
            char.isBidiControl()

    private fun Char.isBidiControl(): Boolean =
        this == '\u061c' ||
            this == '\u200e' ||
            this == '\u200f' ||
            this in '\u202a'..'\u202e' ||
            this in '\u2066'..'\u2069'

    private fun ModelConfigurationIssue.isBaseUrlIssue(): Boolean = when (this) {
        ModelConfigurationIssue.BASE_URL_REQUIRED,
        ModelConfigurationIssue.BASE_URL_TOO_LONG,
        ModelConfigurationIssue.BASE_URL_INVALID,
        ModelConfigurationIssue.BASE_URL_CONTAINS_CONTROL_CHARACTER,
        ModelConfigurationIssue.BASE_URL_CREDENTIALS_NOT_ALLOWED,
        ModelConfigurationIssue.BASE_URL_QUERY_OR_FRAGMENT_NOT_ALLOWED,
        ModelConfigurationIssue.BASE_URL_PATH_NOT_ALLOWED,
        ModelConfigurationIssue.INSECURE_HTTP_NOT_ALLOWED,
        -> true

        else -> false
    }

    private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://.*$")
    private val ENCODED_SEPARATOR = Regex("%(?:2f|5c)", RegexOption.IGNORE_CASE)
    private val ENCODED_CONTROL = Regex("%(?:0[0-9a-f]|1[0-9a-f]|7f)", RegexOption.IGNORE_CASE)
}
