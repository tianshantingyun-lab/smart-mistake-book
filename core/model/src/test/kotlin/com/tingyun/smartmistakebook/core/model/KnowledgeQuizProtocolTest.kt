package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 知识点复习考察（spec dual-review-entry §3.3）：`KNOWLEDGE_QUIZ` 模型任务的
 * 输入输出模型测试——按知识点 + 讲解材料（含 boundaryMarkdown）现场出选择题，
 * 输出含题干、选项与正确答案 id，且限定在讲解材料边界内。
 */
class KnowledgeQuizProtocolTest {

    private val input = KnowledgeQuizInput(
        knowledgeNodeId = "kc1",
        subjectId = "subject-math",
        materialTitle = "导数符号与单调性",
        materialContentMarkdown = "函数在某区间上导数大于零则单调递增……",
        materialBoundaryMarkdown = "仅覆盖导数符号与单调性，不涉及极值最值",
        lastMasteryScore = 0.62,
        lastEvidenceAtEpochMillis = 1_000_000,
    )

    private val output = KnowledgeQuizOutput(
        questionMarkdown = "若函数 f 在区间 (a,b) 上 f'(x)>0 恒成立，则 f 在该区间…",
        choices = listOf(
            KnowledgeQuizChoice("A", "单调递增"),
            KnowledgeQuizChoice("B", "单调递减"),
        ),
        correctChoiceId = "A",
        modelVersion = "fake/quiz-v1",
    )

    @Test
    fun inputRoundTripsThroughTheModelTaskCodec() {
        val request = ModelTaskRequest(
            requestId = "knowledge-quiz:kc1",
            input = input,
            occurredAtEpochMillis = 1_000_000,
        )
        val decoded = ModelTaskCodec.decodeRequest(ModelTaskCodec.encodeRequest(request))
        assertEquals(request, decoded)
        assertEquals(ModelTaskKind.KNOWLEDGE_QUIZ, (decoded.input as KnowledgeQuizInput).kind)
    }

    @Test
    fun outputRoundTripsThroughTheModelTaskCodec() {
        assertEquals(output, ModelTaskCodec.decodeOutput(ModelTaskCodec.encodeOutput(output)))
    }

    @Test
    fun outputValidationRequiresAChoiceThatMatchesTheCorrectId() {
        val request = ModelTaskRequest(
            requestId = "knowledge-quiz:kc1",
            input = input,
            occurredAtEpochMillis = 1_000_000,
        )
        assertTrue(ModelTaskCompletionValidator.validate(request, output).isEmpty())
    }

    @Test
    fun inputRejectsBlankKnowledgeNodeId() {
        assertThrows(IllegalArgumentException::class.java) {
            input.copy(knowledgeNodeId = " ")
        }
    }
}
