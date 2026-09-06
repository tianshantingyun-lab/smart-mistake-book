package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.KnowledgeQuizInput
import com.tingyun.smartmistakebook.core.model.KnowledgeQuizOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Knowledge review quiz protocol (spec dual-review-entry §3.3): the data-layer
 * wire parser — model returns question + choices + single correct id, anchored to
 * the material's boundary. Rejects stray keys and a correct id absent from choices.
 */
class KnowledgeQuizWireTest {

    private val input = KnowledgeQuizInput(
        knowledgeNodeId = "kc1",
        subjectId = "subject-math",
        materialTitle = "导数符号与单调性",
        materialContentMarkdown = "正文",
        materialBoundaryMarkdown = "仅覆盖导数符号与单调性",
    )

    private fun payload(
        correctChoiceId: String = "A",
        extraTopKey: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("questionMarkdown", "若 f'(x)>0 恒成立，则 f 在该区间…")
        put(
            "choices",
            kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put("choiceId", "A"); put("markdown", "单调递增") })
                add(buildJsonObject { put("choiceId", "B"); put("markdown", "单调递减") })
            },
        )
        put("correctChoiceId", correctChoiceId)
        if (extraTopKey) put("unexpected", "x")
    }

    private fun parse(payload: JsonObject): KnowledgeQuizOutput =
        payload.toKnowledgeQuiz(input, "fake/quiz-v1")

    @Test
    fun parsesQuestionChoicesAndCorrectId() {
        val output = parse(payload())
        assertEquals("若 f'(x)>0 恒成立，则 f 在该区间…", output.questionMarkdown)
        assertEquals(2, output.choices.size)
        assertEquals("A", output.choices[0].choiceId)
        assertEquals("单调递增", output.choices[0].markdown)
        assertEquals("A", output.correctChoiceId)
        assertEquals("fake/quiz-v1", output.modelVersion)
    }

    @Test
    fun rejectsUnexpectedTopLevelKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            parse(payload(extraTopKey = true))
        }
    }

    @Test
    fun rejectsACorrectChoiceIdThatIsNotPresent() {
        val raw = payload(correctChoiceId = "C")
        assertThrows(IllegalArgumentException::class.java) {
            parse(raw)
        }
    }
}
