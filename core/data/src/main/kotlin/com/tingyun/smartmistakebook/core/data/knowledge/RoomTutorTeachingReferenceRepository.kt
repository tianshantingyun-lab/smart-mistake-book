package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialNodeBindingRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.port.KnowledgeReadPort
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialNodeRole
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference

class RoomTutorTeachingReferenceRepository(
    private val database: StudyDatabasePort,
) : TutorTeachingReferenceRepository {
    override suspend fun referencesFor(
        subject: String,
        knowledgeNodeIds: Set<String>,
    ): List<TutorTeachingReference> {
        require(subject.isNotBlank())
        require(knowledgeNodeIds.all(String::isNotBlank))
        if (knowledgeNodeIds.isEmpty()) return emptyList()

        // 取数上界远高于预算能容纳的条数（见该常量的 KDoc），所以"返回多少条"由字符预算
        // 决定，不由这里的取数决定——取数若成了实际约束，就等于又开了一条隐形的条数门。
        val materials = database.readKnowledgeTeachingMaterialsForNodes(
            subject = subject,
            knowledgeNodeIds = knowledgeNodeIds,
            limit = KnowledgeReadPort.MAX_TEACHING_MATERIAL_CANDIDATES,
        )
        if (materials.isEmpty()) return emptyList()
        val bindings = database.readKnowledgeTeachingMaterialNodeBindings(
            materials.mapTo(linkedSetOf(), KnowledgeTeachingMaterialRecord::materialId),
        )
        return TutorTeachingReferenceSelector.select(
            subject = subject,
            requestedKnowledgeNodeIds = knowledgeNodeIds,
            materials = materials,
            bindings = bindings,
        )
    }
}

object TutorTeachingReferenceRepositoryFactory {
    fun create(database: StudyDatabasePort): TutorTeachingReferenceRepository =
        RoomTutorTeachingReferenceRepository(database)
}

internal object TutorTeachingReferenceSelector {
    /**
     * 重教材料的类型优先级，数字越小越优先（spec §2.16「强制注入 teaching_material 重教」，
     * 依据见 `docs/research/leech-remediation-research.md` R5/R6 与 §5.1）。
     *
     * 消除的失败：此前的顺序由 `KnowledgeTeachingMaterialDao.readForKnowledgeNodes` 的
     * `ORDER BY` 给出，而那份 CASE 把 `MISCONCEPTION_GUIDE` 排在**最后**、`WORKED_EXAMPLE`
     * 排第四——与证据相反。于是一次 leech 重教可能把预算花在泛泛的讲解上，把真正针对错误
     * 认知的材料挤出 `MAX_TEACHING_REFERENCE_MARKDOWN_CHARS`；而 `reviewedTeachingReferences`
     * 的**顺序**进了 `TutorModelTaskPolicy` 的请求指纹，顺序本身也是可观察行为。
     *
     * - `MISCONCEPTION_GUIDE`：纠正性反馈的核心是**分析导致错误的推理过程**
     *   （Metcalfe 2017, DOI 10.1146/annurev-psych-010416-044022），指南型材料正对此；
     * - `WORKED_EXAMPLE`：例题对经验较少者更有效（Bokosmaty, Sweller & Kalyuga 2015,
     *   DOI 10.3102/0002831214549450），且应与匹配练习**紧邻**呈现（Atkinson et al. 2000,
     *   DOI 10.3102/00346543070002181）；
     * - 讲解型（`METHOD_MODEL` / `DERIVATION` / `REPRESENTATION_GUIDE` / `CONCEPT_EXPLANATION`）无直接针对性；
     * - `COMPLETE_SOLUTION` 最低：Metcalfe 2025（DOI 10.1111/bjep.12651）的课堂分析显示
     *   「以得到正确解法为目标」劣于「让学生理解自己的错误」——"直接显示正确答案 + 附材料"
     *   正是被证明更差的那一类，故重教场景下它排最后。
     *
     * 这是**排序而非过滤**：材料库未必含高优先级类型（内置包只有 `METHOD_MODEL` 与
     * `CONCEPT_EXPLANATION`），过滤会让重教通道在空集上变成死门。
     */
    fun reTeachPriority(type: KnowledgeTeachingMaterialType): Int = when (type) {
        KnowledgeTeachingMaterialType.MISCONCEPTION_GUIDE -> 0
        KnowledgeTeachingMaterialType.WORKED_EXAMPLE -> 1
        KnowledgeTeachingMaterialType.METHOD_MODEL -> 2
        KnowledgeTeachingMaterialType.DERIVATION -> 3
        KnowledgeTeachingMaterialType.CONCEPT_EXPLANATION -> 4
        KnowledgeTeachingMaterialType.REPRESENTATION_GUIDE -> 5
        KnowledgeTeachingMaterialType.COMPLETE_SOLUTION -> 6
    }

