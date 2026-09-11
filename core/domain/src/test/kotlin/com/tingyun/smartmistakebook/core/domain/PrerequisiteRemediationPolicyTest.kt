package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrerequisiteRemediationPolicyTest {

    @Test
    fun `the remediation material is the first reference because the caller ranked them`() {
        // 顺序的唯一权威在 TutorTeachingReferenceSelector.reTeachPriority；本策略只取首项。
        // 若此处再排一次，两条注入通道会对同一份材料给出不同次序。
        val remediation = PrerequisiteRemediationPolicy.offer(
            prerequisiteName = "从图像读取单调性",
            references = listOf(misconceptionGuide(), genericExplanation()),
        )

        assertEquals("读图判断单调性常见错误", requireNotNull(remediation).title)
    }

    @Test
    fun `the material carries the scope boundary`() {
        // 补救材料针对的是"前置里的某一类错误"，不写出适用条件就等于放任学员把它外推到
        // 不成立的题目上。
        val remediation = PrerequisiteRemediationPolicy.offer(
            prerequisiteName = "从图像读取单调性",
            references = listOf(misconceptionGuide()),
        )

        val markdown = requireNotNull(remediation).markdown
        assertTrue(markdown.contains("单调区间要按定义域分段读"))
        assertTrue(markdown.contains("只用于可导函数的单调性判断"))
    }

    @Test
    fun `the card names the missing prerequisite so the student knows what is being filled`() {
        val remediation = PrerequisiteRemediationPolicy.offer(
            prerequisiteName = "从图像读取单调性",
            references = listOf(misconceptionGuide()),
        )

        assertEquals("从图像读取单调性", requireNotNull(remediation).prerequisiteName)
    }

    @Test
    fun `a prerequisite with no reviewed material yields no card`() {
        // 材料库没有该前置的内容时不能编造补救内容，也不能假装补救发生过。
        assertNull(
            PrerequisiteRemediationPolicy.offer(
                prerequisiteName = "从图像读取单调性",
                references = emptyList(),
            ),
        )
    }

    @Test
    fun `an unnamed prerequisite yields no card`() {
        // 叫不出名字的前置在界面上只会显示成一段无来由的材料，学员无从知道它在补什么。
        assertNull(
            PrerequisiteRemediationPolicy.offer(
                prerequisiteName = "  ",
                references = listOf(misconceptionGuide()),
            ),
        )
    }

    private fun misconceptionGuide() = TutorTeachingReference(
        materialId = "material:prereq-misconception",
        subject = "MATH",
        materialType = KnowledgeTeachingMaterialType.MISCONCEPTION_GUIDE,
        title = "读图判断单调性常见错误",
        summaryMarkdown = "只看局部形状就下结论。",
        applicabilityMarkdown = "由函数图像判断单调性时使用。",
        contentMarkdown = "单调区间要按定义域分段读，跨过间断点后不能把两侧并成一个区间。",
        boundaryMarkdown = "只用于可导函数的单调性判断，分段函数需分别处理。",
        knowledgeNodeIds = listOf("knowledge:m1:math.read-monotonicity-from-graph"),
    )

    private fun genericExplanation() = TutorTeachingReference(
        materialId = "material:prereq-explanation",
        subject = "MATH",
        materialType = KnowledgeTeachingMaterialType.CONCEPT_EXPLANATION,
        title = "单调性讲解",
        summaryMarkdown = "单调性的定义。",
        applicabilityMarkdown = "一般情形。",
        contentMarkdown = "单调性的一般讲解。",
        boundaryMarkdown = "一般性说明。",
        knowledgeNodeIds = listOf("knowledge:m1:math.read-monotonicity-from-graph"),
    )
}
