package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialNodeBindingRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialRecord
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住"讲解材料**只有字符预算一条约束，条数不设上限**"这条契约。
 *
 * **本文件在 2026-09-16 被有意改写。** 此前它钉的是相反的事实——"四个槽位是跨节点共享池"
 * "超配节点会把别的节点饿死"——那套语义随 `MAX_TEACHING_REFERENCES = 4` 一起废止。改写不是
 * 为了让测试变绿：旧断言描述的行为（同一知识点第 5 条起的材料永远送不到模型）正是被修掉的
 * 那个失败；保留它等于把缺陷钉成契约。
 *
 * 改写的依据是 2026-09-16 对内置包 10356 条材料的实测分布：
 * `markdownChars` min 64 / p50 195 / p90 295 / p99 463 / max 1029；
 * 单节点材料数最大 97、单节点字符合计最大 18885。预算 20000 因此**在真实数据上装得下**
 * 最坏的那个节点，条数上限才是此前真正卡住内容的那个——它让 649 个超配节点的材料不可达。
 *
 * 四条行为，各有它消灭的具体失败：
 *
 * 1. **同一知识点的材料全量返回**：这是本次改动的目的本身。改前第 5 条起不可见。
 * 2. **跨节点不再互相挤占**：改前两个节点共 6 条候选只能返回 4 条。
 * 3. **预算仍先到先得**：预算仍是唯一约束，超预算的材料不进结果——否则"任意召回"会变成
 *    无界提示词，那是另一个失败。
 * 4. **实测最坏情形仍装得下**：用真实的 97 条量级证明第 1 条在超配节点上成立，而不是只在
 *    小夹具上成立。
 *
 * 只用纯函数 [TutorTeachingReferenceSelector.select]，不需要数据库。
 *
 * **与 `RoomTutorTeachingReferenceRepositoryTest` 的分工**：那个文件钉"角色秩先于类型优先级"
 * "类型优先级相对顺序""并列时 title→id 稳定排序""只暴露被请求节点的材料"。这里不重复。
 */
class TutorTeachingReferenceBudgetTest {

    /**
     * [contentChars] 是**正文**的长度；其余三个字段各给 8 字。
     * 不能把总长平均摊到四个字段上——`TutorTeachingReference` 对每个字段各有自己的上限
     * （title 240 / summary 4000 / applicability 8000 / content 32000 / boundary 8000），
     * 平均摊会让 summary 先超限，测的就不是字符预算而是字段校验了。
     */
    private fun material(
        id: String,
        type: String,
        title: String = id,
        contentChars: Int = 12,
    ) = KnowledgeTeachingMaterialRecord(
        materialId = id,
        stableCode = id,
        subject = "CHEMISTRY",
        materialType = type,
        title = title,
        summaryMarkdown = "s".repeat(8),
        applicabilityMarkdown = "a".repeat(8),
        contentMarkdown = "c".repeat(contentChars),
        boundaryMarkdown = "b".repeat(8),
        derivationKind = "REVIEWED_SYNTHESIS",
        sourceId = "registry-test",
        sourceLocator = "test",
        contentFingerprint = "0".repeat(64),
        reviewedAtEpochMillis = 1L,
    )

    private fun binding(materialId: String, nodeId: String) =
        KnowledgeTeachingMaterialNodeBindingRecord(
            materialId = materialId,
            knowledgeNodeId = nodeId,
            role = "PRIMARY",
        )

    @Test
    fun `同一知识点的六条材料全部返回，不再被条数上限截断`() {
        // 这是本次改动要消灭的那个失败：改前 limit=4，同一知识点第 5 条起的材料
        // 永远送不到模型——它们写进了仓库、随 APK 分发、在数据库里存在，但不可达。
        val materials = (1..6).map { material("a$it", "CONCEPT_EXPLANATION") }
        val bindings = (1..6).map { binding("a$it", "node-a") }

        val selected = TutorTeachingReferenceSelector.select(
            subject = "CHEMISTRY",
            requestedKnowledgeNodeIds = setOf("node-a"),
            materials = materials,
            bindings = bindings,
        )

        assertEquals(
            "六条都要返回；返回 4 条说明还有条数门没拆干净",
            6,
            selected.size,
        )
        assertEquals(
            "且必须全来自同一节点",
            6,
            selected.count { "node-a" in it.knowledgeNodeIds },
        )
    }

    @Test
    fun `两个节点的材料可以同时全部返回，不再互相挤占`() {
        // 改前是"4 个槽位跨节点共享"，6 条候选只能返回 4 条，于是内容多的节点会挤掉另一个。
        val materials = (1..3).map { material("a$it", "CONCEPT_EXPLANATION") } +
            (1..3).map { material("b$it", "CONCEPT_EXPLANATION") }
        val bindings = (1..3).map { binding("a$it", "node-a") } +
            (1..3).map { binding("b$it", "node-b") }

        val selected = TutorTeachingReferenceSelector.select(
            subject = "CHEMISTRY",
            requestedKnowledgeNodeIds = setOf("node-a", "node-b"),
            materials = materials,
            bindings = bindings,
        )

        assertEquals(6, selected.size)
        assertTrue(
            "两个节点都必须拿到材料",
            selected.any { "node-a" in it.knowledgeNodeIds } &&
                selected.any { "node-b" in it.knowledgeNodeIds },
        )
    }

    @Test
    fun `字符预算是唯一约束，装不下的材料不进结果`() {
        val cap = TutorPlanInput.MAX_TEACHING_REFERENCE_MARKDOWN_CHARS
        // 三字段各 8 字，总长 = contentChars + 24；big 吃掉 19994，只剩 6 < small 的 100。
        val materials = listOf(
            material("big", "MISCONCEPTION_GUIDE", contentChars = cap - 30),
            material("small", "CONCEPT_EXPLANATION", contentChars = 76),
        )
        val bindings = listOf(binding("big", "node-a"), binding("small", "node-a"))

        val selected = TutorTeachingReferenceSelector.select(
            subject = "CHEMISTRY",
            requestedKnowledgeNodeIds = setOf("node-a"),
            materials = materials,
            bindings = bindings,
        )

        assertEquals(
            "条数不再设限，但预算仍是硬约束——否则'任意召回'会变成无界提示词",
            1,
            selected.size,
        )
        assertEquals("big", selected.single().materialId)
    }

    @Test
    fun `实测最坏情形仍装得进预算：单节点 97 条材料全量返回`() {
        // 内置包实测：单节点材料数最大 97（"光合作用与细胞呼吸过程"），单节点字符合计最大
        // 18885。下面按同样的条数、略高于实测中位数(p50=195)的单条长度构造 97×200=19400，
        // 断言全量返回——证明"任意召回"在真实的超配节点上成立，而不是只在小夹具上成立。
        // 若这条挂了，说明预算该重新按实测分布定值，而不是去恢复条数上限。
        val materials = (1..97).map {
            material("m$it", "CONCEPT_EXPLANATION", contentChars = 176)
        }
        val bindings = (1..97).map { binding("m$it", "node-big") }

        val selected = TutorTeachingReferenceSelector.select(
            subject = "CHEMISTRY",
            requestedKnowledgeNodeIds = setOf("node-big"),
            materials = materials,
            bindings = bindings,
        )

        assertEquals(
            "97 条合计 19400 字符仍在 20000 预算内，必须全量返回",
            97,
            selected.size,
        )
    }
}
