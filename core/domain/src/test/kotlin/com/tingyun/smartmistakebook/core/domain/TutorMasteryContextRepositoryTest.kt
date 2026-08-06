package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TutorMasteryContextRepositoryTest {
    @Test
    fun requestRejectsCrossSubjectAndDuplicateNodes() {
        val math = node("math", SubjectKind.MATH)
        val biology = node("biology", SubjectKind.BIOLOGY)

        assertThrows(IllegalArgumentException::class.java) {
            TutorMasteryContextRequest(
                subject = SubjectKind.MATH,
                questionKnowledgeNodes = listOf(math, biology),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TutorMasteryContextRequest(
                subject = SubjectKind.MATH,
                questionKnowledgeNodes = listOf(math),
                fallbackKnowledgeNodes = listOf(math),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TutorMasteryContextRequest(
                subject = SubjectKind.MATH,
                questionKnowledgeNodes = listOf(math),
                relatedKnowledgeNodes = listOf(biology),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TutorMasteryContextRequest(
                subject = SubjectKind.MATH,
                questionKnowledgeNodes = listOf(math),
                relatedKnowledgeNodes = List(9) { index -> node("related-$index") },
            )
        }
    }

    @Test
    fun resultIsReboundedToExactRequestedRefsInRequestOrder() {
        val first = node("first")
        val fallback = node("fallback")
        val unrelated = node("unrelated")
        val request = TutorMasteryContextRequest(
            subject = SubjectKind.MATH,
            questionKnowledgeNodes = listOf(first),
            fallbackKnowledgeNodes = listOf(fallback),
        )
        val bounded = TutorMasteryContext(
            summaries = listOf(
                summary(unrelated),
                summary(fallback),
                summary(first),
            ),
            projectionIsCurrent = false,
        ).boundedTo(request)

        assertEquals(
            listOf(first, fallback),
            bounded.summaries.map(TutorMasterySummary::knowledgeNode),
        )
        assertEquals(false, bounded.projectionIsCurrent)
    }

    @Test
    fun boundedToKeepsRepositoryDisclosedRelatedNeighborhood() {
        val first = node("first")
        val related = node("related")
        val request = TutorMasteryContextRequest(
            subject = SubjectKind.MATH,
            questionKnowledgeNodes = listOf(first),
        )
        val bounded =
            TutorMasteryContext(
                summaries = listOf(summary(first)),
                relatedSummaries = listOf(summary(related)),
                relatedKnowledgeNodes = listOf(related),
            ).boundedTo(request)

        assertEquals(listOf(related), bounded.relatedSummaries.map(TutorMasterySummary::knowledgeNode))
        assertEquals(listOf(related), bounded.relatedKnowledgeNodes)
        assertEquals(listOf(first), bounded.summaries.map(TutorMasterySummary::knowledgeNode))
    }

    private fun summary(node: KnowledgeNodeRef) = TutorMasterySummary(
        knowledgeNode = node,
        displayName = node.knowledgeNodeId,
        status = TutorMasteryStatus.LEARNING,
    )

    private fun node(
        id: String,
        subject: SubjectKind = SubjectKind.MATH,
    ) = KnowledgeNodeRef(
        subject = subject,
        knowledgeNodeId = id,
        taxonomyVersion = "taxonomy-v1",
        knowledgePackVersion = "pack-v1",
    )
}
