package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReTeachInjectionTest {

    @Test
    fun `a leeched card is re-taught before it is practiced again`() {
        val opening = ReTeachInjection.openingFor(
            memory = memoryState(lapseCount = 6, consecutiveCrossDayAgain = 2),
            references = listOf(misconceptionGuide()),
        )

        assertEquals("material:misconception", opening?.materialId)
        assertEquals("闭区间最值常见错误", opening?.title)
        assertEquals(KnowledgeTeachingMaterialType.MISCONCEPTION_GUIDE, opening?.materialType)
    }

    @Test
    fun `the opening carries the scope boundary so the material is not over-generalised`() {
        // 重教材料是"针对这类错误认知"的，不写出适用条件就等于放任学员把它外推到不成立
        // 的题目上——MISCONCEPTION_GUIDE / WORKED_EXAMPLE 正是最容易被过度外推的两类。
        val opening = ReTeachInjection.openingFor(
            memory = memoryState(lapseCount = 6, consecutiveCrossDayAgain = 2),
            references = listOf(misconceptionGuide()),
        )

        val markdown = requireNotNull(opening).markdown
        assertTrue(markdown.contains("端点值与驻点值必须同时比较"))
        assertTrue(markdown.contains("只用于闭区间上的最值问题"))
    }

    @Test
    fun `the first reference is the one re-taught because the caller ranks by re-teach priority`() {
        // 顺序的唯一权威在 TutorTeachingReferenceSelector.reTeachPriority；本策略只取首项，
        // 因此这里锁定"不重排"——若本函数擅自再排一次，两处顺序会漂移。
        val opening = ReTeachInjection.openingFor(
            memory = memoryState(lapseCount = 6, consecutiveCrossDayAgain = 2),
            references = listOf(misconceptionGuide(), genericExplanation()),
        )

        assertEquals("material:misconception", opening?.materialId)
    }

    @Test
    fun `a card one lapse short of the leech threshold is not forced into re-teaching`() {
        // 边界：6 次 lapse 是阈值本身（含），5 次不该触发——否则"重教"会退化成对所有
        // 难卡都开场的常规动作，把重教材料的可信度耗光。
        val opening = ReTeachInjection.openingFor(
            memory = memoryState(lapseCount = 5, consecutiveCrossDayAgain = 2),
            references = listOf(misconceptionGuide()),
        )

        assertNull(opening)
    }

    @Test
    fun `a card that has not failed twice across days is not forced into re-teaching`() {
        // 另一维阈值：跨日 Again 只有 1 次说明失败尚未被证实是"跨日仍复现"的稳定问题。
        val opening = ReTeachInjection.openingFor(
            memory = memoryState(lapseCount = 6, consecutiveCrossDayAgain = 1),
            references = listOf(misconceptionGuide()),
        )

        assertNull(opening)
    }

    @Test
    fun `a leeched card with no teaching material yields no opening`() {
        // 材料库没有该知识点的内容时不能编造材料，也不能假装重教发生过。
        val opening = ReTeachInjection.openingFor(
            memory = memoryState(lapseCount = 7, consecutiveCrossDayAgain = 3),
            references = emptyList(),
        )

        assertNull(opening)
    }

    @Test
    fun `a card with no memory state is not re-taught`() {
        // 从未复习过的卡没有记忆状态（不可能 leech）；此处锁定"不因缺状态而误判为 leech"。
        val opening = ReTeachInjection.openingFor(
            memory = null,
            references = listOf(misconceptionGuide()),
        )

        assertNull(opening)
    }

    private fun memoryState(
        lapseCount: Int,
        consecutiveCrossDayAgain: Int,
    ) = ProblemMemoryState(
        practiceUnitId = "practice:m1:closed-interval-extrema:whole",
        stabilityDays = 2.0,
        difficulty = 9.0,
        lastReviewedAtEpochMillis = 10 * DAY_MILLIS,
        nextReviewAtEpochMillis = 12 * DAY_MILLIS,
        lapseCount = lapseCount,
        consecutiveCrossDayAgain = consecutiveCrossDayAgain,
        lastAttemptId = "attempt:last",
        projectorVersion = "projector-v7",
        checkpointSequence = 7,
    )

    private fun misconceptionGuide() = TutorTeachingReference(
        materialId = "material:misconception",
        subject = "MATH",
        materialType = KnowledgeTeachingMaterialType.MISCONCEPTION_GUIDE,
        title = "闭区间最值常见错误",
        summaryMarkdown = "只比较驻点、漏掉端点。",
        applicabilityMarkdown = "求闭区间最值时使用。",
        contentMarkdown = "端点值与驻点值必须同时比较，漏掉任一端点都会得到错误的极值。",
        boundaryMarkdown = "只用于闭区间上的最值问题，开区间不适用。",
        knowledgeNodeIds = listOf("knowledge:m1:math.derivative.closed_interval_extrema"),
    )

    private fun genericExplanation() = TutorTeachingReference(
        materialId = "material:explanation",
        subject = "MATH",
        materialType = KnowledgeTeachingMaterialType.CONCEPT_EXPLANATION,
        title = "闭区间最值讲解",
        summaryMarkdown = "最值的定义。",
        applicabilityMarkdown = "一般情形。",
        contentMarkdown = "函数最值的一般讲解。",
        boundaryMarkdown = "一般性说明。",
        knowledgeNodeIds = listOf("knowledge:m1:math.derivative.closed_interval_extrema"),
    )

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
