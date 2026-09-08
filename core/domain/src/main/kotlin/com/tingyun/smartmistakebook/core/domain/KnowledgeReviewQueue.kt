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
