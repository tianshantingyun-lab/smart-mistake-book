package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.IndependentCorrectObservation
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import com.tingyun.smartmistakebook.core.model.ReviewReason
import com.tingyun.smartmistakebook.core.model.ReviewDifficultyBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 知识点复习队列编排（spec dual-review-entry §3.2）：今天复习的知识点队列由错题排程同构的
 * 打分（scoreKnowledgeNode，T2）决定——打分、按时间预算取队，并在分数接近时用"同讲解材料/
 * 同科目降权"和"难度档轮换"（与 ReviewPlanner.plan 同一套机制）让会话交错。纯函数。
 */
class KnowledgeReviewQueueTest {

    private val planner = ReviewPlanner()
    private val now = 1_000_000_000_000L

    /**
     * 到期风险走**产线那条读侧曲线**（审计 F-02 / N-14）：FSRS-6 ＋ 出厂衰减，
     * 与既有断言里的 `FsrsScheduleMath.retention(天数, 稳定度)` 同一个数。
     * 时区固定 UTC：夹具的"上一次复习"正好是 24 小时前，两种口径在 UTC 下都是 1 天。
     */
    private val curve = ForgettingCurve(algorithm = ForgettingCurveAlgorithm.FSRS6_POWER_LAW)
    private val zone = java.time.ZoneOffset.UTC

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

    /** 一条被校准为 SUPPORTED 的独立答对观察——构造非 HARD 难度档所必需。 */
    private fun supportedObservation(familyId: String) = IndependentCorrectObservation(
        itemFamilyId = familyId,
        studyDayEpochDay = 1,
        occurredAtEpochMillis = now,
        evidenceWeight = 1.0,
        calibration = CalibrationSnapshot(
            support = CalibrationSupport.SUPPORTED,
            sourceId = "test-calibration",
            version = "test-v1",
            validFromEpochMillis = 0,
            validUntilEpochMillis = now + 1,
        ),
    )

    private fun candidate(
        id: String,
        subject: String = "MATH",
        material: String? = "material-$id",
        state: KnowledgeMasteryState? = null,
        seconds: Int = 60,
    ) = KnowledgeReviewCandidate(
        knowledgeNodeId = id,
        subjectId = subject,
        materialGroupId = material,
        state = state,
        estimatedDurationSeconds = seconds,
    )

    @Test
    fun knowledgeRecallRiskAggregatesTheWeakestBoundItemAndSkipsUnknownOnes() {
        val memory = ProblemMemoryState(
            practiceUnitId = "unit-1",
            stabilityDays = 10.0,
            difficulty = 5.0,
            lastReviewedAtEpochMillis = now - 86_400_000L,
            nextReviewAtEpochMillis = now,
            lastAttemptId = "attempt-1",
            projectorVersion = "test",
            checkpointSequence = 1,
        )
        val risks = knowledgeRecallRiskByNode(
            boundPracticeUnitIdsByNode = mapOf(
                "kc-strong" to listOf("unit-1"),
                "kc-no-memory" to listOf("unit-missing"),
            ),
            memoryStates = mapOf("unit-1" to memory),
            nowEpochMillis = now,
            forgettingCurve = curve,
            zoneId = zone,
        )
        // 一道稳定题的 R 很高（近 1），但仍是"已知记忆"。
        assertTrue(risks.getValue("kc-strong") > 0.9)
        // 没有已知记忆的绑定题不进结果，由调用方回退到掌握度估计。
        assertTrue("kc-no-memory" !in risks)
    }

    @Test
    fun knowledgeRecallRiskUsesTheMinimumOverBoundItems() {
        val fresh = ProblemMemoryState(
            practiceUnitId = "unit-fresh",
            stabilityDays = 100.0,
            difficulty = 5.0,
            lastReviewedAtEpochMillis = now - 86_400_000L,
            nextReviewAtEpochMillis = now,
            lastAttemptId = "attempt-fresh",
            projectorVersion = "test",
            checkpointSequence = 1,
        )
        val decayed = fresh.copy(practiceUnitId = "unit-decayed", stabilityDays = 0.2)
        val risks = knowledgeRecallRiskByNode(
            boundPracticeUnitIdsByNode = mapOf("kc-1" to listOf("unit-fresh", "unit-decayed")),
            memoryStates = mapOf("unit-fresh" to fresh, "unit-decayed" to decayed),
            nowEpochMillis = now,
            forgettingCurve = curve,
            zoneId = zone,
        )
        assertTrue(risks.getValue("kc-1") < 0.8)
    }

    @Test
    fun scoreKnowledgeNodeUsesRecallRiskWhenProvided() {
        val state = mastery("kc1", MasteryStatus.LEARNING)
        val decayed = requireNotNull(
            planner.scoreKnowledgeNode("kc1", state, now, recallRisk = 0.2),
        )
        val fresh = requireNotNull(
            planner.scoreKnowledgeNode("kc1", state, now, recallRisk = 0.95),
        )
        assertTrue(ReviewReason.DUE_RECALL_RISK in decayed.reasons)
        assertTrue(
            "decayed score ${decayed.score} must exceed fresh score ${fresh.score}",
            decayed.score > fresh.score,
        )
    }

