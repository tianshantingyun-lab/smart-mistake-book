package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorInteractionDirectiveTest {
    @Test
    fun choiceDirectiveAcceptsOnlyTwoToFourStudentChoices() {
        val twoChoices = TutorInteractionDirective.Choices(
            promptMarkdown = "下一步应先判断什么？",
            choices = listOf(
                TutorInteractionChoice("domain", "定义域"),
                TutorInteractionChoice("derivative", "导数符号"),
            ),
        )
        val fourChoices = twoChoices.copy(
            choices = twoChoices.choices + listOf(
                TutorInteractionChoice("vertex", "顶点"),
                TutorInteractionChoice("range", "值域"),
            ),
        )

        assertEquals(2, twoChoices.choices.size)
        assertEquals(4, fourChoices.choices.size)
        assertTrue(
            runCatching {
                twoChoices.copy(choices = twoChoices.choices.take(1))
            }.isFailure,
        )
        assertTrue(
            runCatching {
                twoChoices.copy(
                    choices = fourChoices.choices +
                        TutorInteractionChoice("axis", "对称轴"),
                )
            }.isFailure,
        )
    }

    @Test
    fun allDirectiveKindsRoundTripThroughPersistedModelOutput() {
        val directives = listOf(
            TutorInteractionDirective.Continue,
            TutorInteractionDirective.FreeResponse("说说你卡在哪一步。"),
            TutorInteractionDirective.Choices(
                promptMarkdown = "先看哪一项？",
                choices = listOf(
                    TutorInteractionChoice("condition", "条件"),
                    TutorInteractionChoice("formula", "公式"),
                ),
            ),
            TutorInteractionDirective.VisualTarget(
                promptMarkdown = "点出图中对应的区间。",
                targetId = "interval-positive",
            ),
        )

        directives.forEach { directive ->
            val output = output(directive)
            assertEquals(
                output,
                ModelTaskCodec.decodeOutput(ModelTaskCodec.encodeOutput(output)),
            )
        }
    }

    @Test
    fun cachedOutputWithoutDirectiveStillDecodes() {
        val encoded = ModelTaskCodec.encodeOutput(output(null))
        val legacy = encoded.replace("\"interactionDirective\":null,", "")

        assertTrue(encoded != legacy)
        val decoded = ModelTaskCodec.decodeOutput(legacy) as TutorPlanOutput
        assertNull(decoded.plan.interactionDirective)
    }

    private fun output(directive: TutorInteractionDirective?) = TutorPlanOutput(
        sessionId = "session-1",
        draftRevisionNumber = 1,
        questionDocumentId = "question-1",
        plan = TutorTurnPlan(
            openingMarkdown = "先看题目的已知条件。",
            diagnosticItem = null,
            interactionDirective = directive,
            solutionMarkdown = "由已知条件代入公式，整理后得到结果。",
            alternateMethodMarkdown = "也可以先画图，再从图上读取关系。",
            difficultyReasonMarkdown = "关键是对应当前小问中的已知量。",
            targetedEvidenceLabels = emptyList(),
            inferredKnowledgeLabels = listOf("函数"),
        ),
        modelVersion = "model-v1",
    )
}
