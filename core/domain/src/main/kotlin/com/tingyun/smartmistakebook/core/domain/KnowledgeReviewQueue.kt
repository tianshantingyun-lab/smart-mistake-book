package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState

/**
 * 知识点复习队列的一个候选：一个知识点 + 其当前掌握态（供 [ReviewPlanner.scoreKnowledgeNode]
 * 打分）+ 该点复习的预估耗时（供时间预算约束）。
 *
 * [subjectId] 与 [materialGroupId] 是会话交错维度（spec §3.2 "多样性"）：同科目的点连续出现
 * 会让跨科交错失效；同讲解材料的点连续出题会得到雷同题目。[materialGroupId] 为 null 表示
 * 该点没有可归组的讲解材料（此时只按科目交错）。
 */
data class KnowledgeReviewCandidate(
    val knowledgeNodeId: String,
    val subjectId: String,
    val materialGroupId: String?,
    val state: KnowledgeMasteryState?,
    val estimatedDurationSeconds: Int,
) {
    init {
        require(knowledgeNodeId.isNotBlank()) { "Knowledge review candidate id must not be blank" }
        require(subjectId.isNotBlank()) { "Knowledge review candidate subject must not be blank" }
        require(materialGroupId == null || materialGroupId.isNotBlank()) {
            "Knowledge review material group must not be blank when provided"
        }
        require(estimatedDurationSeconds > 0) { "Knowledge review estimate must be positive" }
    }
}

/**
 * One planned knowledge-review session for today (spec dual-review-entry §3.2):
 * the ordered queue of knowledge nodes to quiz the student on, derived from
 * today's mistake-review plan scope. Each entry carries only the node identity
 * plus the mastery facts the session needs to build a KNOWLEDGE_QUIZ request;
 * the teaching material itself is resolved at dispatch time via the canonical
 * [TutorTeachingReferenceRepository] so this plan stays a light, durable index.
 */
data class KnowledgeReviewSessionPlan(
    /** Ordered queue; empty exactly when today's scope has no risky node to review. */
    val queue: List<KnowledgeReviewQueueEntry> = emptyList(),
) {
    init {
        require(queue.map(KnowledgeReviewQueueEntry::knowledgeNodeId).distinct().size == queue.size) {
            "Knowledge review plan must not repeat a knowledge node"
        }
    }

    val isEmpty: Boolean
        get() = queue.isEmpty()
}

/** One planned knowledge-review queue entry (spec dual-review-entry §3.2/§3.3). */
data class KnowledgeReviewQueueEntry(
    val knowledgeNodeId: String,
    /** Subject the node belongs to; resolves the teaching material for the quiz input. */
    val subject: String,
    /** Stable, learner-facing node name for the session header. */
    val displayName: String,
    val masteryScore: Double?,
    val lastEvidenceAtEpochMillis: Long?,
) {
    init {
        require(knowledgeNodeId.isNotBlank()) { "Knowledge review entry id must not be blank" }
        require(subject.isNotBlank()) { "Knowledge review entry subject must not be blank" }
        require(displayName.isNotBlank()) { "Knowledge review entry name must not be blank" }
        require(masteryScore == null || masteryScore.isFinite() && masteryScore in 0.0..1.0) {
            "Knowledge review entry mastery must be between zero and one"
        }
        require(lastEvidenceAtEpochMillis == null || lastEvidenceAtEpochMillis >= 0) {
            "Knowledge review entry evidence time must not be negative"
        }
    }
}

/**
 * 生成今天知识点复习队列（spec dual-review-entry §3.2）：对候选知识点用与错题排程同构的
 * 打分（[ReviewPlanner.scoreKnowledgeNode]）打分，再按**同一套**机制取队——时间预算 +
 * 多样性（同讲解材料/同科目软降权）+ 难度档轮换（[ReviewPlanner.DIFFICULTY_CYCLE] 平局时
 * 轮换）。已掌握且新鲜的知识点被 scoreKnowledgeNode 跳过（不进队列）。纯函数，不依赖 DB/UI。
 *
 * 与错题排程的一处刻意差异：多样性只做**软降权**、不做"同族已用即停"的硬约束。知识点常共享
 * 一份讲解材料，硬约束会在共用材料时直接截断队列（学生永远只复习到第一个点）。
 *
 * 消灭的失败：知识点复习缺少"今天复习哪些点、按什么顺序"的确定队列，导致 UI 无从排程，
 * 或连续出同一份材料/同一科目的题，跨科交错与题目多样性失效。
 */
fun selectKnowledgeReviewQueue(
    planner: ReviewPlanner,
    candidates: List<KnowledgeReviewCandidate>,
    now: Long,
    timeBudgetSeconds: Int,
): List<ReviewPlanner.ScoredKnowledgeNode> {
    require(timeBudgetSeconds >= 0) { "Knowledge review time budget must not be negative" }
    val pool = candidates
        .mapNotNull { candidate ->
            planner.scoreKnowledgeNode(
                knowledgeNodeId = candidate.knowledgeNodeId,
                state = candidate.state,
                now = now,
            )?.let { scored -> scored to candidate }
        }
        .toMutableList()
    var remainingSeconds = timeBudgetSeconds
    val materialRepeats = mutableMapOf<String, Int>()
    val subjectRepeats = mutableMapOf<String, Int>()
    val selected = mutableListOf<ReviewPlanner.ScoredKnowledgeNode>()
    var preferredBandIndex = 0
    while (pool.isNotEmpty()) {
        val fitting = pool.filter { (_, candidate) ->
            candidate.estimatedDurationSeconds <= remainingSeconds
        }
        if (fitting.isEmpty()) break
        val desiredBand = ReviewPlanner.DIFFICULTY_CYCLE[
            preferredBandIndex % ReviewPlanner.DIFFICULTY_CYCLE.size,
        ]
        val chosen = fitting
            .sortedWith(
                compareByDescending<Pair<ReviewPlanner.ScoredKnowledgeNode, KnowledgeReviewCandidate>> {
                    it.first.score - diversityPenalty(it.second, materialRepeats, subjectRepeats)
                }
                    .thenBy { if (it.first.difficultyBand == desiredBand) 0 else 1 }
                    .thenBy { it.first.knowledgeNodeId },
            )
            .first()
        selected += chosen.first
        pool.removeAll { (scored, _) ->
            scored.knowledgeNodeId == chosen.first.knowledgeNodeId
        }
        remainingSeconds -= chosen.second.estimatedDurationSeconds
        chosen.second.materialGroupId?.let { materialRepeats.merge(it, 1, Int::plus) }
        subjectRepeats.merge(chosen.second.subjectId, 1, Int::plus)
        preferredBandIndex++
    }
    return selected
}

/** 同讲解材料/同科目重复出现的软降权；权重与错题排程的 family/source 降权同量级。 */
private fun diversityPenalty(
    candidate: KnowledgeReviewCandidate,
    materialRepeats: Map<String, Int>,
    subjectRepeats: Map<String, Int>,
): Double {
    val materialPenalty = (candidate.materialGroupId?.let { materialRepeats[it] } ?: 0) *
        MATERIAL_REPEAT_PENALTY
    val subjectPenalty = (subjectRepeats[candidate.subjectId] ?: 0) * SUBJECT_REPEAT_PENALTY
    return (materialPenalty + subjectPenalty).coerceAtMost(MAX_DIVERSITY_PENALTY)
}

private const val MATERIAL_REPEAT_PENALTY = 0.3
private const val SUBJECT_REPEAT_PENALTY = 0.2
private const val MAX_DIVERSITY_PENALTY = 1.5
