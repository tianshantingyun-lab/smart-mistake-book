package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CapturedTutorProblemIdentityTest {
    @Test
    fun `captured draft identity is stable across modules`() {
        assertEquals("captured-question-v1", CapturedTutorProblemIdentity.fingerprintVersion)
        assertEquals(
            "7c250c778309d3ac66d9dcbb4a915ecee0513992ae6cdec9c87acdec3610a400",
            CapturedTutorProblemIdentity.questionFingerprint("draft-123"),
        )
    }

    @Test
    fun `captured draft identity rejects ambiguous ids`() {
        listOf("", " draft-123", "draft-123\n").forEach { draftId ->
            try {
                CapturedTutorProblemIdentity.questionFingerprint(draftId)
                fail("Expected invalid captured draft id")
            } catch (_: IllegalArgumentException) {
                Unit
            }
        }
    }
}
