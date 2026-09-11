package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort

/**
 * 一组知识点的前置关系解析结果：`dependent KC → 其前置 KC 集合`，以及这些关系**提到过**的
 * 全部知识点行。
 *
 * [nodesById] 同时包含被查询的 KC **和它们的**前置：前置 KC 通常不在被查询集合里（关系指
 * 向集合之外），而补救通道需要它的 `subject`（关系表按科目分区，材料检索也按科目过滤）与
 * `displayName`（要说出"缺的是哪一个前置"）。只带被查询的那些行，会让前置节点查无此行。
 */
internal data class KnowledgePrerequisiteGraph(
    val prerequisitesByDependent: Map<String, Set<String>>,
    val nodesById: Map<String, KnowledgeNodeSeedRecord>,
)

/**
 * 把知识点 id 解析成前置关系图的唯一入口（spec §2.9、§6/L4）。
 *
 * 消灭的失败：`ReviewPlanningRequest.knowledgePrerequisites` 自声明"取自 PREREQUISITE_OF
 * 关系表"，但生产调用点从未填过它，于是 §2.9 的前置门恒等于"无前置"——`prereqGap` 永远是
 * 0.0、`ReviewReason.PREREQ_GAP` 从不出现、`PREREQ_GAP_WEIGHT × gap` 是一段死算术，连带
 * §6/C3 的"共享前置的易混对"通道也一起失效（两者读的是同一张图）。
 *
 * 为什么不是各调用点自己查：排程侧（`StudyReviewPlannerService`）与会话侧
 * （`RoomBackedStudyExperienceRepository`）都要这张图，而"按科目分组 + 分块查询 + 上限"
 * 这三步只要有第二份实现就会漂移。上限 256 是 `RoomKnowledgeBaseStore` 的硬约束，超限会
 * 直接抛异常——分块不是优化，是正确性前提。
 */
internal class KnowledgePrerequisiteReader(
    private val database: StudyDatabasePort,
) {
    suspend fun graphFor(knowledgeNodeIds: Set<String>): KnowledgePrerequisiteGraph {
        if (knowledgeNodeIds.isEmpty()) {
            return KnowledgePrerequisiteGraph(emptyMap(), emptyMap())
        }
        val dependentsById = database.readKnowledgeNodesByIds(knowledgeNodeIds)
            .associateBy(KnowledgeNodeSeedRecord::knowledgeNodeId)
        val prerequisitesByDependent = linkedMapOf<String, MutableSet<String>>()
        // 关系表按 subject 分区，查询必须用 KC 自己的科目：题目的科目由采集/归因链路赋值，
        // 知识点科目由知识库给出，两者不一致时用题目科目去查会**静默返回空集**——前置关系
        // 看起来"不存在"，而不是查询出错了。
        dependentsById.values
            .groupBy(KnowledgeNodeSeedRecord::subject, KnowledgeNodeSeedRecord::knowledgeNodeId)
            .forEach { (subject, nodeIds) ->
                nodeIds.chunked(MAX_DEPENDENT_NODES_PER_QUERY).forEach { chunk ->
                    database.readKnowledgeNodeRelationsForDependents(subject, chunk.toSet())
                        .forEach { relation ->
                            prerequisitesByDependent
                                .getOrPut(relation.dependentKnowledgeNodeId) { linkedSetOf() }
                                .add(relation.prerequisiteKnowledgeNodeId)
                        }
                }
            }
        // 前置 KC 通常不在被查询集合里（关系指向集合之外），必须另行取回它们的行，
        // 否则补救通道拿不到它们的科目与名称。
        val prerequisiteIds = prerequisitesByDependent.values
            .flatMapTo(linkedSetOf()) { it }
            .apply { removeAll(dependentsById.keys) }
        val prerequisitesById = if (prerequisiteIds.isEmpty()) {
            emptyMap()
        } else {
            database.readKnowledgeNodesByIds(prerequisiteIds)
                .associateBy(KnowledgeNodeSeedRecord::knowledgeNodeId)
        }
        return KnowledgePrerequisiteGraph(
            prerequisitesByDependent = prerequisitesByDependent.mapValues { it.value.toSet() },
            nodesById = dependentsById + prerequisitesById,
        )
    }

    private companion object {
        /** 对齐 `RoomKnowledgeBaseStore.readKnowledgeNodeRelationsForDependents` 的 256 上限。 */
        const val MAX_DEPENDENT_NODES_PER_QUERY = 256
    }
}
