package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorPlanHintProtocolTest {
    @Test
    fun guidedPlanRetainsOneBoundedCurrentStepHint() {
        val input = input(TutorExplanationMode.GUIDED)
        val constrained = output(SAFE_HINT).locallyConstrainedFor(input)

        assertEquals(SAFE_HINT, constrained.plan.hintMarkdown)
        assertEquals(TutorResponseIntent.ASK, constrained.plan.responseIntent)
        assertEquals(
            TutorInteractionDirective.FreeResponse(GUIDED_FREE_RESPONSE_PROMPT),
            constrained.plan.interactionDirective,
        )
        assertEquals(constrained, constrained.locallyConstrainedFor(input))
    }

    @Test
    fun directPlanAlwaysClearsModelAuthoredHint() {
        val input = input(TutorExplanationMode.DIRECT)
        val constrained = output(SAFE_HINT).locallyConstrainedFor(input)

        assertNull(constrained.plan.hintMarkdown)
        assertEquals(TutorResponseIntent.EXPLAIN, constrained.plan.responseIntent)
        assertTrue(constrained.plan.solutionRevealed)
        assertTrue(
            ModelTaskCompletionValidator.validate(request(input), constrained).isEmpty(),
        )
    }

    @Test
    fun hintRejectsBlankOversizedAnswerBearingAndSolutionEquivalentContent() {
        val base = output().plan
        val unsafeHints = listOf(
            "   ",
            "提".repeat(TutorTurnPlan.MAX_HINT_CHARS + 1),
            base.solutionMarkdown,
            "**${base.solutionMarkdown}**",
            "最终答案是 x=2。",
        )

        unsafeHints.forEach { hint ->
            assertTrue(
                "Expected hint to fail closed: $hint",
                runCatching { base.copy(hintMarkdown = hint) }.isFailure,
            )
        }
        assertTrue(
            runCatching {
                base.copy(
                    responseIntent = TutorResponseIntent.EXPLAIN,
                    hintMarkdown = SAFE_HINT,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                base.copy(
                    solutionRevealed = true,
                    hintMarkdown = SAFE_HINT,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                base.copy(
                    interactionDirective = null,
                    hintMarkdown = SAFE_HINT,
                )
            }.isFailure,
        )
    }

    @Test
    fun cachedPlanWithoutHintStillDecodesWithNullDefault() {
        val encoded = ModelTaskCodec.encodeOutput(output())
        val root = Json.parseToJsonElement(encoded).jsonObject
        val legacyPlan = root.getValue("plan").jsonObject.toMutableMap().apply {
            remove("hintMarkdown")
        }
        val legacy = JsonObject(
            root.toMutableMap().apply { put("plan", JsonObject(legacyPlan)) },
        ).toString()

        val decoded = ModelTaskCodec.decodeOutput(legacy) as TutorPlanOutput

        assertNull(decoded.plan.hintMarkdown)
        assertEquals("tutor-plan-v13-guided-single-step-hint", ModelPromptPolicyVersions.TUTOR_PLAN)
    }

    private fun request(input: TutorPlanInput) = ModelTaskRequest(
        requestId = "hint-plan-request",
        input = input,
        occurredAtEpochMillis = 1,
    )

    private fun input(mode: TutorExplanationMode) = TutorPlanInput(
        sessionId = "session-1",
        draftRevisionNumber = 1,
        subject = "MATH",
        questionDocument = QuestionDocument(
            id = "question-1",
            blocks = listOf(ContentBlock.Paragraph("stem", "求函数的单调区间")),
        ),
        explanationMode = mode,
    )

    private fun output(hintMarkdown: String? = null) = TutorPlanOutput(
        sessionId = "session-1",
        draftRevisionNumber = 1,
        questionDocumentId = "question-1",
        plan = TutorTurnPlan(
            openingMarkdown = "先判断导数在各区间的符号。",
            responseIntent = TutorResponseIntent.ASK,
            solutionRevealed = false,
            interactionDirective = TutorInteractionDirective.FreeResponse("下一步怎样判断？"),
            solutionMarkdown = "先求导，再解导数大于零与小于零的区间。",
            alternateMethodMarkdown = "也可以列导函数符号表，再读取单调区间。",
            difficultyReasonMarkdown = "关键是对应导数符号与函数单调性。",
            targetedEvidenceLabels = emptyList(),
            inferredKnowledgeLabels = listOf("导数"),
            hintMarkdown = hintMarkdown,
        ),
        modelVersion = "model-v1",
    )

    companion object {
        private const val SAFE_HINT = "先只判断导数在零点两侧分别是正还是负。"
    }
}
