package com.tingyun.smartmistakebook.core.mastery.database

import org.junit.Assert.assertEquals
import org.junit.Test

class MasteryDigestFocusSelectionTest {
    @Test
    fun roundRobinPreservesDistinctRiskLanesBeforeBaselineFillers() {
        val result =
            roundRobinDistinctFocus(
                limit = 4,
                lanes =
                    listOf(
                        listOf(projection("wavering")),
                        listOf(projection("reinforcement-due")),
                        listOf(projection("familiarizing-due")),
                        listOf(projection("recall-due")),
                        listOf(projection("recent-negative")),
                        (1..8).map { projection("baseline-$it") },
                    ),
            )

        assertEquals(
            listOf("wavering", "reinforcement-due", "familiarizing-due", "recall-due"),
            result.map(MasteryKnowledgeProjectionEntity::knowledgeNodeId),
        )
    }

    @Test
    fun duplicateKnowledgeAcrossLanesIsCountedOnceAndDoesNotWasteTheLimit() {
        val shared = projection("shared")
        val result =
            roundRobinDistinctFocus(
                limit = 5,
                lanes =
                    listOf(
                        listOf(shared),
                        listOf(shared),
                        listOf(projection("familiarizing-due")),
                        listOf(projection("recall-due")),
                        listOf(projection("recent-negative")),
                        listOf(projection("baseline-1"), projection("baseline-2")),
                    ),
            )

        assertEquals(5, result.size)
        assertEquals(5, result.map { it.stableNodeIdentityFingerprint }.toSet().size)
        assertEquals(
            listOf("shared", "familiarizing-due", "recall-due", "recent-negative", "baseline-1"),
            result.map(MasteryKnowledgeProjectionEntity::knowledgeNodeId),
        )
    }

    @Test
    fun focusSelectionIsDeterministicForTheSameBoundedLanes() {
        val lanes =
            listOf(
                listOf(projection("wavering-1"), projection("wavering-2")),
                listOf(projection("due-1"), projection("due-2")),
                (1..4).map { projection("baseline-$it") },
            )

        val first = roundRobinDistinctFocus(limit = 6, lanes = lanes)
        val replay = roundRobinDistinctFocus(limit = 6, lanes = lanes)

        assertEquals(first, replay)
    }

    private fun projection(nodeId: String): MasteryKnowledgeProjectionEntity =
        MasteryKnowledgeProjectionEntity(
            learnerId = "local-learner",
            subject = "MATH",
            knowledgeNodeId = nodeId,
            taxonomyVersion = "taxonomy-v1",
            latestEvidenceKnowledgePackVersion = "pack-v1",
            stableNodeIdentityFingerprint =
                MasteryProjectionIdentity.fingerprint("MATH", nodeId, "taxonomy-v1"),
            positiveEvidenceMicros = 500_000L,
            negativeEvidenceMicros = 500_000L,
            masteryScoreMicros = 500_000L,
            masteryState = "FAMILIARIZING",
            trend = "STABLE",
            observationCount = 2L,
            memoryStabilityMillis = 10_000L,
            recallDueAtEpochMillis = 20_000L,
            lastPositiveAtEpochMillis = 10_000L,
            lastNegativeAtEpochMillis = 9_000L,
            lastEvidenceAtEpochMillis = 10_000L,
            lastEventSequence = 2L,
            lastOrderedEventId = "event-$nodeId",
            projectionPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
            evidenceQualityMicros = 800_000L,
            independentProblemFamilyCount = 2L,
            distinctPresentationCount = 2L,
        )
}
