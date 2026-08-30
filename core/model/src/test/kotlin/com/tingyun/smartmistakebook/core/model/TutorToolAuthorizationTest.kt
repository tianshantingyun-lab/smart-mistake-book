package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorToolAuthorizationTest {

    private fun decision(
        intent: TutorMessageIntent,
        confidence: Double,
    ) = TutorIntentDecision(
        intent = intent,
        confidence = confidence,
        explicitActionRequest = false,
        memoryPreference = TutorMemoryPreference.UNCHANGED,
        requestedLocalCapability = TutorRequestedLocalCapability.NONE,
    )

    private val allDeclared = setOf(
        TutorToolName.KNOWLEDGE_READ,
        TutorToolName.NOTEBOOK_READ,
        TutorToolName.MASTERY_READ,
    )

    @Test
    fun `current question help unlocks all three tools above the route threshold`() {
        val authorization = tutorToolAuthorization(decision(TutorMessageIntent.CURRENT_QUESTION_HELP, 0.9), allDeclared)
        assertEquals(allDeclared, authorization.allowedTools)
        assertTrue(authorization.routeEligible)
    }

    @Test
    fun `notebook lookup unlocks only the notebook read`() {
        val authorization = tutorToolAuthorization(
            decision(TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP, 0.8),
            allDeclared,
        )
        assertEquals(setOf(TutorToolName.NOTEBOOK_READ), authorization.allowedTools)
    }

    @Test
    fun `casual conversation never unlocks tools regardless of confidence`() {
        val authorization = tutorToolAuthorization(decision(TutorMessageIntent.CASUAL_CONVERSATION, 1.0), allDeclared)
        assertTrue(authorization.allowedTools.isEmpty())
        assertTrue(authorization.routeEligible)
    }

    @Test
    fun `confidence below the route threshold is untrusted and unlocks nothing`() {
        val authorization = tutorToolAuthorization(decision(TutorMessageIntent.CURRENT_QUESTION_HELP, 0.44), allDeclared)
        assertTrue(authorization.allowedTools.isEmpty())
        assertTrue(!authorization.routeEligible)
    }

    @Test
    fun `tools outside the declared set are intersected away`() {
        val authorization = tutorToolAuthorization(
            decision(TutorMessageIntent.CURRENT_QUESTION_HELP, 0.9),
            setOf(TutorToolName.NOTEBOOK_READ),
        )
        assertEquals(setOf(TutorToolName.NOTEBOOK_READ), authorization.allowedTools)
    }

    @Test
    fun `tool calls require anchored terms for read tools that need them`() {
        val withTerms = TutorToolCall(
            tool = TutorToolName.KNOWLEDGE_READ,
            rationale = "学生问二次函数图像，查知识库",
            terms = listOf("二次函数"),
        )
        assertEquals(listOf("二次函数"), withTerms.terms)

        val exception = runCatching {
            TutorToolCall(tool = TutorToolName.KNOWLEDGE_READ, rationale = "查一下")
        }.exceptionOrNull()
        assertTrue(exception is IllegalArgumentException)
    }

    @Test
    fun `mastery read needs no terms`() {
        val call = TutorToolCall(tool = TutorToolName.MASTERY_READ, rationale = "看整体掌握分布")
        assertTrue(call.terms.isEmpty())
    }
}
