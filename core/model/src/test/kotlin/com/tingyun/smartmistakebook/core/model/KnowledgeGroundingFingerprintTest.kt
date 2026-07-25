package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeGroundingFingerprintTest {
    @Test
    fun normalizesWhitespaceAndCaseWithoutMergingSubjects() {
        val first = KnowledgeGroundingFingerprint.of(
            subject = SubjectKind.MATH,
            expectedParentKnowledgeDisplayName = " Function Properties ",
            query = "Derivative   Sign",
        )
        val equivalent = KnowledgeGroundingFingerprint.of(
            subject = SubjectKind.MATH,
            expectedParentKnowledgeDisplayName = "function properties",
            query = "derivative sign",
        )
        val physics = KnowledgeGroundingFingerprint.of(
            subject = SubjectKind.PHYSICS,
            expectedParentKnowledgeDisplayName = "function properties",
            query = "derivative sign",
        )

        assertEquals(first, equivalent)
        assertNotEquals(first, physics)
        assertTrue(first.matches(Regex("grounding:[a-f0-9]{64}")))
        assertEquals(
            KnowledgeGroundingFingerprint.occurrenceId("request-1", 0, first),
            KnowledgeGroundingFingerprint.occurrenceId("request-1", 0, equivalent),
        )
        assertNotEquals(
            KnowledgeGroundingFingerprint.occurrenceId("request-1", 0, first),
            KnowledgeGroundingFingerprint.occurrenceId("request-1", 1, first),
        )
    }

    @Test
    fun resolutionAndBindingIdsAreStableButRemainTargetSpecific() {
        val groundingKey = KnowledgeGroundingFingerprint.of(
            subject = SubjectKind.MATH,
            expectedParentKnowledgeDisplayName = "函数性质",
            query = "导数符号与单调性",
        )

        val resolution = KnowledgeGroundingFingerprint.resolutionId(
            groundingKey = groundingKey,
            knowledgeNodeId = "kb:math:atomic:monotonicity",
        )
        assertEquals(
            resolution,
            KnowledgeGroundingFingerprint.resolutionId(
                groundingKey = groundingKey,
                knowledgeNodeId = "kb:math:atomic:monotonicity",
            ),
        )
        assertNotEquals(
            resolution,
            KnowledgeGroundingFingerprint.resolutionId(
                groundingKey = groundingKey,
                knowledgeNodeId = "kb:math:atomic:range",
            ),
        )

        val binding = KnowledgeGroundingFingerprint.resolvedBindingId(
            practiceUnitId = "unit-1",
            knowledgeNodeId = "kb:math:atomic:monotonicity",
            problemRevisionId = "revision-1",
            taxonomyVersion = "research-v1",
        )
        assertTrue(binding.matches(Regex("knowledge-binding:[a-f0-9]{40}")))
    }
}