    @Test
    fun skipsMasteredNodesAndKeepsRiskyOnes() {
        val candidates = listOf(
            candidate("kc1", state = mastery("kc1", MasteryStatus.CONFLICTED)),
            candidate("kc2", state = mastery("kc2", MasteryStatus.UNKNOWN)),
            candidate("kc3", state = mastery("kc3", MasteryStatus.MASTERED)),
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
            candidate("kc1", state = mastery("kc1", MasteryStatus.UNKNOWN), seconds = 120),
            candidate("kc2", state = mastery("kc2", MasteryStatus.CONFLICTED), seconds = 120),
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
            candidate("kc1", state = mastery("kc1", MasteryStatus.CONFLICTED)),
            candidate("kc-w", state = weak),
        )
        val queue = selectKnowledgeReviewQueue(
            planner = planner,
            candidates = candidates,
            now = now,
            timeBudgetSeconds = 240,
        )
        assertTrue(queue.size == 2)
        // 分数仍是主导：多样性/难度只做小幅调整，不足以翻转明显分差。
        assertTrue(queue.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test
    fun interleavesTeachingMaterialsInsteadOfQuizzingTheSameMaterialBackToBack() {
        // 两个节点共用 m1、两个共用 m2，分数完全平局（都无证据）——降权让材料交错。
        val candidates = listOf(
            candidate("kc-a", material = "m1"),
            candidate("kc-b", material = "m1"),
            candidate("kc-c", material = "m2"),
            candidate("kc-d", material = "m2"),
        )
        val queue = selectKnowledgeReviewQueue(
            planner = planner,
            candidates = candidates,
            now = now,
            timeBudgetSeconds = 240,
        )
        assertEquals(4, queue.size)
        val materials = queue.map { scored ->
            candidates.single { it.knowledgeNodeId == scored.knowledgeNodeId }.materialGroupId
        }
        assertTrue(materials.zipWithNext().all { (a, b) -> a != b })
    }

    @Test
    fun interleavesSubjectsWhenMaterialsAlreadyDiffer() {
        // 材料各不相同，只有科目重复——同科降权仍应让两个科目交错。
        val candidates = listOf(
            candidate("kc-a", subject = "MATH", material = "m1"),
            candidate("kc-b", subject = "MATH", material = "m2"),
            candidate("kc-c", subject = "PHYSICS", material = "m3"),
            candidate("kc-d", subject = "PHYSICS", material = "m4"),
        )
        val queue = selectKnowledgeReviewQueue(
            planner = planner,
            candidates = candidates,
            now = now,
            timeBudgetSeconds = 240,
        )
        assertEquals(4, queue.size)
        val subjects = queue.map { scored ->
            candidates.single { it.knowledgeNodeId == scored.knowledgeNodeId }.subjectId
        }
        assertTrue(subjects.zipWithNext().all { (a, b) -> a != b })
    }

    @Test
    fun neverDropsNodesWhenEveryCandidateSharesOneMaterial() {
        // 回归护栏：多样性必须是软降权。若做成"硬约束"，全部共用一份材料时会截断到 1 个，
        // 学生会永远只复习到一个知识点。
        val candidates = (1..3).map { index -> candidate("kc-$index", material = "m-only") }
        val queue = selectKnowledgeReviewQueue(
            planner = planner,
            candidates = candidates,
            now = now,
            timeBudgetSeconds = 240,
        )
        assertEquals(3, queue.size)
    }

    @Test
    fun scoresKnowledgeNodesWithAMasteryDerivedDifficultyBand() {
        // 无证据 → 最难（HARD）；有一份被支撑的答对观察、平滑掌握 0.5 → MEDIUM。
        val medium = mastery("kc-mid", MasteryStatus.LEARNING).copy(
            masteryScore = 0.5,
            conservativeMasteryScore = 0.0,
            evidenceMass = 1.0,
            independentCorrectObservations = listOf(supportedObservation("family-mid")),
        )
        assertEquals(
            ReviewDifficultyBand.HARD,
            planner.scoreKnowledgeNode("kc-none", null, now)!!.difficultyBand,
        )
        assertEquals(
            ReviewDifficultyBand.MEDIUM,
            planner.scoreKnowledgeNode("kc-mid", medium, now)!!.difficultyBand,
        )
    }

    @Test
    fun cyclesDifficultyBandsWhenScoresTie() {
        // 无证据节点与"平滑掌握 0.5"的节点同为 4.0 分（5×0.2+3×1.0 = 5×0.5+3×0.5）：
        // 平局时按难度循环（首档 MEDIUM）先取中等难度的点，而不是永远按 id 取。
        val medium = mastery("kc-mid", MasteryStatus.LEARNING).copy(
            masteryScore = 0.5,
            conservativeMasteryScore = 0.0,
            evidenceMass = 1.0,
            independentCorrectObservations = listOf(supportedObservation("family-mid")),
        )
        val candidates = listOf(
            candidate("kc-none", material = "m-none"),
            candidate("kc-mid", material = "m-mid", state = medium),
        )
        val queue = selectKnowledgeReviewQueue(
            planner = planner,
            candidates = candidates,
            now = now,
            timeBudgetSeconds = 240,
        )
        assertEquals(listOf("kc-mid", "kc-none"), queue.map { it.knowledgeNodeId })
    }

    @Test
    fun sessionPlanRejectsDuplicateNodes() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeReviewSessionPlan(
                queue = listOf(entry("kc1"), entry("kc1")),
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
        )
        assertTrue(plan.queue.single().masteryScore == null)
    }

    private fun entry(knowledgeNodeId: String) = KnowledgeReviewQueueEntry(
        knowledgeNodeId = knowledgeNodeId,
        subject = "MATH",
        displayName = "知识点$knowledgeNodeId",
        masteryScore = 0.5,
        lastEvidenceAtEpochMillis = now,
    )
}