    fun select(
        subject: String,
        requestedKnowledgeNodeIds: Set<String>,
        materials: List<KnowledgeTeachingMaterialRecord>,
        bindings: List<KnowledgeTeachingMaterialNodeBindingRecord>,
    ): List<TutorTeachingReference> {
        val bindingsByMaterial = bindings.groupBy(
            KnowledgeTeachingMaterialNodeBindingRecord::materialId,
        )
        // 排序在这里做而不是只靠 SQL：DAO 的取数顺序必须与本函数一致（该查询的注释说明
        // 了这条约束），而顺序的可测性与权威表达在这里。
        //
        // 没有条数上限之后，顺序的作用从"谁能进预算"变成"进预算后以什么次序出现在提示词里"
        // ——预算通常装得下（一题 5 个中位节点约 3800 字符，预算 20000），所以并列时的
        // 次序不再决定谁被饿死；但它仍进请求指纹，因此必须与 DAO 的 ORDER BY 逐项对齐：
        // 角色秩 → 类型优先级 → title → material_id。用 material_id（一个 SHA-256 派生值）
        // 单独排序等于按哈希次序决定提示词里材料的先后。
        val ranked = materials.asSequence()
            .filter { material -> material.subject == subject }
            .mapNotNull { material ->
                val matchedBindings = bindingsByMaterial[material.materialId]
                    .orEmpty()
                    .filter { it.knowledgeNodeId in requestedKnowledgeNodeIds }
                val matchedKnowledgeNodeIds = matchedBindings
                    .map(KnowledgeTeachingMaterialNodeBindingRecord::knowledgeNodeId)
                    .distinct()
                if (matchedKnowledgeNodeIds.isEmpty()) return@mapNotNull null
                val roleRank = matchedBindings.minOf { bindingRoleRank(it.role) }
                val reference = TutorTeachingReference(
                    materialId = material.materialId,
                    subject = material.subject,
                    materialType = enumValueOf<KnowledgeTeachingMaterialType>(
                        material.materialType,
                    ),
                    title = material.title,
                    summaryMarkdown = material.summaryMarkdown,
                    applicabilityMarkdown = material.applicabilityMarkdown,
                    contentMarkdown = material.contentMarkdown,
                    boundaryMarkdown = material.boundaryMarkdown,
                    knowledgeNodeIds = matchedKnowledgeNodeIds,
                )
                RankedReference(roleRank, reTeachPriority(reference.materialType), reference)
            }
            .sortedWith(
                compareBy(
                    { it.roleRank },
                    { it.typePriority },
                    { it.reference.title },
                    { it.reference.materialId },
                ),
            )
            .map(RankedReference::reference)
            .toList()

        var remainingChars = TutorPlanInput.MAX_TEACHING_REFERENCE_MARKDOWN_CHARS
        val selected = mutableListOf<TutorTeachingReference>()
        ranked.forEach { reference ->
            if (reference.markdownChars <= remainingChars) {
                selected += reference
                remainingChars -= reference.markdownChars
            }
        }
        return selected
    }

    /** 与 DAO 的 `best_role_rank` CASE 同构：PRIMARY 先于 SUPPORTING，其余最后。 */
    private fun bindingRoleRank(role: String): Int = when (role) {
        KnowledgeMaterialNodeRole.PRIMARY.name -> 0
        KnowledgeMaterialNodeRole.SUPPORTING.name -> 1
        else -> 2
    }

    private data class RankedReference(
        val roleRank: Int,
        val typePriority: Int,
        val reference: TutorTeachingReference,
    )
}
