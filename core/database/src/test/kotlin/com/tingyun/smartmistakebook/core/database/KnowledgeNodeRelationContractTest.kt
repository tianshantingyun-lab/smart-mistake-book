package com.tingyun.smartmistakebook.core.database

import org.junit.Assert.assertThrows
import org.junit.Test

class KnowledgeNodeRelationContractTest {
    @Test
    fun `accepts a reviewed same-subject prerequisite and rejects cycles`() {
        val forward = relation(PREREQUISITE_ID, DEPENDENT_ID)
        KnowledgeNodeRelationContract.validate(
            incoming = listOf(forward),
            nodes = listOf(node(PREREQUISITE_ID), node(DEPENDENT_ID)),
            sources = listOf(source()),
            existing = emptyList(),
        )

        val reverse = relation(DEPENDENT_ID, PREREQUISITE_ID)
        assertThrows(DatabaseContractViolationException::class.java) {
            KnowledgeNodeRelationContract.validate(
                incoming = listOf(reverse),
                nodes = listOf(node(PREREQUISITE_ID), node(DEPENDENT_ID)),
                sources = listOf(source()),
                existing = listOf(forward),
            )
        }
    }

    @Test
    fun `rejects unreviewed or cross-subject endpoints`() {
        val relation = relation(PREREQUISITE_ID, DEPENDENT_ID)
        assertThrows(DatabaseContractViolationException::class.java) {
            KnowledgeNodeRelationContract.validate(
                incoming = listOf(relation),
                nodes = listOf(
                    node(PREREQUISITE_ID),
                    node(DEPENDENT_ID).copy(subject = "PHYSICS"),
                ),
                sources = listOf(source()),
                existing = emptyList(),
            )
        }
        assertThrows(DatabaseContractViolationException::class.java) {
            KnowledgeNodeRelationContract.validate(
                incoming = listOf(relation),
                nodes = listOf(
                    node(PREREQUISITE_ID),
                    node(DEPENDENT_ID).copy(verificationStatus = "MODEL_CANDIDATE"),
                ),
                sources = listOf(source()),
                existing = emptyList(),
            )
        }
    }

    @Test
    fun `accepts a reviewed branch and rejects duplicate logical endpoints`() {
        val shared = "knowledge:shared"
        val first = relation(shared, PREREQUISITE_ID)
        val second = relation(shared, DEPENDENT_ID)
        KnowledgeNodeRelationContract.validate(
            incoming = listOf(first, second),
            nodes = listOf(node(shared), node(PREREQUISITE_ID), node(DEPENDENT_ID)),
            sources = listOf(source()),
            existing = emptyList(),
        )

        val duplicateFromAnotherLocator = first.copy(
            relationId = "pending",
            sourceLocator = "另一处课程内容",
        ).let { it.copy(relationId = KnowledgeNodeRelationContract.expectedId(it)) }
        assertThrows(DatabaseContractViolationException::class.java) {
            KnowledgeNodeRelationContract.validate(
                incoming = listOf(duplicateFromAnotherLocator),
                nodes = listOf(node(shared), node(PREREQUISITE_ID)),
                sources = listOf(source()),
                existing = listOf(first),
            )
        }
    }

    private fun relation(from: String, to: String): KnowledgeNodeRelationRecord {
        val draft = KnowledgeNodeRelationRecord(
            relationId = "pending",
            subject = "MATH",
            prerequisiteKnowledgeNodeId = from,
            dependentKnowledgeNodeId = to,
            relationType = StudyDbValue.KnowledgeRelationType.PREREQUISITE_OF,
            sourceId = SOURCE_ID,
            sourceLocator = "课程内容·函数性质",
            reviewedAtEpochMillis = 10,
        )
        return draft.copy(relationId = KnowledgeNodeRelationContract.expectedId(draft))
    }

    private fun node(id: String) = KnowledgeNodeSeedRecord(
        knowledgeNodeId = id,
        stableCode = "test:$id",
        subject = "MATH",
        displayName = id,
        parentKnowledgeNodeId = "topic",
        taxonomyVersion = "test-v1",
        createdAtEpochMillis = 5,
        canonicalName = id,
        nodeKind = "REASONING",
        granularity = "ATOMIC",
        verificationStatus = "SOURCE_GROUNDED",
    )

    private fun source() = KnowledgeSourceSeedRecord(
        sourceId = SOURCE_ID,
        subject = "MATH",
        sourceType = "OFFICIAL_CURRICULUM_STANDARD",
        title = "课程标准",
        publisher = "教育部",
        edition = "2020",
        sourceUri = "https://example.edu/standard.pdf",
        licenseStatus = "PUBLIC_OFFICIAL",
        contentFingerprint = "A".repeat(64),
        importedAtEpochMillis = 1,
    )

    private companion object {
        const val SOURCE_ID = "source:test:math"
        const val PREREQUISITE_ID = "knowledge:prerequisite"
        const val DEPENDENT_ID = "knowledge:dependent"
    }
}
