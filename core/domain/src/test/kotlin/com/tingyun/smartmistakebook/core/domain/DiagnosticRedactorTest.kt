package com.tingyun.smartmistakebook.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactorTest {
    @Test
    fun opaqueIdentifiersAlwaysRenderAsRedacted() {
        listOf(
            null,
            "learner-local",
            "source-fact-123",
            "candidate-456",
            "problem-789",
        ).forEach { value ->
            assertEquals(DiagnosticRedactor.REDACTED, DiagnosticRedactor.redactId(value))
        }
    }

    @Test
    fun redactMessageReplacesEverySensitiveValueEvenWhenEmbedded() {
        val learnerId = "learner-abc-123"
        val requestId = "request-def-456"
        val message =
            "failed learner=$learnerId request=$requestId retry=${learnerId}:$requestId"

        val redacted = DiagnosticRedactor.redactMessage(message, learnerId, requestId)

        assertFalse(redacted.contains(learnerId))
        assertFalse(redacted.contains(requestId))
        assertTrue(redacted.contains(DiagnosticRedactor.REDACTED))
        assertEquals(
            "failed learner=${DiagnosticRedactor.REDACTED} " +
                "request=${DiagnosticRedactor.REDACTED} " +
                "retry=${DiagnosticRedactor.REDACTED}:${DiagnosticRedactor.REDACTED}",
            redacted,
        )
    }
}
