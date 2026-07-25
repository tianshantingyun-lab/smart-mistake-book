package com.tingyun.smartmistakebook.core.data.settings

import com.tingyun.smartmistakebook.core.domain.ModelConfigurationIssue
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigurationValidatorTest {
    @Test
    fun `missing scheme defaults to https and trailing slash is normalized`() {
        val valid = validate("api.example.com/v1/") as ModelConfigurationValidationResult.Valid

        assertEquals("https://api.example.com/v1", valid.configuration.baseUrl)
    }

    @Test
    fun `https accepts an explicit port and path`() {
        val valid = validate("https://models.example.com:8443/openai/v1")
            as ModelConfigurationValidationResult.Valid

        assertEquals(
            "https://models.example.com:8443/openai/v1",
            valid.configuration.baseUrl,
        )
    }

    @Test
    fun `decoded path is rebuilt once without double encoding percent escapes`() {
        val valid = validate("https://models.example.com/openai%20compatible/v1")
            as ModelConfigurationValidationResult.Valid

        assertEquals(
            "https://models.example.com/openai%20compatible/v1",
            valid.configuration.baseUrl,
        )
    }

    @Test
    fun `all http is rejected to match the Android cleartext policy`() {
        val urls = listOf(
            "http://api.example.com/v1",
            "http://8.8.8.8/v1",
            "http://localhost:11434/v1",
            "http://127.42.0.1:11434/v1",
            "http://[::1]:11434/v1",
            "http://10.0.2.2:11434/v1",
            "http://10.0.3.2:11434/v1",
            "http://192.168.1.20:11434/v1",
        )

        urls.forEach { url ->
            assertIssue(validate(url), ModelConfigurationIssue.INSECURE_HTTP_NOT_ALLOWED)
        }
    }

    @Test
    fun `credentials query fragment and path traversal are rejected`() {
        assertIssue(
            validate("https://user:password@example.com/v1"),
            ModelConfigurationIssue.BASE_URL_CREDENTIALS_NOT_ALLOWED,
        )
        assertIssue(
            validate("https://example.com/v1?token=secret"),
            ModelConfigurationIssue.BASE_URL_QUERY_OR_FRAGMENT_NOT_ALLOWED,
        )
        assertIssue(
            validate("https://example.com/v1#secret"),
            ModelConfigurationIssue.BASE_URL_QUERY_OR_FRAGMENT_NOT_ALLOWED,
        )
        assertIssue(
            validate("https://example.com/%2e%2e/admin"),
            ModelConfigurationIssue.BASE_URL_PATH_NOT_ALLOWED,
        )
        assertIssue(
            validate("https://example.com/v1%2Fchat"),
            ModelConfigurationIssue.BASE_URL_PATH_NOT_ALLOWED,
        )
        assertIssue(
            validate("https://example.com/v1%0Achat"),
            ModelConfigurationIssue.BASE_URL_PATH_NOT_ALLOWED,
        )
        assertIssue(
            validate("https://example.com/v1%E2%80%AEchat"),
            ModelConfigurationIssue.BASE_URL_PATH_NOT_ALLOWED,
        )
    }

    @Test
    fun `unsupported scheme invalid port and controls are rejected`() {
        assertIssue(
            validate("ftp://example.com/v1"),
            ModelConfigurationIssue.BASE_URL_INVALID,
        )
        assertIssue(
            validate("https://example.com:65536/v1"),
            ModelConfigurationIssue.BASE_URL_INVALID,
        )
        assertIssue(
            validate("https://example.com:0/v1"),
            ModelConfigurationIssue.BASE_URL_INVALID,
        )
        assertIssue(
            validate("https://example.com/v1\nnext"),
            ModelConfigurationIssue.BASE_URL_CONTAINS_CONTROL_CHARACTER,
        )
        assertIssue(
            validate("https://example.com/v1\u202enext"),
            ModelConfigurationIssue.BASE_URL_CONTAINS_CONTROL_CHARACTER,
        )
    }

    @Test
    fun `empty long and whitespace secret values are rejected`() {
        assertIssue(
            validate(baseUrl = "https://example.com", apiKey = charArrayOf()),
            ModelConfigurationIssue.API_KEY_REQUIRED,
        )
        assertIssue(
            validate(baseUrl = "https://example.com", apiKey = "key with space".toCharArray()),
            ModelConfigurationIssue.API_KEY_CONTAINS_WHITESPACE_OR_CONTROL_CHARACTER,
        )
        assertIssue(
            validate(
                baseUrl = "https://example.com",
                apiKey = CharArray(4_097) { 'x' },
            ),
            ModelConfigurationIssue.API_KEY_TOO_LONG,
        )
    }

    @Test
    fun `provider and model metadata enforce required length and control boundaries`() {
        assertIssue(
            validate("https://example.com", provider = "  "),
            ModelConfigurationIssue.PROVIDER_REQUIRED,
        )
        assertIssue(
            validate("https://example.com", provider = "safe\u0000unsafe"),
            ModelConfigurationIssue.PROVIDER_CONTAINS_CONTROL_CHARACTER,
        )
        assertIssue(
            validate("https://example.com", provider = "safe\u202eunsafe"),
            ModelConfigurationIssue.PROVIDER_CONTAINS_CONTROL_CHARACTER,
        )
        assertIssue(
            validate("https://example.com", modelId = "m\nodel"),
            ModelConfigurationIssue.MODEL_ID_CONTAINS_CONTROL_CHARACTER,
        )
        assertIssue(
            validate("https://example.com", modelId = "m\u2067odel"),
            ModelConfigurationIssue.MODEL_ID_CONTAINS_CONTROL_CHARACTER,
        )
        assertIssue(
            validate("https://example.com", modelId = "x".repeat(257)),
            ModelConfigurationIssue.MODEL_ID_TOO_LONG,
        )
    }

    private fun validate(
        baseUrl: String,
        provider: String = "openai-compatible",
        modelId: String = "model-1",
        apiKey: CharArray = "test-api-key".toCharArray(),
    ): ModelConfigurationValidationResult = ModelConfigurationValidator.validate(
        update = ModelConfigurationUpdate(
            provider = provider,
            baseUrl = baseUrl,
            modelId = modelId,
        ),
        apiKey = apiKey,
    )

    private fun assertIssue(
        result: ModelConfigurationValidationResult,
        expected: ModelConfigurationIssue,
    ) {
        val invalid = result as ModelConfigurationValidationResult.Invalid
        assertTrue("Expected $expected in ${invalid.issues}", expected in invalid.issues)
    }
}
