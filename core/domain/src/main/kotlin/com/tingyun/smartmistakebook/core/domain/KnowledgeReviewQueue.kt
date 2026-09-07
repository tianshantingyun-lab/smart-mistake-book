package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState

/**
 * 知识点复习队列的一个候选：一个知识点 + 其当前掌握态（供 [ReviewPlanner.scoreKnowledgeNode]
 * 打分）+ 该点复习的预估耗时（供时间预算约束）。
 */
data class KnowledgeReviewCandidate(
    val knowledgeNodeId: String,
    val state: KnowledgeMasteryState?,
    val estimatedDurationSeconds: Int,
) {
    init {
        require(knowledgeNodeId.isNotBlank()) { "Knowledge review candidate id must not be blank" }
        require(estimatedDurationSeconds > 0) { "Knowledge review estimate must be positive" }
    }
}

/**
 * 生成今天知识点复习队列（spec dual-review-entry §3.2）：对候选知识点用与错题排程同构的
 * 打分（[ReviewPlanner.scoreKnowledgeNode]）打分、排序，再在时间预算内取队。已掌握且新鲜的
 * 知识点被 scoreKnowledgeNode 跳过（不进队列）。纯函数，不依赖 DB/UI。
 *
 * 消灭的失败：知识点复习缺少"今天复习哪些点"的确定队列，导致 UI 无从排程或各界面自定。
 */
fun selectKnowledgeReviewQueue(
    planner: ReviewPlanner,
    candidates: List<KnowledgeReviewCandidate>,
    now: Long,
    timeBudgetSeconds: Int,
): List<ReviewPlanner.ScoredKnowledgeNode> {
    require(timeBudgetSeconds >= 0) { "Knowledge review time budget must not be negative" }
    val scored = candidates.mapNotNull { candidate ->
        planner.scoreKnowledgeNode(
            knowledgeNodeId = candidate.knowledgeNodeId,
            state = candidate.state,
            now = now,
        )?.let { scored -> scored to candidate.estimatedDurationSeconds }
    }
    var remainingSeconds = timeBudgetSeconds
    val selected = mutableListOf<ReviewPlanner.ScoredKnowledgeNode>()
    for ((scored, duration) in scored.sortedByDescending { it.first.score }) {
        if (duration > remainingSeconds) continue
        selected += scored
        remainingSeconds -= duration
    }
    return selected
}
