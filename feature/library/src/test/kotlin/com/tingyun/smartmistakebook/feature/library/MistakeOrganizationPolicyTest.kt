package com.tingyun.smartmistakebook.feature.library

import com.tingyun.smartmistakebook.core.domain.ConfirmedMistakeOrganization
import com.tingyun.smartmistakebook.core.domain.ConfirmedProblemClassification
import com.tingyun.smartmistakebook.core.domain.ConfirmedProblemRelation
import com.tingyun.smartmistakebook.core.model.ClassificationDimension
import com.tingyun.smartmistakebook.core.model.ProblemClassificationSuggestion
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationPlan
import com.tingyun.smartmistakebook.core.model.ProblemRelationKind
import com.tingyun.smartmistakebook.core.model.ProblemRelationSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MistakeOrganizationPolicyTest {
    @Test
    fun correctionCanSaveWithOnlyUserAddedChapterAndKnowledge() {
        assertTrue(
            canSaveOrganizationCorrection(
                hasKnowledge = true,
                hasChapter = true,
                selectedModelClassificationCount = 0,
                userClassificationCount = 2,
                isSaving = false,
            ),
        )
    }

    @Test
    fun correctionEditorPreselectsOnlyFactsAlreadyAcceptedByLocalPolicy() {
        val plan = plan()
        val confirmed = ConfirmedMistakeOrganization(
            classifications = listOf(
                ConfirmedProblemClassification(
                    ClassificationDimension.CHAPTER,
                    "chapter-functions",
                    "函数",
                ),
            ),
            relations = listOf(
                ConfirmedProblemRelation(
                    targetProblemId = "problem-high",
                    targetProblemRevisionId = "revision-problem-high",
                    kind = ProblemRelationKind.SAME_KNOWLEDGE,
                    confidence = 0.8,
                ),
            ),
        )

        assertEquals(setOf(1), confirmedClassificationIndexes(plan, confirmed))
        assertEquals(setOf(1), confirmedRelationIndexes(plan, confirmed))
        assertEquals(listOf(0), unconfirmedRelationIndexes(plan, confirmed))
    }

    @Test
    fun correctionEditorRetainsAcceptedClassificationsMissingFromCurrentModelPlan() {
        val confirmed = ConfirmedMistakeOrganization(
            classifications = listOf(
                ConfirmedProblemClassification(
                    ClassificationDimension.CHAPTER,
                    "chapter-functions",
                    "函数",
                ),
                ConfirmedProblemClassification(
                    ClassificationDimension.KNOWLEDGE,
                    "knowledge-custom",
                    "参数分类讨论",
                ),
            ),
        )

        val retained = unmappedConfirmedClassifications(plan(), confirmed)

        assertEquals(
            listOf(ClassificationDimension.KNOWLEDGE to "参数分类讨论"),
            retained.map { it.dimension to it.displayName },
        )
    }

    private fun plan() = ProblemOrganizationPlan(
        summaryMarkdown = "整理建议。",
        reviewPriorityMarkdown = "建议近期复习。",
        targetedEvidenceLabels = emptyList(),
        classifications = listOf(
            ProblemClassificationSuggestion(
                dimension = ClassificationDimension.KNOWLEDGE,
                displayName = "导数符号",
                rationaleMarkdown = "核心知识点。",
                confidence = 0.52,
            ),
            ProblemClassificationSuggestion(
                dimension = ClassificationDimension.CHAPTER,
                displayName = "函数",
                rationaleMarkdown = "属于函数板块。",
                confidence = 0.4,
            ),
        ),
        relations = listOf(
            relation("problem-low", 0.79),
            relation("problem-high", 0.8),
        ),
    )

    private fun relation(problemId: String, confidence: Double) = ProblemRelationSuggestion(
        targetProblemId = problemId,
        targetProblemRevisionId = "revision-$problemId",
        kind = ProblemRelationKind.SAME_KNOWLEDGE,
        rationaleMarkdown = "模型关系建议。",
        confidence = confidence,
    )
}
