package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorMemoryPreference
import com.tingyun.smartmistakebook.core.model.TutorMessageIntent
import com.tingyun.smartmistakebook.core.model.TutorRequestedLocalCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorIntentAuthorityTest {
    @Test
    fun ambiguousMessageCannotReadOrWriteAnything() {
        val authorization = TutorIntentAuthorityPolicy.authorize(
            decision(
                intent = TutorMessageIntent.AMBIGUOUS,
                confidence = 0.55,
            ),
            studentMessage = "这个呢",
        )

        assertTrue(authorization.requiresClarification)
        assertTrue(authorization.capabilities.isEmpty())
    }

    @Test
    fun mistakeLookupGetsOnlyABoundedRead() {
        val authorization = TutorIntentAuthorityPolicy.authorize(
            decision(
                intent = TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP,
                capability = TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
                confidence = 0.82,
                explicit = true,
            ),
            studentMessage = "帮我查一下错题本里的函数题",
        )

        assertFalse(authorization.requiresClarification)
        assertEquals(
            setOf(TutorAuthorizedCapability.READ_MISTAKE_NOTEBOOK),
            authorization.capabilities,
        )
        assertEquals(TutorIntentAuthorization.MAX_READ_ITEMS, authorization.mistakeReadLimit)
    }

    @Test
    fun modelCanOnlyOfferAWriteConfirmation() {
        val authorization = TutorIntentAuthorityPolicy.authorize(
            decision(
                intent = TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP,
                capability = TutorRequestedLocalCapability.OFFER_SAVE_CURRENT_QUESTION,
                confidence = 0.96,
                explicit = true,
            ),
            studentMessage = "把这道题加入错题本",
        )

        assertEquals(
            setOf(TutorAuthorizedCapability.REQUEST_SAVE_CONFIRMATION),
            authorization.capabilities,
        )
    }

    @Test
    fun weakWriteRequestExecutesNothing() {
        val authorization = TutorIntentAuthorityPolicy.authorize(
            decision(
                intent = TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP,
                capability = TutorRequestedLocalCapability.OFFER_SAVE_CURRENT_QUESTION,
                confidence = 0.74,
                explicit = true,
            ),
            studentMessage = "把这道题加入错题本",
        )

        assertTrue(authorization.capabilities.isEmpty())
    }

    @Test
    fun explicitDoNotRememberCanOnlyNarrowTheSession() {
        val authorization = TutorIntentAuthorityPolicy.authorize(
            decision(
                intent = TutorMessageIntent.CASUAL_CONVERSATION,
                confidence = 0.91,
                explicit = true,
                memory = TutorMemoryPreference.BLOCK_LONG_TERM_WRITES_FOR_SESSION,
            ),
            studentMessage = "这次别记",
        )

        assertEquals(
            setOf(TutorAuthorizedCapability.BLOCK_LONG_TERM_WRITES_FOR_SESSION),
            authorization.capabilities,
        )
    }

    @Test
    fun sayingIDoNotRememberDoesNotDisableLearningMemory() {
        val authorization = TutorIntentAuthorityPolicy.authorize(
            decision(
                intent = TutorMessageIntent.CURRENT_QUESTION_HELP,
                confidence = 0.97,
                explicit = true,
                memory = TutorMemoryPreference.BLOCK_LONG_TERM_WRITES_FOR_SESSION,
            ),
            studentMessage = "我不记得这一步为什么要换元",
        )

        assertTrue(authorization.requiresClarification)
        assertTrue(authorization.capabilities.isEmpty())
    }

    @Test
    fun modelCannotInventAReadThatTheStudentMessageDidNotRequest() {
        val authorization = TutorIntentAuthorityPolicy.authorize(
            decision(
                intent = TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP,
                capability = TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
                confidence = 0.99,
                explicit = true,
            ),
            studentMessage = "这一步为什么先求导？",
        )

        assertTrue(authorization.requiresClarification)
        assertTrue(authorization.capabilities.isEmpty())
    }

    @Test
    fun inventedLookupTermsInvalidateAnOtherwisePlausibleRead() {
        val authorization = TutorIntentAuthorityPolicy.authorize(
            decision(
                intent = TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP,
                capability = TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
                confidence = 0.99,
                explicit = true,
                lookupTerms = listOf("圆锥曲线"),
            ),
            studentMessage = "帮我查一下错题本里的函数题",
        )

        assertTrue(authorization.requiresClarification)
        assertTrue(authorization.capabilities.isEmpty())
    }

    private fun decision(
        intent: TutorMessageIntent,
        capability: TutorRequestedLocalCapability = TutorRequestedLocalCapability.NONE,
        confidence: Double,
        explicit: Boolean = false,
        memory: TutorMemoryPreference = TutorMemoryPreference.UNCHANGED,
        lookupTerms: List<String> = emptyList(),
    ) = TutorIntentDecision(
        intent = intent,
        confidence = confidence,
        explicitActionRequest = explicit,
        memoryPreference = memory,
        requestedLocalCapability = capability,
        lookupTerms = lookupTerms,
    )
}
