package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.EventTimeTrust
import com.tingyun.smartmistakebook.core.model.IndependentCorrectObservation
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ReviewReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 知识点复习排程（spec dual-review-entry §3.2）：`scoreKnowledgeNode` 对单个知识点
 * 打分的纯函数测试——到期风险（lastEvidenceAt 距今 + 遗忘曲线/平滑掌握度）+
 * 掌握度风险（CONFLICTED/STALE/UNKNOWN → 高分），与错题排程 `scoreCandidate`
 * 对"题绑定的知识点"的打分逻辑同构。
 */
class KnowledgeReviewPlannerTest {

    private val planner = ReviewPlanner()
    private val now = 1_000_000_000_000L

    /** An observation whose calibration is SUPPORTED at [now], so it counts as evidence. */
    private fun supportedObservation(atEpochMillis: Long = now) = IndependentCorrectObservation(
        itemFamilyId = "fam1",
        studyDayEpochDay = 20_000,
        occurredAtEpochMillis = atEpochMillis,
        evidenceWeight = 1.0,
        calibration = CalibrationSnapshot(
            support = CalibrationSupport.SUPPORTED,
            sourceId = "test-source",
            version = "test-v0",
            validFromEpochMillis = 0,
            validUntilEpochMillis = Long.MAX_VALUE,
        ),
        timeTrust = EventTimeTrust.TRUSTED,
    )

    private fun mastery(
        id: String,
        status: MasteryStatus,
        conservative: Double = 0.9,
        masteryScore: Double = 0.95,
        lastEvidenceAt: Long? = now,
        observations: List<IndependentCorrectObservation> = emptyList(),
    ): KnowledgeMasteryState = KnowledgeMasteryState(
        knowledgeNodeId = id,
        masteryScore = masteryScore,
        conservativeMasteryScore = conservative,
        evidenceMass = 0.0,
        independentCorrectObservations = observations,
        status = status,
        calibrationSupport = CalibrationSupport.SUPPORTED,
        projectorVersion = "test",
        checkpointSequence = 1,
        lastEvidenceAtEpochMillis = lastEvidenceAt,
    )

    @Test
    fun conflictedKnowledgeNodeGetsHighScoreAndConflictReason() {
        val score = requireNotNull(planner.scoreKnowledgeNode(
            knowledgeNodeId = "kc1",
            state = mastery("kc1", MasteryStatus.CONFLICTED),
            now = now,
        ))
        assertTrue(score.score >= 1.0)
        assertTrue(score.reasons.contains(ReviewReason.CONFLICTED_KNOWLEDGE))
    }

    @Test
    fun unknownKnowledgeNodeGetsHighScore() {
        val score = requireNotNull(planner.scoreKnowledgeNode(
            knowledgeNodeId = "kc1",
            state = mastery("kc1", MasteryStatus.UNKNOWN),
            now = now,
        ))
        assertTrue(score.score >= 1.0)
        assertTrue(score.reasons.contains(ReviewReason.CALIBRATION_CHECK))
    }

    @Test
    fun staleKnowledgeNodeGetsHighScoreAndStaleReason() {
        val score = requireNotNull(planner.scoreKnowledgeNode(
            knowledgeNodeId = "kc1",
            state = mastery("kc1", MasteryStatus.STALE, lastEvidenceAt = now - 400L * 86_400_000L),
            now = now,
        ))
        assertTrue(score.score >= 1.0)
        assertTrue(score.reasons.contains(ReviewReason.STALE_KNOWLEDGE))
    }

    @Test
    fun masteredFreshKnowledgeNodeIsSkippedFromQueue() {
        assertNull(planner.scoreKnowledgeNode(
            knowledgeNodeId = "kc1",
            state = mastery(
                "kc1",
                MasteryStatus.MASTERED,
                conservative = 0.9,
                masteryScore = 0.95,
                lastEvidenceAt = now,
                observations = listOf(supportedObservation(now)),
            ),
            now = now,
        ))
    }

    @Test
    fun masteredButStaleEvidenceIsScoredForReview() {
        // 已掌握但证据过期（>45 天）→ 不该被 skip，应回到队列复习（含 STALE）。
        val score = requireNotNull(planner.scoreKnowledgeNode(
            knowledgeNodeId = "kc1",
            state = mastery(
                "kc1",
                MasteryStatus.MASTERED,
                conservative = 0.9,
                masteryScore = 0.95,
                lastEvidenceAt = now - 100L * 86_400_000L,
                observations = listOf(supportedObservation(now - 100L * 86_400_000L)),
            ),
            now = now,
        ))
        assertTrue(score.reasons.contains(ReviewReason.STALE_KNOWLEDGE))
    }
}
