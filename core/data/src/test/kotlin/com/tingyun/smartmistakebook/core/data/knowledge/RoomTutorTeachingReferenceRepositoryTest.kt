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
            limit = 4,
        )

        assertEquals(
            listOf("method", "example", "solution", "derivation"),
            selected.map { it.materialId },
        )
        assertEquals(
            listOf(
                KnowledgeTeachingMaterialType.METHOD_MODEL,
                KnowledgeTeachingMaterialType.WORKED_EXAMPLE,
                KnowledgeTeachingMaterialType.COMPLETE_SOLUTION,
                KnowledgeTeachingMaterialType.DERIVATION,
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
            limit = 4,
        )

        assertTrue(selected.isEmpty())
    }

    private fun material(
        id: String,
        subject: String = "MATH",
        type: KnowledgeTeachingMaterialType = KnowledgeTeachingMaterialType.METHOD_MODEL,
        content: String = "方法说明。",
    ) = KnowledgeTeachingMaterialRecord(
        materialId = id,
        stableCode = "stable:$id",
        subject = subject,
        materialType = type.name,
        title = "资料 $id",
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
    ) = KnowledgeTeachingMaterialNodeBindingRecord(
        materialId = materialId,
        knowledgeNodeId = knowledgeNodeId,
        role = KnowledgeMaterialNodeRole.PRIMARY.name,
    )
}
