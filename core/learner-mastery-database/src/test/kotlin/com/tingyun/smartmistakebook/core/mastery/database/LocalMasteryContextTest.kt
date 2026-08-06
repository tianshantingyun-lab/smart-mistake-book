package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMasteryContextTest {
    @Test
    fun requestRejectsSeventeenthExactNodeAndFifthFallbackNode() {
        assertIllegalArgument {
            LocalMasteryContextRequest(
                subject = SUBJECT,
                exactStableNodeFingerprints =
                    (0..LocalMasteryContextRequest.MAX_EXACT_NODE_COUNT).map(::fingerprint),
            )
        }
        assertIllegalArgument {
            LocalMasteryContextRequest(
                subject = SUBJECT,
                exactStableNodeFingerprints = emptyList(),
                fallbackLimit = LocalMasteryContextRequest.MAX_FALLBACK_COUNT + 1,
            )
        }
        assertIllegalArgument {
            LocalMasteryContextRequest(
                subject = SUBJECT,
                exactStableNodeFingerprints = listOf(FINGERPRINT_A, FINGERPRINT_A),
            )
        }
    }

    @Test
    fun sixteenExactQuestionNodesAreFollowedByFourBoundedSubjectFocusNodes() {
        val questionNodes =
            (0 until LocalMasteryContextRequest.MAX_EXACT_NODE_COUNT)
                .map(::fingerprint)

        val request =
            LocalMasteryContextRequest(
                subject = SUBJECT,
                exactStableNodeFingerprints = questionNodes,
                fallbackLimit = LocalMasteryContextRequest.MAX_FALLBACK_COUNT,
            )
        val extraFocus =
            (
                LocalMasteryContextRequest.MAX_EXACT_NODE_COUNT until
                    LocalMasteryContextRequest.MAX_RESULT_COUNT
                ).map { index ->
                    projection(
                        nodeId = "extra-focus-$index",
                        stableFingerprint = fingerprint(index),
                        state = KnowledgeMasteryState.NEEDS_REINFORCEMENT,
                    )
                }
        val result =
            buildLocalMasteryContext(
                query = BoundLocalMasteryContextQuery(LEARNER_ID, request),
                exactProjections =
                    request.exactStableNodeFingerprints.mapIndexed { index, stableFingerprint ->
                        projection(
                            nodeId = "authorized-$index",
                            stableFingerprint = stableFingerprint,
                            state = KnowledgeMasteryState.STEADY,
                        )
                    },
                focusProjections = extraFocus,
                nowEpochMillis = NOW,
            )

        assertEquals(16, LocalMasteryContextRequest.MAX_EXACT_NODE_COUNT)
        assertEquals(16, request.exactStableNodeFingerprints.size)
        assertEquals(20, LocalMasteryContextRequest.MAX_RESULT_COUNT)
        assertEquals(20, result.items.size)
        assertEquals(
            List(16) { LocalMasteryContextSelection.EXACT } +
                List(4) { LocalMasteryContextSelection.SUBJECT_FOCUS },
            result.items.map(LocalMasteryContextItem::selection),
        )
    }

    @Test
    fun knowledgeNodeRequestRejectsCrossSubjectReferences() {
        assertIllegalArgument {
            LocalMasteryContextRequest.fromKnowledgeNodes(
                subject = SUBJECT,
                exactKnowledgeNodes =
                    listOf(
                        KnowledgeNodeRef(
                            subject = SubjectKind.PHYSICS,
                            knowledgeNodeId = "mechanics.motion",
                            taxonomyVersion = "taxonomy-v1",
                            knowledgePackVersion = "pack-v1",
                        ),
                    ),
            )
        }
    }

    @Test
    fun exactNodesLeadThenSubjectFocusIsDeduplicatedAndDeterministic() {
        val request =
            LocalMasteryContextRequest(
                subject = SUBJECT,
                exactStableNodeFingerprints = listOf(FINGERPRINT_B, FINGERPRINT_A),
                fallbackLimit = 2,
            )
        val result =
            buildLocalMasteryContext(
                query = BoundLocalMasteryContextQuery(LEARNER_ID, request),
                exactProjections =
                    listOf(
                        projection("exact-b", FINGERPRINT_B, KnowledgeMasteryState.STEADY),
                        projection("exact-a", FINGERPRINT_A, KnowledgeMasteryState.FAMILIARIZING),
                    ),
                focusProjections =
                    listOf(
                        projection("duplicate-a", FINGERPRINT_A, KnowledgeMasteryState.FAMILIARIZING),
                        projection("steady", FINGERPRINT_C, KnowledgeMasteryState.STEADY),
                        projection(
                            "needs-reinforcement",
                            FINGERPRINT_D,
                            KnowledgeMasteryState.NEEDS_REINFORCEMENT,
                        ),
                        projection("familiarizing", FINGERPRINT_E, KnowledgeMasteryState.FAMILIARIZING),
                    ),
                nowEpochMillis = NOW,
            )

        assertEquals(
            listOf(FINGERPRINT_A, FINGERPRINT_B, FINGERPRINT_D, FINGERPRINT_E),
            result.items.map(LocalMasteryContextItem::stableNodeIdentityFingerprint),
        )
        assertEquals(
            listOf(
                LocalMasteryContextSelection.EXACT,
                LocalMasteryContextSelection.EXACT,
                LocalMasteryContextSelection.SUBJECT_FOCUS,
                LocalMasteryContextSelection.SUBJECT_FOCUS,
            ),
            result.items.map(LocalMasteryContextItem::selection),
        )
    }

    @Test
    fun resultNeverExceedsTwentyItems() {
        val exactFingerprints =
            (0 until LocalMasteryContextRequest.MAX_EXACT_NODE_COUNT).map(::fingerprint)
        val fallbackFingerprints =
            (
                LocalMasteryContextRequest.MAX_EXACT_NODE_COUNT until
                    LocalMasteryContextRequest.MAX_RESULT_COUNT
                ).map(::fingerprint)
        val result =
            buildLocalMasteryContext(
                query =
                    BoundLocalMasteryContextQuery(
                        learnerId = LEARNER_ID,
                        request =
                            LocalMasteryContextRequest(
                                subject = SUBJECT,
                                exactStableNodeFingerprints = exactFingerprints.reversed(),
                                fallbackLimit = LocalMasteryContextRequest.MAX_FALLBACK_COUNT,
                            ),
                    ),
                exactProjections =
                    exactFingerprints.reversed().mapIndexed { index, stableFingerprint ->
                        projection(
                            nodeId = "exact-$index",
                            stableFingerprint = stableFingerprint,
                            state = KnowledgeMasteryState.STEADY,
                        )
                    },
                focusProjections =
                    fallbackFingerprints.mapIndexed { index, stableFingerprint ->
                        projection(
                            nodeId = "fallback-$index",
                            stableFingerprint = stableFingerprint,
                            state = KnowledgeMasteryState.NEEDS_REINFORCEMENT,
                        )
                    },
                nowEpochMillis = NOW,
            )

        assertEquals(LocalMasteryContextRequest.MAX_RESULT_COUNT, result.items.size)
    }

    @Test
    fun resultContractHasNoLearnerRawScoreExactTimeOrWeightFields() {
        val fieldNames =
            (
                LocalMasteryContextRequest::class.java.declaredFields +
                    LocalMasteryContext::class.java.declaredFields +
                    LocalMasteryContextItem::class.java.declaredFields
                ).map { it.name.lowercase() }

        assertTrue(fieldNames.none { "learner" in it })
        assertTrue(fieldNames.none { "score" in it || "micros" in it || "weight" in it })
        assertTrue(fieldNames.none { "epoch" in it || "timestamp" in it })
    }

    @Test
    fun projectionRowsOutsideTheBoundSubjectFailClosed() {
        val request =
            LocalMasteryContextRequest(
                subject = SUBJECT,
                exactStableNodeFingerprints = listOf(FINGERPRINT_A),
            )
        val failure =
            runCatching {
                buildLocalMasteryContext(
                    query = BoundLocalMasteryContextQuery(LEARNER_ID, request),
                    exactProjections =
                        listOf(
                            projection(
                                nodeId = "physics-node",
                                stableFingerprint = FINGERPRINT_A,
                                state = KnowledgeMasteryState.STEADY,
                                subject = SubjectKind.PHYSICS,
                            ),
                        ),
                    focusProjections = emptyList(),
                    nowEpochMillis = NOW,
                )
            }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    private fun projection(
        nodeId: String,
        stableFingerprint: String,
        state: KnowledgeMasteryState,
        subject: SubjectKind = SUBJECT,
    ): MasteryKnowledgeProjectionEntity =
        MasteryKnowledgeProjectionEntity(
            learnerId = LEARNER_ID,
            subject = subject.name,
            knowledgeNodeId = nodeId,
            taxonomyVersion = "taxonomy-v1",
            latestEvidenceKnowledgePackVersion = "pack-v1",
            stableNodeIdentityFingerprint = stableFingerprint,
            positiveEvidenceMicros = 700_000L,
            negativeEvidenceMicros = 300_000L,
            masteryScoreMicros = 700_000L,
            masteryState = state.name,
            trend = KnowledgeMasteryTrend.STABLE.name,
            observationCount = 2L,
            memoryStabilityMillis = 10_000L,
            recallDueAtEpochMillis = NOW + 10_000L,
            lastPositiveAtEpochMillis = NOW,
            lastNegativeAtEpochMillis = null,
            lastEvidenceAtEpochMillis = NOW,
            lastEventSequence = 1L,
            lastOrderedEventId = "event-$nodeId",
            projectionPolicyVersion = LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION,
            evidenceQualityMicros = 800_000L,
            independentProblemFamilyCount = 2L,
            distinctPresentationCount = 2L,
        )

    private fun assertIllegalArgument(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }

    private fun fingerprint(value: Int): String =
        value.toString(radix = 16).padStart(64, '0')

    private companion object {
        const val LEARNER_ID = "learner-context-test"
        const val NOW = 1_000L
        val SUBJECT = SubjectKind.MATH
        val FINGERPRINT_A = "a".repeat(64)
        val FINGERPRINT_B = "b".repeat(64)
        val FINGERPRINT_C = "c".repeat(64)
        val FINGERPRINT_D = "d".repeat(64)
        val FINGERPRINT_E = "e".repeat(64)
    }
}
