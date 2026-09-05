package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具授权矩阵不变量（spec model-intent-routing §3.2 + tool-loop-wiring §5.2）：
 * MASTERY_UPDATE（唯一可落库写工具）只能在 CURRENT_QUESTION_HELP + 声明集下被授权——
 * 任何其他意图（CASUAL/AMBIGUOUS/END_OR_PAUSE 等）即使声明也不放行；意图置信度低于
 * 阈值一律拒（routeEligible=false）。repository 工具环在此授权之上另有"仅 Respond 派遣可
 * 执行写工具"的本地锚定（见 RoomModelTaskRepository 工具环）——本测试锁定授权矩阵本体。
 */
class TutorToolAuthorizationTest {

    private fun decision(
        intent: TutorMessageIntent,
        confidence: Double = 0.9,
        capability: TutorRequestedLocalCapability = TutorRequestedLocalCapability.NONE,
        explicitActionRequest: Boolean = false,
    ) = TutorIntentDecision(
        intent = intent,
        confidence = confidence,
        explicitActionRequest = explicitActionRequest,
        memoryPreference = TutorMemoryPreference.UNCHANGED,
        requestedLocalCapability = capability,
    )

    private fun declared(vararg tools: TutorToolName) = tools.toSet()

    private val writeTool = TutorToolName.MASTERY_UPDATE

    @Test
    fun masteryUpdateRequiresCurrentQuestionHelpIntent() {
        val full = declared(TutorToolName.KNOWLEDGE_READ, TutorToolName.MASTERY_UPDATE)
        assertTrue(
            "CURRENT_QUESTION_HELP 高置信 + 已声明 → MASTERY_UPDATE 放行",
            TutorToolName.MASTERY_UPDATE in tutorToolAuthorization(
                decision(TutorMessageIntent.CURRENT_QUESTION_HELP),
                full,
            ).allowedTools,
        )
        // 其他意图一律不放行写工具，即便声明了
        listOf(
            TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP,
            TutorMessageIntent.LEARNING_PROGRESS_LOOKUP,
            TutorMessageIntent.APP_HELP_OR_SETTINGS,
            TutorMessageIntent.CASUAL_CONVERSATION,
            TutorMessageIntent.END_OR_PAUSE,
        ).forEach { intent ->
            assertFalse(
                "$intent 下 MASTERY_UPDATE 不应被授权",
                TutorToolName.MASTERY_UPDATE in tutorToolAuthorization(
                    decision(intent),
                    full,
                ).allowedTools,
            )
        }
    }

    @Test
    fun masteryUpdateNotAllowedWhenUndeclaredEvenUnderCurrentQuestion() {
        val noWriteDeclared = declared(TutorToolName.NOTEBOOK_READ)
        assertFalse(
            "未声明 MASTERY_UPDATE 即使 CURRENT_QUESTION_HELP 也不放行（declared ∩ intent）",
            TutorToolName.MASTERY_UPDATE in tutorToolAuthorization(
                decision(TutorMessageIntent.CURRENT_QUESTION_HELP),
                noWriteDeclared,
            ).allowedTools,
        )
    }

    @Test
    fun lowConfidenceIntentIsNotRouteEligible() {
        val result = tutorToolAuthorization(
            decision(TutorMessageIntent.CURRENT_QUESTION_HELP, confidence = 0.3),
            declared(TutorToolName.MASTERY_UPDATE),
        )
        assertFalse("意图置信度 0.3 < 0.45 → 不 routeEligible", result.routeEligible)
        assertTrue(result.allowedTools.isEmpty())
    }

    @Test
    fun ambiguousIntentNeverAuthorizesTools() {
        val result = tutorToolAuthorization(
            decision(TutorMessageIntent.AMBIGUOUS),
            declared(TutorToolName.MASTERY_UPDATE),
        )
        assertFalse(result.routeEligible)
        assertTrue(result.allowedTools.isEmpty())
    }

    @Test
    fun currentQuestionReadToolsAuthorizedButCasualNone() {
        val full = declared(
            TutorToolName.KNOWLEDGE_READ,
            TutorToolName.NOTEBOOK_READ,
            TutorToolName.MASTERY_READ,
            TutorToolName.MASTERY_UPDATE,
        )
        val current = tutorToolAuthorization(
            decision(TutorMessageIntent.CURRENT_QUESTION_HELP),
            full,
        ).allowedTools
        assertTrue(current.containsAll(setOf(TutorToolName.KNOWLEDGE_READ, TutorToolName.NOTEBOOK_READ)))
        assertTrue(current.contains(TutorToolName.MASTERY_READ))
        assertTrue(current.contains(TutorToolName.MASTERY_UPDATE))

        val casual = tutorToolAuthorization(
            decision(TutorMessageIntent.CASUAL_CONVERSATION),
            full,
        )
        assertTrue("CASUAL 零工具授权", casual.allowedTools.isEmpty())
    }

    @Test
    fun masteryReadRequiresLearningProgressOrCurrentQuestion() {
        val reads = declared(TutorToolName.NOTEBOOK_READ, TutorToolName.MASTERY_READ)
        assertTrue(
            TutorToolName.MASTERY_READ in tutorToolAuthorization(
                decision(TutorMessageIntent.LEARNING_PROGRESS_LOOKUP),
                reads,
            ).allowedTools,
        )
        assertFalse(
            "NOTEBOOK_LOOKUP 不含 MASTERY_READ",
            TutorToolName.MASTERY_READ in tutorToolAuthorization(
                decision(TutorMessageIntent.MISTAKE_NOTEBOOK_LOOKUP),
                reads,
            ).allowedTools,
        )
    }
}
