package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.model.TutorToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MasteryUpdateEvidenceIdTest {

    @Test
    fun `same namespace tool and kc derive the same id`() {
        val first = masteryUpdateEvidenceId("request-1", TutorToolName.MASTERY_UPDATE, "node-algebra")
        val second = masteryUpdateEvidenceId("request-1", TutorToolName.MASTERY_UPDATE, "node-algebra")
        assertEquals(first, second)
    }

    @Test
    fun `different kc or namespace derives a different id`() {
        val base = masteryUpdateEvidenceId("request-1", TutorToolName.MASTERY_UPDATE, "node-algebra")
        assertNotEquals(base, masteryUpdateEvidenceId("request-1", TutorToolName.MASTERY_UPDATE, "node-geometry"))
        assertNotEquals(base, masteryUpdateEvidenceId("request-2", TutorToolName.MASTERY_UPDATE, "node-algebra"))
    }

    @Test
    fun `null namespace falls back to a unique non idempotent id`() {
        val a = masteryUpdateEvidenceId(null, TutorToolName.MASTERY_UPDATE, "node-algebra")
        val b = masteryUpdateEvidenceId(null, TutorToolName.MASTERY_UPDATE, "node-algebra")
        // nanoTime fallback must still be non-blank and prefixed.
        assertTrue(a.startsWith("chat-ev-"))
        assertTrue(b.startsWith("chat-ev-"))
        assertNotEquals(a, b)
    }

    @Test
    fun `derived id stays within the chat evidence id budget`() {
        val id = masteryUpdateEvidenceId("request-1", TutorToolName.MASTERY_UPDATE, "node-algebra")
        // evidence_id is the Room PK of learner_chat_evidence — keep it short enough
        // for the TEXT PK and any outbox id composition.
        assertTrue("derived id length ${id.length}", id.length <= 120)
    }
}
