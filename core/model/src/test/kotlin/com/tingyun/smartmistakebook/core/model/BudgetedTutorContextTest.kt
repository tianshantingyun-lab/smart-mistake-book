package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetedTutorContextTest {
    @Test
    fun keepsBoundarySummaryAndApplicabilityBeforeBody() {
        val reference = reference(content = "正文")
        val protectedChars = reference.materialType.name.length + reference.title.length
        val context = BudgetedTutorContext.from(
            input = plan(reference),
            maxChars = protectedChars +
                reference.summaryMarkdown.length +
                reference.applicabilityMarkdown.length +
                reference.boundaryMarkdown.length,
        )

        val accepted = context.teachingReferences.single()
        assertEquals(BudgetedTutorContext.POLICY_VERSION, context.policyVersion)
        assertEquals(reference.title, accepted.title)
        assertEquals(reference.summaryMarkdown, accepted.summaryMarkdown)
        assertEquals(reference.applicabilityMarkdown, accepted.applicabilityMarkdown)
        assertEquals(reference.boundaryMarkdown, accepted.boundaryMarkdown)
        assertNull(accepted.contentMarkdown)
    }

    @Test
    fun chineseUtf8BudgetKeepsTheBoundaryWholeOrDropsTheReference() {
        val reference = reference(
            summary = "摘要说明",
            applicability = "适用",
            boundary = "边界🙂",
            content = "正文",
        )
        val minimumParts = listOf(
            reference.materialType.name,
            reference.title,
            reference.boundaryMarkdown,
        )
        val minimumChars = minimumParts.sumOf(String::length)
        val minimumUtf8Bytes = minimumParts.sumOf { it.encodeToByteArray().size.toLong() }
        val accepted = BudgetedTutorContext.from(
            input = plan(reference),
            maxChars = minimumChars,
            maxUtf8Bytes = minimumUtf8Bytes,
        ).teachingReferences.single()
        val dropped = BudgetedTutorContext.from(
            input = plan(reference),
            maxChars = minimumChars,
            maxUtf8Bytes = minimumUtf8Bytes - 1,
        ).teachingReferences

        assertEquals(reference.boundaryMarkdown, accepted.boundaryMarkdown)
        assertNull(accepted.summaryMarkdown)
        assertNull(accepted.applicabilityMarkdown)
        assertNull(accepted.contentMarkdown)
        assertTrue(dropped.isEmpty())
    }

    private fun plan(reference: TutorTeachingReference) = TutorPlanInput(
        sessionId = "session-1",
        draftRevisionNumber = 1,
        subject = SubjectKind.MATH.name,
        questionDocument = QuestionDocument(
            id = "question-1",
            blocks = listOf(ContentBlock.Paragraph("stem", "求函数的单调区间。")),
        ),
        reviewedTeachingReferences = listOf(reference),
    )

    private fun reference(
        summary: String = "摘要",
        applicability: String = "适用",
        boundary: String = "边界",
        content: String,
    ) = TutorTeachingReference(
        materialId = "material-1",
        subject = SubjectKind.MATH.name,
        materialType = KnowledgeTeachingMaterialType.CONCEPT_EXPLANATION,
        title = "单调性",
        summaryMarkdown = summary,
        applicabilityMarkdown = applicability,
        contentMarkdown = content,
        boundaryMarkdown = boundary,
        knowledgeNodeIds = listOf("knowledge:math:monotonicity"),
    )
}
