package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 知识点复习考察→选择题判定项（spec dual-review-entry §3.3 P1）：把 KNOWLEDGE_QUIZ 的
 * 输出（question + choices + correctChoiceId）映射成可渲染、可判答的 [TutorAssessmentItem]，
 * 复用其 evaluateChoice 判答。消灭"UI 各自组装考察项、判答标准不一"的失败。
 */
class KnowledgeQuizAssessmentMappingTest {

    private val quizOutput = KnowledgeQuizOutput(
        questionMarkdown = "若 f'(x)>0 恒成立，则 f 在该区间…",
        choices = listOf(
            KnowledgeQuizChoice("A", "单调递增"),
            KnowledgeQuizChoice("B", "单调递减"),
        ),
        correctChoiceId = "A",
        modelVersion = "fake/quiz-v1",
    )

    private val item = quizOutput.toTutorAssessmentItem(
        knowledgeNodeId = "kc1",
        itemId = "knowledge-quiz:item-1",
    )

    @Test
    fun mapsQuestionChoicesAndCorrectIdIntoAnAssessmentItem() {
        assertEquals("knowledge-quiz:item-1", item.id)
        assertEquals("若 f'(x)>0 恒成立，则 f 在该区间…", item.stemMarkdown)
        assertEquals(2, item.choices.size)
        assertEquals("A", item.choices[0].id)
        assertEquals("单调递增", item.choices[0].markdown)
        assertEquals("A", item.correctChoiceId)
        assertEquals(setOf("kc1"), item.knowledgeNodeIds)
    }

    @Test
    fun reusesTheAssessmentItemCorrectnessJudgment() {
        val correct = item.evaluateChoice("A")
        val wrong = item.evaluateChoice("B")
        assertTrue(correct.isCorrect)
        assertTrue(!wrong.isCorrect)
        assertEquals("A", correct.choice.id)
        assertEquals("B", wrong.choice.id)
    }

    @Test
    fun feedbackMarkdownIsAbsentForKnowledgeQuizChoices() {
        assertEquals(null, item.choices[0].feedbackMarkdown)
    }
}
