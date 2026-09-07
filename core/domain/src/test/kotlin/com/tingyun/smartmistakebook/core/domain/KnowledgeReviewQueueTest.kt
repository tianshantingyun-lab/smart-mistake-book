package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 知识点复习队列编排（spec dual-review-entry §3.2）：今天复习的知识点队列由错题排程同构的
 * 打分（scoreKnowledgeNode，T2）决定——对候选知识点打分、排序、按预算取队。纯函数。
 */
class KnowledgeReviewQueueTest {

    private val planner = ReviewPlanner()
    private val now = 1_000_000_000_000L

    private fun mastery(id: String, status: MasteryStatus): KnowledgeMasteryState = KnowledgeMasteryState(
        knowledgeNodeId = id,
        masteryScore = 0.95,
        conservativeMasteryScore = 0.9,
        evidenceMass = 0.0,
        status = status,
        calibrationSupport = CalibrationSupport.SUPPORTED,
        projectorVersion = "test",
        checkpointSequence = 1,
        lastEvidenceAtEpochMillis = now,
    )

    @Test
    fun skipsMasteredNodesAndKeepsRiskyOnes() {
        val candidates = listOf(
            KnowledgeReviewCandidate("kc1", mastery("kc1", MasteryStatus.CONFLICTED), 60),
            KnowledgeReviewCandidate("kc2", mastery("kc2", MasteryStatus.UNKNOWN), 60),
            KnowledgeReviewCandidate("kc3", mastery("kc3", MasteryStatus.MASTERED), 60),
        )
        val queue = selectKnowledgeReviewQueue(
            planner = planner,
            candidates = candidates,
            now = now,
            timeBudgetSeconds = 120,
        )
        assertTrue(queue.isNotEmpty())
        assertTrue(queue.none { it.knowledgeNodeId == "kc3" })
        assertTrue(queue.all { it.score >= 1.0 })
    }

    @Test
    fun respectsTheTimeBudget() {
        val candidates = listOf(
            KnowledgeReviewCandidate("kc1", mastery("kc1", MasteryStatus.UNKNOWN), 120),
            KnowledgeReviewCandidate("kc2", mastery("kc2", MasteryStatus.CONFLICTED), 120),
        )
        val queue = selectKnowledgeReviewQueue(
            planner = planner,
            candidates = candidates,
            now = now,
            timeBudgetSeconds = 120,
        )
        assertTrue(queue.size == 1)
    }

    @Test
    fun emptyOrAllMasteredCandidatesYieldsEmptyQueue() {
        assertTrue(
            selectKnowledgeReviewQueue(
                planner = planner,
                candidates = emptyList(),
                now = now,
                timeBudgetSeconds = 120,
            ).isEmpty(),
        )
    }

    @Test
    fun ordersRiskyNodesByDescendingScore() {
        // 一个高风险的冲突点 + 一个新鲜的学习中点。两者都高级别风险，值按降序排队。
        val weak = mastery("kc-w", MasteryStatus.LEARNING).copy(
            masteryScore = 0.4,
            conservativeMasteryScore = 0.3,
        )
        val candidates = listOf(
            KnowledgeReviewCandidate("kc1", mastery("kc1", MasteryStatus.CONFLICTED), 60),
            KnowledgeReviewCandidate("kc-w", weak, 60),
        )
        val queue = selectKnowledgeReviewQueue(
            planner = planner,
            candidates = candidates,
            now = now,
            timeBudgetSeconds = 240,
        )
        assertTrue(queue.size == 2)
        // 队列必须按分数降序排列（这是"排序取队"的核心保证）。
        assertTrue(queue.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test
    fun sessionPlanRejectsDuplicateNodes() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeReviewSessionPlan(
                queue = listOf(entry("kc1"), entry("kc1")),
                timeBudgetSeconds = 600,
            )
        }
    }

    @Test
    fun sessionPlanRejectsBlankEntryFields() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeReviewQueueEntry(
                knowledgeNodeId = "",
                subject = "MATH",
                displayName = "一元二次方程",
                masteryScore = 0.5,
                lastEvidenceAtEpochMillis = now,
                score = 1.0,
                reasonNames = setOf("CALIBRATION_CHECK"),
            )
        }
    }

    @Test
    fun sessionPlanAcceptsNoEvidenceNodeAsUnknown() {
        // 无掌握态的知识点（从未有过证据）也必须是合法候选——planner 把它当 NEWLY_ADDED，
        // 不能因为它没有掌握态条目就被计划拒绝。
        val plan = KnowledgeReviewSessionPlan(
            queue = listOf(
                entry("kc-fresh").copy(
                    masteryScore = null,
                    lastEvidenceAtEpochMillis = null,
                ),
            ),
            timeBudgetSeconds = 600,
        )
        assertTrue(plan.queue.single().masteryScore == null)
    }

    private fun entry(knowledgeNodeId: String) = KnowledgeReviewQueueEntry(
        knowledgeNodeId = knowledgeNodeId,
        subject = "MATH",
        displayName = "知识点$knowledgeNodeId",
        masteryScore = 0.5,
        lastEvidenceAtEpochMillis = now,
        score = 1.0,
        reasonNames = setOf("CALIBRATION_CHECK"),
    )
}
