package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialNodeBindingRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialRecord
import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialNodeRole
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomTutorTeachingReferenceRepositoryTest {
    @Test
    fun `selector keeps all reviewed teaching forms but only for requested subject nodes`() {
        val method = material(
            id = "method",
            subject = "MATH",
            type = KnowledgeTeachingMaterialType.METHOD_MODEL,
            content = "方法模型：先判断导数符号，再确定函数的增减。",
        )
        val example = material(
            id = "example",
            subject = "MATH",
            type = KnowledgeTeachingMaterialType.WORKED_EXAMPLE,
            content = "完整例题与解答：求导、找零点、列符号表、写出单调区间。",
        )
        val solution = material(
            id = "solution",
            subject = "MATH",
            type = KnowledgeTeachingMaterialType.COMPLETE_SOLUTION,
            content = "完整解答：逐步说明当前方法如何得到结论。",
        )
        val derivation = material(
            id = "derivation",
            subject = "MATH",
            type = KnowledgeTeachingMaterialType.DERIVATION,
            content = "推导：从定义出发得到判断条件。",
        )
        val unrelated = material(
            id = "physics",
            subject = "PHYSICS",
            type = KnowledgeTeachingMaterialType.CONCEPT_EXPLANATION,
            content = "匀变速直线运动。",
        )
        val selected = TutorTeachingReferenceSelector.select(
            subject = "MATH",
            requestedKnowledgeNodeIds = setOf("knowledge:math:monotonicity"),
            materials = listOf(method, example, solution, derivation, unrelated),
            bindings = listOf(
                binding("method", "knowledge:math:monotonicity"),
                binding("example", "knowledge:math:monotonicity"),
                binding("solution", "knowledge:math:monotonicity"),
                binding("derivation", "knowledge:math:monotonicity"),
                binding("physics", "knowledge:physics:motion"),
            ),
        )

        // 顺序由重教优先级策略给出，不是输入顺序——旧断言把传入顺序原样断言，因此对
        // `ORDER BY` 与选择器的排序**完全覆盖不到**（审计 §3.9）。
        assertEquals(
            listOf("example", "method", "derivation", "solution"),
            selected.map { it.materialId },
        )
        assertEquals(
            listOf(
                KnowledgeTeachingMaterialType.WORKED_EXAMPLE,
                KnowledgeTeachingMaterialType.METHOD_MODEL,
                KnowledgeTeachingMaterialType.DERIVATION,
                KnowledgeTeachingMaterialType.COMPLETE_SOLUTION,
            ),
            selected.map { it.materialType },
        )
        assertTrue(selected.any { it.contentMarkdown.contains("完整例题与解答") })
    }

    @Test
    fun `selector does not expose material bound only to another knowledge point`() {
        val selected = TutorTeachingReferenceSelector.select(
            subject = "MATH",
            requestedKnowledgeNodeIds = setOf("knowledge:math:monotonicity"),
            materials = listOf(material("other")),
            bindings = listOf(binding("other", "knowledge:math:trigonometry")),
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `a misconception guide outranks a generic concept explanation`() {
        // spec §2.16 的重教通道（"leech 多为概念错"）：针对错误认知的材料必须先于泛泛讲解
        // 进入字符预算，否则预算花在讲解上、真正对症的材料被挤出（研究
        // leech-remediation-research §3.1——纠正性反馈的核心是分析导致错误的推理）。
        val explanation = material(
            id = "a-explanation",
            type = KnowledgeTeachingMaterialType.CONCEPT_EXPLANATION,
        )
        val guide = material(id = "z-guide", type = KnowledgeTeachingMaterialType.MISCONCEPTION_GUIDE)

        val selected = TutorTeachingReferenceSelector.select(
            subject = "MATH",
            requestedKnowledgeNodeIds = setOf("knowledge:math:monotonicity"),
            materials = listOf(explanation, guide),
            bindings = listOf(
                binding("a-explanation", "knowledge:math:monotonicity"),
                binding("z-guide", "knowledge:math:monotonicity"),
            ),
        )

        assertEquals(
            listOf("z-guide", "a-explanation"),
            selected.map { it.materialId },
        )
    }

    @Test
    fun `a complete solution ranks last because showing the answer is not re-teaching`() {
        // 研究 §3.3（Metcalfe et al. 2025, DOI 10.1111/bjep.12651）：逐秒课堂分析显示
        // "以得到正确解法为目标"的教法劣于"让学生理解自己的错误"，而"直接显示正确答案 +
        // 附材料"正是被证明更差的那一类，故重教场景下 complete-solution 排最后。
        val solution = material(
            id = "a-solution",
            type = KnowledgeTeachingMaterialType.COMPLETE_SOLUTION,
        )
        val derivation = material(id = "z-derivation", type = KnowledgeTeachingMaterialType.DERIVATION)

        val selected = TutorTeachingReferenceSelector.select(
            subject = "MATH",
            requestedKnowledgeNodeIds = setOf("knowledge:math:monotonicity"),
            materials = listOf(solution, derivation),
            bindings = listOf(
                binding("a-solution", "knowledge:math:monotonicity"),
                binding("z-derivation", "knowledge:math:monotonicity"),
            ),
        )

        assertEquals(listOf("z-derivation", "a-solution"), selected.map { it.materialId })
    }

    @Test
    fun `binding role outranks material type so the node's primary reference is not displaced`() {
        // 角色（PRIMARY/SUPPORTING）是第一序键，与 DAO 的 `best_role_rank` 同构：某知识点
        // 的主材料不应因为另一材料类型更"对症"就被顶掉它在该节点上的首选地位。
        val primaryExplanation = material(
            id = "a-primary",
            type = KnowledgeTeachingMaterialType.CONCEPT_EXPLANATION,
        )
        val supportingGuide = material(
            id = "z-supporting",
            type = KnowledgeTeachingMaterialType.MISCONCEPTION_GUIDE,
        )

        val selected = TutorTeachingReferenceSelector.select(
            subject = "MATH",
            requestedKnowledgeNodeIds = setOf("knowledge:math:monotonicity"),
            materials = listOf(supportingGuide, primaryExplanation),
            bindings = listOf(
                binding("a-primary", "knowledge:math:monotonicity", KnowledgeMaterialNodeRole.PRIMARY),
                binding("z-supporting", "knowledge:math:monotonicity", KnowledgeMaterialNodeRole.SUPPORTING),
            ),
        )

        assertEquals(listOf("a-primary", "z-supporting"), selected.map { it.materialId })
    }

    @Test
    fun `equally ranked materials fall back to title then id so the order is stable`() {
        // 材料顺序进了 TutorModelTaskPolicy 的请求指纹，因此必须由内容决定而不是由行序决定：
        // 同样材料以任意顺序传入都要得到同一结果。
        //
        // 并列键是 **title 然后 material_id**，与 DAO 的 ORDER BY 逐项对齐。本用例刻意让两者
        // 相反——按 id 排会得到 a-id-early 在前，按 title 排得到 z-id-late 在前——所以它抓得住
        // "只用 id 兜底"那种写法：material_id 是 SHA-256 派生值，用它单独排序等于按哈希
        // 决定提示词里材料的先后。
        //
        // 标题用 ASCII 而非中文：String 比较是 UTF-16 码元序，而中文的码点序与笔画/拼音序
        // 无关（例如「乙」U+4E59 排在「甲」U+7532 之前）。用中文会让"谁在前"变成一个需要
        // 查码表才能读懂的条件，测试表达的就成了别的意思。
        val laterById = material("z-id-late", title = "material A")
        val earlierById = material("a-id-early", title = "material B")
        val selected = TutorTeachingReferenceSelector.select(
            subject = "MATH",
            requestedKnowledgeNodeIds = setOf("knowledge:math:monotonicity"),
            materials = listOf(earlierById, laterById),
            bindings = listOf(
                binding("a-id-early", "knowledge:math:monotonicity"),
                binding("z-id-late", "knowledge:math:monotonicity"),
            ),
        )

        assertEquals(listOf("z-id-late", "a-id-early"), selected.map { it.materialId })
    }

    private fun material(
        id: String,
        subject: String = "MATH",
        type: KnowledgeTeachingMaterialType = KnowledgeTeachingMaterialType.METHOD_MODEL,
        content: String = "方法说明。",
        title: String = "资料 $id",
    ) = KnowledgeTeachingMaterialRecord(
        materialId = id,
        stableCode = "stable:$id",
        subject = subject,
        materialType = type.name,
        title = title,
        summaryMarkdown = "摘要",
        applicabilityMarkdown = "适用于当前知识点。",
        contentMarkdown = content,
        boundaryMarkdown = "只在题意满足时使用。",
        derivationKind = "REVIEWED_SYNTHESIS",
        sourceId = "source:$id",
        sourceLocator = "source",
        contentFingerprint = "A".repeat(64),
        reviewedAtEpochMillis = 1,
    )

    private fun binding(
        materialId: String,
        knowledgeNodeId: String,
        role: KnowledgeMaterialNodeRole = KnowledgeMaterialNodeRole.PRIMARY,
    ) = KnowledgeTeachingMaterialNodeBindingRecord(
        materialId = materialId,
        knowledgeNodeId = knowledgeNodeId,
        role = role.name,
    )
}
