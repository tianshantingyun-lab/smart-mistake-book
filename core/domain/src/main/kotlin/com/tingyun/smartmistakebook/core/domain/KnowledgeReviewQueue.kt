package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState

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
    /**
     * 该知识点绑定题目的预测检索概率（取绑定题中已知 FSRS 记忆的最小 R），null = 无可用记忆。
     * 供 [ReviewPlanner.scoreKnowledgeNode] 以遗忘曲线而非掌握度 EMA 估计到期风险。
     */
    val recallRisk: Double? = null,
) {
    init {
        require(knowledgeNodeId.isNotBlank()) { "Knowledge review candidate id must not be blank" }
        require(subjectId.isNotBlank()) { "Knowledge review candidate subject must not be blank" }
        require(materialGroupId == null || materialGroupId.isNotBlank()) {
            "Knowledge review material group must not be blank when provided"
        }
        require(estimatedDurationSeconds > 0) { "Knowledge review estimate must be positive" }
        require(recallRisk == null || recallRisk.isFinite() && recallRisk in 0.0..1.0) {
            "Knowledge review candidate recall risk must be between zero and one"
        }
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
/**
 * 知识点 → 其绑定题目的预测检索概率（取该点所有已知 FSRS 记忆中的**最小** R）。
 *
 * 用途：知识点自身没有记忆痕迹，其"是否快要忘"只能由承载它的题目来回答。取最小值是保守
 * 选择——只要有一道绑定题濒临遗忘，该知识点就值得复习。没有已知记忆的点不出现在结果里，
 * 由 [ReviewPlanner.scoreKnowledgeNode] 回退到掌握度估计。
 *
 * 依据：Cepeda et al. 2006/2008（间隔的意义相对于目标保持间隔）与 FSRS R(t,S) 曲线本身；
 * 掌握度 EMA 在"新鲜"窗口内不随时间衰减，不适合作为到期风险的唯一输入。
 *
 * 消灭的失败：知识点到期风险此前用 `1 − masteryScore` 与"45 天悬崖"近似，排序既不随时间
 * 连续变化，也与绑定题的真实遗忘状态脱节。
 */
fun knowledgeRecallRiskByNode(
    boundPracticeUnitIdsByNode: Map<String, List<String>>,
    memoryStates: Map<String, ProblemMemoryState>,
    nowEpochMillis: Long,
    /**
     * 读这条风险的曲线：它同时带来**口径**（`t` 是本地日历日）与**参数**（拟合出来的衰减）。
     * 收一个配置好的曲线、而不是在这里自己算天数或自己取默认衰减，是这一处曾经出过两次错的
     * 直接原因——它原先内联了一段"未取整的墙钟分数天"，于是同一张卡在这里与在错题详情上
     * 是两个不同的 R（审计 F-02）；而且它读的是 `FsrsScheduleMath` 的**出厂**衰减，
     * 与排期用的那条曲线不是同一条（审计 N-14）。
     */
    forgettingCurve: ForgettingCurve,
    zoneId: java.time.ZoneId,
): Map<String, Double> = boundPracticeUnitIdsByNode.mapNotNull { (nodeId, unitIds) ->
    unitIds.asSequence()
        .mapNotNull { unitId -> memoryStates[unitId] }
        .filter { it.stabilityDays > 0.0 && it.lastReviewedAtEpochMillis > 0 }
        .map { memory ->
            forgettingCurve.estimateAt(
                state = memory,
                atEpochMillis = nowEpochMillis,
                zoneId = zoneId,
            ).probability
        }
        .minOrNull()
        ?.let { nodeId to it }
}.toMap()

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
                recallRisk = candidate.recallRisk,
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
